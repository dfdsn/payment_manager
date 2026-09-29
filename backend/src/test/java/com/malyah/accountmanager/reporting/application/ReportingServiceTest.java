package com.malyah.accountmanager.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExpenseTotalsBucket;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.domain.SpaceRole;

class ReportingServiceTest {
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    // 02:30 UTC on 1 October is still 30 September in São Paulo (UTC-3).
    private static final Instant NEAR_MIDNIGHT = Instant.parse("2026-10-01T02:30:00Z");

    private final List<Call> calls = new ArrayList<>();
    private List<ExpenseTotalsBucket> monthRows = List.of();
    private List<ExpenseTotalsBucket> previousRows = List.of();

    private record Call(UUID spaceId, ExpenseSelection selection) { }

    private final ExpenseReportQueries queries = (spaceId, selection) -> {
        calls.add(new Call(spaceId, selection));
        return selection.dateFrom() == null ? previousRows : monthRows;
    };

    private ReportingService service(Instant now) {
        return new ReportingService(queries, email -> {
            if (!email.equals("ana@example.com")) throw new AuthenticatedUserContextNotFoundException();
            return new AuthenticatedUserContext(USER, "Ana", email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR",
                    "America/Sao_Paulo");
        }, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static ExpenseTotalsBucket row(ExpenseStatus status, boolean confirmed, boolean overdue, long count,
            String charge, String paid, String increase, String discount) {
        return new ExpenseTotalsBucket(status, confirmed, overdue, count, new BigDecimal(charge), new BigDecimal(paid),
                new BigDecimal(increase), new BigDecimal(discount));
    }

    @Test
    void defaultsToTheCurrentMonthInTheSpaceTimeZoneAndSeparatesPreviousPendingEntries() {
        monthRows = List.of(row(ExpenseStatus.PENDING, true, false, 1, "100.00", "0", "0", "0"),
                row(ExpenseStatus.PAID, true, false, 1, "150.00", "155.00", "5.00", "0"));
        previousRows = List.of(row(ExpenseStatus.PENDING, true, true, 2, "800.00", "0", "0", "0"),
                row(ExpenseStatus.PENDING, false, true, 1, "180.00", "0", "0", "0"));

        var view = service(NEAR_MIDNIGHT).dueDashboard("ana@example.com", ReportFilters.currentMonth());

        assertThat(view.month()).isEqualTo("2026-09");
        assertThat(view.periodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(view.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(view.today()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(view.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(view.dateBasis()).isEqualTo("DUE_DATE");
        assertThat(view.indicators().plannedTotal()).isEqualTo("250.00");
        assertThat(view.indicators().pendingTotal()).isEqualTo("100.00");
        assertThat(view.indicators().paidTotal()).isEqualTo("155.00");
        assertThat(view.indicators().adjustmentIncrease()).isEqualTo("5.00");
        assertThat(view.indicators().adjustmentNet()).isEqualTo("5.00");
        assertThat(view.previousPending().dueBefore()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(view.previousPending().count()).isEqualTo(3);
        assertThat(view.previousPending().total()).isEqualTo("980.00");
        assertThat(view.previousPending().estimated()).isEqualTo("180.00");
        assertThat(view.previousPending().overdueCount()).isEqualTo(3);
        assertThat(view.previousPending().overdueTotal()).isEqualTo("980.00");

        assertThat(calls).hasSize(2).allSatisfy(call -> assertThat(call.spaceId()).isEqualTo(SPACE));
        var month = calls.get(0).selection();
        assertThat(month.dateBasis()).isEqualTo(ExpenseDateBasis.DUE_DATE);
        assertThat(month.dateFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(month.dateTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(month.status()).isEqualTo(ExpenseStatusFilter.ACTIVE);
        assertThat(month.today()).isEqualTo(LocalDate.of(2026, 9, 30));
        var previous = calls.get(1).selection();
        assertThat(previous.dateFrom()).isNull();
        assertThat(previous.dateTo()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(previous.status()).isEqualTo(ExpenseStatusFilter.PENDING);
        assertThat(previous.dateBasis()).isEqualTo(ExpenseDateBasis.DUE_DATE);
    }

    @Test
    void appliesEveryFilterToTheMonthAndToThePreviousPendingEntries() {
        var payer = UUID.randomUUID();
        var responsible = UUID.randomUUID();
        service(NEAR_MIDNIGHT).dueDashboard("ana@example.com", new ReportFilters(" 2026-11 ", "  luz ", CATEGORY,
                false, responsible, false, payer, ExpenseStatusFilter.OVERDUE));

        var month = calls.get(0).selection();
        assertThat(month.dateFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(month.dateTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(month.search()).isEqualTo("luz");
        assertThat(month.categoryId()).isEqualTo(CATEGORY);
        assertThat(month.responsibleUserId()).isEqualTo(responsible);
        assertThat(month.payerUserId()).isEqualTo(payer);
        assertThat(month.status()).isEqualTo(ExpenseStatusFilter.OVERDUE);
        var previous = calls.get(1).selection();
        assertThat(previous.search()).isEqualTo("luz");
        assertThat(previous.categoryId()).isEqualTo(CATEGORY);
        assertThat(previous.responsibleUserId()).isEqualTo(responsible);
        assertThat(previous.payerUserId()).isEqualTo(payer);
        assertThat(previous.status()).isEqualTo(ExpenseStatusFilter.OVERDUE);
        assertThat(previous.dateTo()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void filtersForPaidOrCancelledEntriesHaveNoPreviousPendingEntries() {
        for (var status : List.of(ExpenseStatusFilter.PAID, ExpenseStatusFilter.CANCELLED)) {
            calls.clear();
            previousRows = List.of(row(ExpenseStatus.PENDING, true, true, 1, "10.00", "0", "0", "0"));
            var view = service(NEAR_MIDNIGHT).dueDashboard("ana@example.com",
                    new ReportFilters("2026-09", null, null, false, null, false, null, status));
            assertThat(calls).hasSize(1);
            assertThat(view.previousPending().count()).isZero();
            assertThat(view.previousPending().total()).isEqualTo("0.00");
        }
        assertThat(ReportingService.previousPendingStatus(ExpenseStatusFilter.ALL)).isEqualTo(ExpenseStatusFilter.PENDING);
        assertThat(ReportingService.previousPendingStatus(ExpenseStatusFilter.PENDING)).isEqualTo(ExpenseStatusFilter.PENDING);
        assertThat(ReportingService.previousPendingStatus(ExpenseStatusFilter.ACTIVE)).isEqualTo(ExpenseStatusFilter.PENDING);
    }

    @Test
    void rejectsInvalidMonthsAndFilterCombinationsBeforeQuerying() {
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).dueDashboard("ana@example.com",
                new ReportFilters("2026-13", null, null, false, null, false, null, null)))
                .isInstanceOf(ReportQueryValidationException.class)
                .satisfies(error -> assertThat(((ReportQueryValidationException) error).field()).isEqualTo("month"));
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).dueDashboard("ana@example.com",
                new ReportFilters("09/2026", null, null, false, null, false, null, null)))
                .isInstanceOf(ReportQueryValidationException.class);
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).dueDashboard("ana@example.com",
                new ReportFilters(null, null, CATEGORY, true, null, false, null, null)))
                .isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).dueDashboard("ana@example.com",
                new ReportFilters(null, "x".repeat(201), null, false, null, false, null, null)))
                .isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).dueDashboard("ana@example.com", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(calls).isEmpty();
        assertThat(ReportingService.parseMonth("  ")).isNull();
    }

    @Test
    void aUserWithoutActiveMembershipGetsNothing() {
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).dueDashboard("other@example.com", ReportFilters.currentMonth()))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void aCancelledRowReachingTheTotalsIsAProgrammingError() {
        monthRows = List.of(row(ExpenseStatus.CANCELLED, true, false, 1, "10.00", "0", "0", "0"));
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).dueDashboard("ana@example.com", ReportFilters.currentMonth()))
                .isInstanceOf(IllegalStateException.class);
    }
}
