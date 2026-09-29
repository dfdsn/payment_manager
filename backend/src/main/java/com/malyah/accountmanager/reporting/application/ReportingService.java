package com.malyah.accountmanager.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExpenseTotalsBucket;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.reporting.domain.DueIndicators;
import com.malyah.accountmanager.reporting.domain.PaymentIndicators;
import com.malyah.accountmanager.reporting.domain.Situation;
import com.malyah.accountmanager.reporting.domain.TotalsBucket;

/**
 * E06 reports. The space and the business date always come from the authenticated member's active membership; the
 * sums are delegated to the expenses module over the whole selection, with the same filter contract as the list.
 */
public final class ReportingService implements ReportingUseCase {
    private final ExpenseReportQueries expenses;
    private final AuthenticatedUserContextQuery contexts;
    private final Clock clock;

    public ReportingService(ExpenseReportQueries expenses, AuthenticatedUserContextQuery contexts, Clock clock) {
        this.expenses = Objects.requireNonNull(expenses);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public DueDashboardView dueDashboard(String actorEmail, ReportFilters filters) {
        Objects.requireNonNull(filters, "filters");
        var status = filters.status() == null ? ExpenseStatusFilter.ACTIVE : filters.status();
        var requestedMonth = parseMonth(filters.month());
        var actor = contexts.findByEmail(actorEmail);
        var today = LocalDate.now(clock.withZone(ZoneId.of(actor.timeZone())));
        var month = requestedMonth == null ? YearMonth.from(today) : requestedMonth;
        var start = month.atDay(1);
        var end = month.atEndOfMonth();
        var selection = new ExpenseSelection(trim(filters.search()), start, end, ExpenseDateBasis.DUE_DATE,
                filters.categoryId(), filters.withoutCategory(), filters.responsibleUserId(),
                filters.withoutResponsible(), filters.payerUserId(), status, today);
        selection.validate();
        var indicators = DueIndicators.from(buckets(expenses.totals(actor.spaceId(), selection)));
        var previousStatus = previousPendingStatus(status);
        var previous = previousStatus == null ? DueIndicators.from(List.of())
                : DueIndicators.from(buckets(expenses.totals(actor.spaceId(),
                        selection.withPeriod(null, start.minusDays(1)).withStatus(previousStatus))));
        return new DueDashboardView(month.toString(), start, end, ExpenseDateBasis.DUE_DATE.name(), today,
                actor.timeZone(), DueIndicatorsView.of(indicators), new PreviousPendingView(start,
                        previous.pendingCount(), previous.pendingTotal().toPlainString(),
                        previous.pendingEstimated().toPlainString(), previous.overdueCount(),
                        previous.overdueTotal().toPlainString()));
    }

    /**
     * H06.2: active payments whose effective payment date falls in the month. The same selection feeds the totals
     * (summed by the database over the whole selection) and the page, so both always describe one population.
     */
    @Override
    public PaymentReportView payments(String actorEmail, PaymentReportQuery query) {
        Objects.requireNonNull(query, "query");
        var filters = Objects.requireNonNull(query.filters(), "filters");
        if (filters.status() != null && filters.status() != ExpenseStatusFilter.PAID)
            throw new ReportQueryValidationException("status",
                    "A visão de pagamentos mostra somente quitações ativas.");
        if (query.page() < 0)
            throw new ReportQueryValidationException("page", "A página não pode ser negativa.");
        if (query.size() < 1 || query.size() > PaymentReportQuery.MAXIMUM_SIZE)
            throw new ReportQueryValidationException("size", "O tamanho da página deve ficar entre 1 e 100.");
        var sort = query.sort() == null ? PaymentSort.PAYMENT_DATE : query.sort();
        var direction = query.direction() == null ? SortDirection.ASC : query.direction();
        var requestedMonth = parseMonth(filters.month());
        var actor = contexts.findByEmail(actorEmail);
        var today = LocalDate.now(clock.withZone(ZoneId.of(actor.timeZone())));
        var month = requestedMonth == null ? YearMonth.from(today) : requestedMonth;
        var start = month.atDay(1);
        var end = month.atEndOfMonth();
        var selection = new ExpenseSelection(trim(filters.search()), start, end, ExpenseDateBasis.PAYMENT_DATE,
                filters.categoryId(), filters.withoutCategory(), filters.responsibleUserId(),
                filters.withoutResponsible(), filters.payerUserId(), ExpenseStatusFilter.PAID, today);
        selection.validate();
        var indicators = PaymentIndicators.from(buckets(expenses.totals(actor.spaceId(), selection)));
        var page = expenses.payments(actor.spaceId(), selection, query.page(), query.size(), sort, direction);
        var totalPages = (int) ((page.totalElements() + query.size() - 1) / query.size());
        return new PaymentReportView(month.toString(), start, end, ExpenseDateBasis.PAYMENT_DATE.name(),
                actor.timeZone(), PaymentIndicatorsView.of(indicators),
                page.content().stream().map(PaymentRowView::of).toList(), query.page(), query.size(),
                page.totalElements(), totalPages, sort.name(), direction.name());
    }

    /**
     * Previous pending entries answer the same situation filter: only pending ones exist before the month, overdue
     * narrows them and a filter for paid or cancelled entries has none.
     */
    static ExpenseStatusFilter previousPendingStatus(ExpenseStatusFilter status) {
        return switch (status) {
            case ACTIVE, PENDING, ALL -> ExpenseStatusFilter.PENDING;
            case OVERDUE -> ExpenseStatusFilter.OVERDUE;
            case PAID, CANCELLED -> null;
        };
    }

    static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return null;
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException error) {
            throw new ReportQueryValidationException("month", "Informe o mês no formato AAAA-MM.");
        }
    }

    private static List<TotalsBucket> buckets(List<ExpenseTotalsBucket> rows) {
        return rows.stream().map(row -> new TotalsBucket(situation(row.status()), !row.chargeConfirmed(),
                row.overdue(), row.count(), row.chargeTotal(), row.paidTotal(), row.increaseTotal(),
                row.discountTotal())).toList();
    }

    private static Situation situation(ExpenseStatus status) {
        return switch (status) {
            case PENDING -> Situation.PENDING;
            case PAID -> Situation.PAID;
            case CANCELLED -> throw new IllegalStateException("Lançamento cancelado não compõe totais.");
        };
    }

    private static String trim(String search) {
        return search == null ? null : search.trim();
    }
}
