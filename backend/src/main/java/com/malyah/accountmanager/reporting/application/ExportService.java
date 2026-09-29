package com.malyah.accountmanager.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseExportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExportedExpense;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.recurrences.application.PlannedForecast;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.reporting.domain.CsvDocument;
import com.malyah.accountmanager.reporting.domain.PlanningHorizon;

/**
 * H06.4 CSV exports. Expenses: exactly the population and order of the expense list for the same filters (shared
 * {@link ExpenseSelection#effective} defaults, predicate and order), every row, never a page. Forecasts of
 * recurrences go to a separate file (RF-CSV-04) with the filters of the planning. The count is checked against
 * {@link #MAXIMUM_ROWS} before anything is written, so a file is either complete or refused, never cut.
 */
public final class ExportService implements ExportUseCase {
    /** RNF-PER-03 validates exports of up to 10 thousand rows; larger selections are refused, not truncated. */
    public static final int MAXIMUM_ROWS = 10_000;
    static final List<String> EXPENSE_HEADER = List.of("Descrição", "Categoria", "Vencimento", "Valor da cobrança",
            "Estimativa", "Situação", "Valor pago", "Data do pagamento", "Responsável", "Pagador", "Origem", "Parcela",
            "ID do lançamento");
    static final List<String> FORECAST_HEADER = List.of("Descrição", "Categoria", "Vencimento previsto",
            "Valor previsto", "Estimativa", "Responsável", "Tipo", "ID da recorrência");

    private final ExpenseExportQueries expenses;
    private final RecurrenceForecastQueries forecasts;
    private final AuthenticatedUserContextQuery contexts;
    private final Clock clock;
    private final int maximumRows;

    public ExportService(ExpenseExportQueries expenses, RecurrenceForecastQueries forecasts,
            AuthenticatedUserContextQuery contexts, Clock clock) {
        this(expenses, forecasts, contexts, clock, MAXIMUM_ROWS);
    }

    ExportService(ExpenseExportQueries expenses, RecurrenceForecastQueries forecasts,
            AuthenticatedUserContextQuery contexts, Clock clock, int maximumRows) {
        this.expenses = Objects.requireNonNull(expenses);
        this.forecasts = Objects.requireNonNull(forecasts);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
        if (maximumRows < 1) throw new IllegalArgumentException("The row limit must be positive.");
        this.maximumRows = maximumRows;
    }

    @Override
    public CsvFile expenses(String actorEmail, ExpenseExportQuery query) {
        Objects.requireNonNull(query, "query");
        new ExpenseSelection(query.search(), query.dateFrom(), query.dateTo(), query.dateBasis(), query.categoryId(),
                query.withoutCategory(), query.responsibleUserId(), query.withoutResponsible(), query.payerUserId(),
                query.status(), null).validate();
        var actor = contexts.findByEmail(actorEmail);
        var today = LocalDate.now(clock.withZone(ZoneId.of(actor.timeZone())));
        var selection = new ExpenseSelection(query.search(), query.dateFrom(), query.dateTo(), query.dateBasis(),
                query.categoryId(), query.withoutCategory(), query.responsibleUserId(), query.withoutResponsible(),
                query.payerUserId(), query.status(), null).effective(today);
        var count = expenses.count(actor.spaceId(), selection);
        if (count > maximumRows) throw new ExportLimitExceededException(count, maximumRows);
        var document = new CsvDocument(EXPENSE_HEADER);
        expenses.export(actor.spaceId(), selection, query.sort() == null ? ExpenseSort.REFERENCE_DATE : query.sort(),
                query.direction() == null ? SortDirection.ASC : query.direction(), maximumRows,
                expense -> document.row(row(expense, today)));
        return new CsvFile(expenseFileName(selection), document.bytes(), document.rows());
    }

