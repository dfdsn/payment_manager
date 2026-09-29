package com.malyah.accountmanager.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpensePlanningQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.PlanningExpense;
import com.malyah.accountmanager.expenses.application.PlanningExpenseBucket;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.recurrences.application.PlannedForecast;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.reporting.domain.Money;
import com.malyah.accountmanager.reporting.domain.PlanningHorizon;
import com.malyah.accountmanager.reporting.domain.PlanningLine;
import com.malyah.accountmanager.reporting.domain.PlanningOrigin;
import com.malyah.accountmanager.reporting.domain.PlanningPageMerge;
import com.malyah.accountmanager.reporting.domain.PlanningTotals;
import com.malyah.accountmanager.reporting.domain.Situation;

/**
 * H06.3 integrated planning of the current month plus 12. Materialized expenses (one-off, installments and
 * generated recurrence occurrences, pending or paid) come from the expenses module with the shared predicate and
 * are summed by the database; forecasts come from the recurrences module, which already excludes every period that
 * has a materialized occurrence (identity recurrence + period), so a value is never counted twice. Opening the
 * planning writes nothing.
 */
public final class PlanningService implements PlanningUseCase {
    private final ExpensePlanningQueries expenses;
    private final RecurrenceForecastQueries forecasts;
    private final AuthenticatedUserContextQuery contexts;
    private final Clock clock;

    public PlanningService(ExpensePlanningQueries expenses, RecurrenceForecastQueries forecasts,
            AuthenticatedUserContextQuery contexts, Clock clock) {
        this.expenses = Objects.requireNonNull(expenses);
        this.forecasts = Objects.requireNonNull(forecasts);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PlanningView planning(String actorEmail, PlanningQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.page() < 0) throw new ReportQueryValidationException("page", "A página não pode ser negativa.");
        if (query.size() < 1 || query.size() > PlanningQuery.MAXIMUM_SIZE)
            throw new ReportQueryValidationException("size", "O tamanho da página deve ficar entre 1 e 100.");
        var requestedMonth = ReportingService.parseMonth(query.month());
        var actor = contexts.findByEmail(actorEmail);
        var today = LocalDate.now(clock.withZone(ZoneId.of(actor.timeZone())));
        var horizon = PlanningHorizon.from(YearMonth.from(today));
        var month = requestedMonth == null ? horizon.start() : requestedMonth;
        if (!horizon.contains(month))
            throw new ReportQueryValidationException("month", "Escolha um mês entre " + label(horizon.start())
                    + " e " + label(horizon.end()) + ", o horizonte do planejamento.");
        var search = query.search() == null ? null : query.search().trim();
        var selection = new ExpenseSelection(search, horizon.start().atDay(1), horizon.end().atEndOfMonth(),
                ExpenseDateBasis.DUE_DATE, query.categoryId(), query.withoutCategory(), query.responsibleUserId(),
                query.withoutResponsible(), null, ExpenseStatusFilter.ACTIVE, today);
        selection.validate();
        var filter = new ForecastFilter(search, query.categoryId(), query.withoutCategory(),
                query.responsibleUserId(), query.withoutResponsible());
        var planned = forecasts.unmaterialized(actor.spaceId(), horizon.start(), horizon.end()).stream()
                .filter(filter::accepts).toList();

        var lines = new ArrayList<PlanningLine>();
        for (var bucket : expenses.planningTotals(actor.spaceId(), selection)) lines.add(line(bucket));
        for (var forecast : planned)
            lines.add(PlanningLine.forecast(YearMonth.from(forecast.dueDate()), forecast.estimated(),
                    forecast.amount()));
        var byMonth = new LinkedHashMap<YearMonth, List<PlanningLine>>();
        for (var value : horizon.months()) byMonth.put(value, new ArrayList<>());
        for (var line : lines) byMonth.get(line.month()).add(line);
        var months = byMonth.entrySet().stream().map(entry -> new PlanningMonthView(entry.getKey().toString(),
                PlanningTotalsView.of(PlanningTotals.of(entry.getValue())))).toList();

        var monthSelection = selection.withPeriod(month.atDay(1), month.atEndOfMonth());
        var monthForecasts = planned.stream().filter(f -> YearMonth.from(f.dueDate()).equals(month)).toList();
        var perDay = new TreeMap<LocalDate, Long>();
        for (var day : expenses.dailyCounts(actor.spaceId(), monthSelection)) perDay.put(day.date(), day.count());
        var materializedCount = perDay.values().stream().mapToLong(Long::longValue).sum();
        var total = materializedCount + monthForecasts.size();
        var offset = (long) query.page() * query.size();
        var content = new ArrayList<PlanningItemView>();
        if (offset < total) {
            var start = PlanningPageMerge.windowStart(offset, monthForecasts.size());
            var window = start < materializedCount ? expenses.planningEntries(actor.spaceId(), monthSelection, start,
                    PlanningPageMerge.windowLimit(offset, query.size(), monthForecasts.size())) : List.<PlanningExpense>of();
            for (var slot : PlanningPageMerge.page(offset, query.size(), start,
                    window.stream().map(PlanningExpense::referenceDate).toList(),
                    monthForecasts.stream().map(PlannedForecast::dueDate).toList(), perDay))
                content.add(slot.forecast() ? item(monthForecasts.get(slot.index()))
                        : item(window.get(slot.index()), today));
        }
        var totalPages = (int) ((total + query.size() - 1) / query.size());
        return new PlanningView(horizon.start().toString(), horizon.end().toString(), horizon.start().atDay(1),
                horizon.end().atEndOfMonth(), ExpenseDateBasis.DUE_DATE.name(), today, actor.timeZone(),
                PlanningTotalsView.of(PlanningTotals.of(lines)), months, month.toString(),
                PlanningTotalsView.of(PlanningTotals.of(byMonth.get(month))), content, query.page(), query.size(),
                total, totalPages);
    }

