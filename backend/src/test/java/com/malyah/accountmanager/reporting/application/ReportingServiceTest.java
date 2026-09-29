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
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.PaymentRecord;
import com.malyah.accountmanager.expenses.application.PaymentRecordPage;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.SortDirection;
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

    private PaymentRecordPage paymentPage = new PaymentRecordPage(List.of(), 0);
    private final List<String> pageCalls = new ArrayList<>();

    private final ExpenseReportQueries queries = new ExpenseReportQueries() {
        @Override
        public List<ExpenseTotalsBucket> totals(UUID spaceId, ExpenseSelection selection) {
            calls.add(new Call(spaceId, selection));
            return selection.dateFrom() == null ? previousRows : monthRows;
        }

        @Override
        public PaymentRecordPage payments(UUID spaceId, ExpenseSelection selection, int page, int size,
                PaymentSort sort, SortDirection direction) {
            calls.add(new Call(spaceId, selection));
            pageCalls.add(page + "/" + size + "/" + sort + "/" + direction);
            return paymentPage;
        }

        @Override
        public List<com.malyah.accountmanager.expenses.application.ReportedExpense> entries(UUID spaceId,
                ExpenseSelection selection) {
            throw new UnsupportedOperationException("Not used by the E06 reports.");
        }
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

    private static PaymentRecord record(String charge, String paid, PaymentRecord.PaymentCorrection correction) {
        return new PaymentRecord(UUID.randomUUID(), "Água", "INSTALLMENT", new InstallmentLink(UUID.randomUUID(), 2, 3),
                LocalDate.of(2026, 9, 25), new BigDecimal(charge), true, new BigDecimal(paid),
                LocalDate.of(2026, 9, 2), USER, "Ana", USER, "Ana", NEAR_MIDNIGHT, true, "Casa", null,
                correction == null ? 0 : 1, correction);
    }

    @Test
    void paymentsSelectActivePaymentsByPaymentDateInTheSpaceMonthAndPageThem() {
        monthRows = List.of(row(ExpenseStatus.PAID, true, false, 3, "450.00", "455.00", "15.00", "10.00"));
        var correction = new PaymentRecord.PaymentCorrection(USER, "Ana", NEAR_MIDNIGHT, List.of("paymentDate"));
        paymentPage = new PaymentRecordPage(List.of(record("150.00", "155.00", correction),
                record("120.00", "110.00", null)), 3);

        var view = service(NEAR_MIDNIGHT).payments("ana@example.com", new PaymentReportQuery(
                new ReportFilters(null, "  água ", CATEGORY, false, null, true, USER, null), 1, 2, null, null));

        assertThat(view.month()).isEqualTo("2026-09");
        assertThat(view.dateBasis()).isEqualTo("PAYMENT_DATE");
        assertThat(view.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(view.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(view.indicators()).isEqualTo(new PaymentIndicatorsView(3, "455.00", "450.00", "15.00", "10.00",
                "5.00"));
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(2);
        assertThat(view.totalElements()).isEqualTo(3);
        assertThat(view.totalPages()).isEqualTo(2);
        assertThat(view.sort()).isEqualTo("PAYMENT_DATE");
        assertThat(view.direction()).isEqualTo("ASC");
        assertThat(pageCalls).containsExactly("1/2/PAYMENT_DATE/ASC");
        assertThat(view.content()).extracting(PaymentRowView::adjustment).containsExactly("5.00", "-10.00");
        var first = view.content().getFirst();
        assertThat(first.chargeAmount()).isEqualTo("150.00");
        assertThat(first.paidAmount()).isEqualTo("155.00");
        assertThat(first.installment().number()).isEqualTo(2);
        assertThat(first.batchPayment()).isTrue();
        assertThat(first.correctionCount()).isEqualTo(1);
        assertThat(first.lastCorrection()).isEqualTo(correction);
        assertThat(first.categoryName()).isEqualTo("Casa");
        // Totals and page receive exactly the same selection.
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).isEqualTo(calls.get(1));
        var selection = calls.getFirst().selection();
        assertThat(calls.getFirst().spaceId()).isEqualTo(SPACE);
        assertThat(selection.dateBasis()).isEqualTo(ExpenseDateBasis.PAYMENT_DATE);
        assertThat(selection.status()).isEqualTo(ExpenseStatusFilter.PAID);
        assertThat(selection.dateFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(selection.dateTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(selection.search()).isEqualTo("água");
        assertThat(selection.categoryId()).isEqualTo(CATEGORY);
        assertThat(selection.withoutResponsible()).isTrue();
        assertThat(selection.payerUserId()).isEqualTo(USER);
        assertThat(selection.today()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void paymentsKeepTheRequestedMonthOrderAndAnExactlyFullLastPage() {
        paymentPage = new PaymentRecordPage(List.of(), 4);
        var view = service(NEAR_MIDNIGHT).payments("ana@example.com", new PaymentReportQuery(
                new ReportFilters("2026-12", null, null, false, null, false, null, ExpenseStatusFilter.PAID), 0, 2,
                PaymentSort.PAID_AMOUNT, SortDirection.DESC));
        assertThat(view.month()).isEqualTo("2026-12");
        assertThat(view.totalPages()).isEqualTo(2);
        assertThat(view.indicators()).isEqualTo(new PaymentIndicatorsView(0, "0.00", "0.00", "0.00", "0.00", "0.00"));
        assertThat(pageCalls).containsExactly("0/2/PAID_AMOUNT/DESC");
        assertThat(service(NEAR_MIDNIGHT).payments("ana@example.com", new PaymentReportQuery(
                ReportFilters.currentMonth(), 0, 100, null, null)).size()).isEqualTo(100);
        assertThat(service(NEAR_MIDNIGHT).payments("ana@example.com", new PaymentReportQuery(
                ReportFilters.currentMonth(), 0, 1, null, null)).totalPages()).isEqualTo(4);
    }

    @Test
    void paymentsRejectInvalidPagesSituationsAndMembersBeforeQuerying() {
        var service = service(NEAR_MIDNIGHT);
        for (var status : List.of(ExpenseStatusFilter.ACTIVE, ExpenseStatusFilter.PENDING,
                ExpenseStatusFilter.CANCELLED)) {
            assertThatThrownBy(() -> service.payments("ana@example.com", new PaymentReportQuery(
                    new ReportFilters(null, null, null, false, null, false, null, status), 0, 20, null, null)))
                    .isInstanceOfSatisfying(ReportQueryValidationException.class,
                            error -> assertThat(error.field()).isEqualTo("status"));
        }
        assertThatThrownBy(() -> service.payments("ana@example.com", new PaymentReportQuery(
                ReportFilters.currentMonth(), -1, 20, null, null))).isInstanceOfSatisfying(
                ReportQueryValidationException.class, error -> assertThat(error.field()).isEqualTo("page"));
        for (var size : List.of(0, 101)) {
            assertThatThrownBy(() -> service.payments("ana@example.com", new PaymentReportQuery(
                    ReportFilters.currentMonth(), 0, size, null, null))).isInstanceOfSatisfying(
                    ReportQueryValidationException.class, error -> assertThat(error.field()).isEqualTo("size"));
        }
        assertThatThrownBy(() -> service.payments("ana@example.com", new PaymentReportQuery(
                new ReportFilters("10/2026", null, null, false, null, false, null, null), 0, 20, null, null)))
                .isInstanceOf(ReportQueryValidationException.class);
        assertThatThrownBy(() -> service.payments("ana@example.com", new PaymentReportQuery(
                new ReportFilters(null, null, CATEGORY, true, null, false, null, null), 0, 20, null, null)))
                .isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> service.payments("other@example.com", new PaymentReportQuery(
                ReportFilters.currentMonth(), 0, 20, null, null)))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void aPendingRowReachingThePaymentTotalsIsAProgrammingError() {
        monthRows = List.of(row(ExpenseStatus.PENDING, true, false, 1, "10.00", "0", "0", "0"));
        assertThatThrownBy(() -> service(NEAR_MIDNIGHT).payments("ana@example.com", new PaymentReportQuery(
                ReportFilters.currentMonth(), 0, 20, null, null))).isInstanceOf(IllegalArgumentException.class);
    }
}