    @Override
    public CsvFile forecasts(String actorEmail, ForecastExportQuery query) {
        Objects.requireNonNull(query, "query");
        var search = query.search() == null ? null : query.search().trim();
        new ExpenseSelection(search, null, null, ExpenseDateBasis.DUE_DATE, query.categoryId(),
                query.withoutCategory(), query.responsibleUserId(), query.withoutResponsible(), null,
                ExpenseStatusFilter.ACTIVE, null).validate();
        var actor = contexts.findByEmail(actorEmail);
        var today = LocalDate.now(clock.withZone(ZoneId.of(actor.timeZone())));
        var horizon = PlanningHorizon.from(YearMonth.from(today));
        var filter = new ForecastFilter(search, query.categoryId(), query.withoutCategory(),
                query.responsibleUserId(), query.withoutResponsible());
        var selected = forecasts.unmaterialized(actor.spaceId(), horizon.start(), horizon.end()).stream()
                .filter(filter::accepts).toList();
        if (selected.size() > maximumRows) throw new ExportLimitExceededException(selected.size(), maximumRows);
        var document = new CsvDocument(FORECAST_HEADER);
        for (var forecast : selected) document.row(row(forecast));
        return new CsvFile("previsoes_" + horizon.start() + "_a_" + horizon.end() + ".csv", document.bytes(),
                document.rows());
    }

    static List<String> row(ExportedExpense expense, LocalDate today) {
        var cells = new ArrayList<String>(EXPENSE_HEADER.size());
        cells.add(CsvDocument.text(expense.description()));
        cells.add(CsvDocument.text(expense.categoryName()));
        cells.add(CsvDocument.date(expense.dueDate()));
        cells.add(CsvDocument.money(expense.chargeAmount()));
        cells.add(expense.chargeConfirmed() ? "Não" : "Sim");
        cells.add(situation(expense, today));
        var paid = expense.status() == com.malyah.accountmanager.expenses.domain.ExpenseStatus.PAID;
        cells.add(paid ? CsvDocument.money(expense.paidAmount()) : "");
        cells.add(paid ? CsvDocument.date(expense.paymentDate()) : "");
        cells.add(CsvDocument.text(expense.responsibleDisplayName()));
        cells.add(paid ? CsvDocument.text(expense.payerDisplayName()) : "");
        cells.add(origin(expense.origin()));
        // "n de N", not "n/N": Excel in Portuguese would turn "2/3" into the date 2 March.
        cells.add(expense.installment() == null ? ""
                : expense.installment().number() + " de " + expense.installment().count());
        cells.add(expense.id().toString());
        return cells;
    }

    static List<String> row(PlannedForecast forecast) {
        return List.of(CsvDocument.text(forecast.description()), CsvDocument.text(forecast.categoryName()),
                CsvDocument.date(forecast.dueDate()), CsvDocument.money(forecast.amount()),
                forecast.estimated() ? "Sim" : "Não", CsvDocument.text(forecast.responsibleDisplayName()),
                "Previsão de recorrência", forecast.recurrenceId().toString());
    }

    static String situation(ExportedExpense expense, LocalDate today) {
        return switch (expense.status()) {
            case PAID -> "Paga";
            case CANCELLED -> "Cancelada";
            case PENDING -> expense.dueDate() != null && expense.dueDate().isBefore(today) ? "Atrasada" : "Pendente";
        };
    }

    static String origin(String origin) {
        return switch (origin) {
            case "RECURRENCE" -> "Recorrência";
            case "INSTALLMENT" -> "Parcela";
            default -> "Avulsa";
        };
    }

    static String expenseFileName(ExpenseSelection selection) {
        var basis = selection.dateBasis() == ExpenseDateBasis.PAYMENT_DATE ? "pagamento" : "vencimento";
        String period;
        if (selection.dateFrom() == null) period = "ate_" + selection.dateTo();
        else if (selection.dateTo() == null) period = "desde_" + selection.dateFrom();
        else period = selection.dateFrom() + "_a_" + selection.dateTo();
        return "despesas_" + basis + "_" + period + ".csv";
    }
}