    private static PlanningLine line(PlanningExpenseBucket bucket) {
        var situation = switch (bucket.status()) {
            case PENDING -> Situation.PENDING;
            case PAID -> Situation.PAID;
            case CANCELLED -> throw new IllegalStateException("Lançamento cancelado não compõe o planejamento.");
        };
        return new PlanningLine(bucket.month(), PlanningOrigin.valueOf(bucket.origin()), false, situation,
                !bucket.chargeConfirmed(), bucket.count(), bucket.chargeTotal(),
                situation == Situation.PAID ? bucket.paidTotal() : java.math.BigDecimal.ZERO);
    }

    private static PlanningItemView item(PlanningExpense expense, LocalDate today) {
        var paid = expense.status() == ExpenseStatus.PAID;
        return new PlanningItemView("EXPENSE", expense.id(), null, expense.origin(), expense.installment(),
                expense.description(), expense.referenceDate(), expense.dueDate(),
                Money.of(expense.chargeAmount()).toPlainString(), !expense.chargeConfirmed(), expense.status().name(),
                !paid && expense.dueDate() != null && expense.dueDate().isBefore(today),
                paid ? Money.of(expense.paidAmount()).toPlainString() : null, expense.paymentDate(),
                expense.categoryName(), expense.responsibleDisplayName());
    }

    private static PlanningItemView item(PlannedForecast forecast) {
        return new PlanningItemView("FORECAST", null, forecast.recurrenceId(), PlanningOrigin.RECURRENCE.name(), null,
                forecast.description(), forecast.dueDate(), forecast.dueDate(),
                Money.of(forecast.amount()).toPlainString(), forecast.estimated(), "FORECAST", false, null, null,
                forecast.categoryName(), forecast.responsibleDisplayName());
    }

    private static String label(YearMonth month) {
        return String.format("%02d/%d", month.getMonthValue(), month.getYear());
    }
}
