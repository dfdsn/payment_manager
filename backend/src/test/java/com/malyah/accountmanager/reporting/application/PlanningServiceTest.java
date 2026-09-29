package com.malyah.accountmanager.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseDayCount;
import com.malyah.accountmanager.expenses.application.ExpensePlanningQueries;
import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.PlanningExpense;
import com.malyah.accountmanager.expenses.application.PlanningExpenseBucket;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.recurrences.application.PlannedForecast;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;

class PlanningServiceTest {
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID RECURRENCE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant MID_OCTOBER = Instant.parse("2026-10-15T15:00:00Z");
    // 02:30 UTC on 1 November is still 31 October in São Paulo.
    private static final Instant NEAR_MIDNIGHT = Instant.parse("2026-11-01T02:30:00Z");

    private final List<ExpenseSelection> selections = new ArrayList<>();
    private final List<String> windows = new ArrayList<>();
    private final List<String> forecastCalls = new ArrayList<>();
    private List<PlanningExpenseBucket> buckets = List.of();
    private List<ExpenseDayCount> days = List.of();
    private List<PlanningExpense> entries = List.of();
    private List<PlannedForecast> forecasts = List.of();

    private final ExpensePlanningQueries expenses = new ExpensePlanningQueries() {
        @Override
        public List<PlanningExpenseBucket> planningTotals(UUID spaceId, ExpenseSelection selection) {
            selections.add(selection);
            return buckets;
        }

        @Override
        public List<ExpenseDayCount> dailyCounts(UUID spaceId, ExpenseSelection selection) {
            selections.add(selection);
            return days;
        }

        @Override
        public List<PlanningExpense> planningEntries(UUID spaceId, ExpenseSelection selection, long offset, int limit) {
            windows.add(offset + "+" + limit);
            var from = (int) Math.min(offset, entries.size());
            return entries.subList(from, (int) Math.min(entries.size(), offset + limit));
        }
    };

    private final RecurrenceForecastQueries forecastQueries = (spaceId, from, to) -> {
        forecastCalls.add(spaceId + ":" + from + ".." + to);
        return forecasts;
    };

    private PlanningService service(Instant now) {
        return new PlanningService(expenses, forecastQueries, email -> {
            if (!email.equals("ana@example.com")) throw new AuthenticatedUserContextNotFoundException();
            return new AuthenticatedUserContext(USER, "Ana", email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR",
                    "America/Sao_Paulo");
        }, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PlanningQuery query(String month, int page, int size) {
        return new PlanningQuery(month, null, null, false, null, false, page, size);
    }

    private static PlannedForecast forecast(LocalDate due, String description, String amount, boolean estimated,
            UUID category, UUID responsible) {
        return new PlannedForecast(RECURRENCE, due, description, new BigDecimal(amount), estimated, category,
                category == null ? null : "Casa", responsible, responsible == null ? null : "Bia");
    }

    private static PlanningExpense expense(LocalDate date, String description, ExpenseStatus status, String charge,
            boolean confirmed, String paid, LocalDate paymentDate, LocalDate due) {
        return new PlanningExpense(UUID.nameUUIDFromBytes(description.getBytes()), "ONE_OFF", null, description, date,
                due, new BigDecimal(charge), confirmed, status, paid == null ? null : new BigDecimal(paid),
                paymentDate, null, null);
    }

    @Test
    void horizonFollowsTheSpaceCalendarAndTheSelectionIsTheSharedActivePredicate() {
        var view = service(NEAR_MIDNIGHT).planning("ana@example.com", PlanningQuery.currentMonth());

        assertThat(view.horizonStart()).isEqualTo("2026-10");
        assertThat(view.horizonEnd()).isEqualTo("2027-10");
        assertThat(view.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(view.periodEnd()).isEqualTo(LocalDate.of(2027, 10, 31));
        assertThat(view.today()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(view.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(view.dateBasis()).isEqualTo("DUE_DATE");
        assertThat(view.month()).isEqualTo("2026-10");
        assertThat(view.months()).extracting(PlanningMonthView::month).hasSize(13).startsWith("2026-10")
                .endsWith("2027-10");
        assertThat(view.totals().plannedTotal()).isEqualTo("0.00");
        assertThat(view.content()).isEmpty();
        assertThat(view.totalElements()).isZero();
        assertThat(view.totalPages()).isZero();
        assertThat(windows).as("an empty month never reads a window").isEmpty();
        assertThat(forecastCalls).containsExactly(SPACE + ":2026-10..2027-10");
        var horizon = selections.getFirst();
        assertThat(horizon.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(horizon.dateTo()).isEqualTo(LocalDate.of(2027, 10, 31));
        assertThat(horizon.dateBasis()).isEqualTo(ExpenseDateBasis.DUE_DATE);
        assertThat(horizon.status()).isEqualTo(ExpenseStatusFilter.ACTIVE);
        assertThat(horizon.payerUserId()).isNull();
        assertThat(horizon.today()).isEqualTo(LocalDate.of(2026, 10, 31));
        var month = selections.get(1);
        assertThat(month.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(month.dateTo()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void combinesExpenseBucketsAndFilteredForecastsPerMonthAndForTheHorizon() {
        buckets = List.of(
                new PlanningExpenseBucket(YearMonth.of(2027, 1), "ONE_OFF", ExpenseStatus.PAID, true, 1,
                        new BigDecimal("900.00"), new BigDecimal("880.00")),
                new PlanningExpenseBucket(YearMonth.of(2027, 1), "INSTALLMENT", ExpenseStatus.PENDING, true, 1,
                        new BigDecimal("333.33"), BigDecimal.ZERO),
                new PlanningExpenseBucket(YearMonth.of(2026, 12), "RECURRENCE", ExpenseStatus.PENDING, false, 1,
                        new BigDecimal("210.00"), BigDecimal.ZERO));
        forecasts = List.of(forecast(LocalDate.of(2027, 1, 5), "Internet", "100.00", false, CATEGORY, null),
                forecast(LocalDate.of(2027, 1, 20), "Luz", "210.00", true, null, PERSON));

        var view = service(MID_OCTOBER).planning("ana@example.com", query("2027-01", 0, 20));

        assertThat(view.totals().plannedTotal()).isEqualTo("1753.33");
        assertThat(view.totals().estimatedTotal()).isEqualTo("420.00");
        assertThat(view.totals().forecastCount()).isEqualTo(2);
        assertThat(view.totals().paidTotal()).isEqualTo("880.00");
        var january = view.months().stream().filter(m -> m.month().equals("2027-01")).findFirst().orElseThrow();
        assertThat(january.totals().plannedTotal()).isEqualTo("1543.33");
        assertThat(january.totals().oneOffTotal()).isEqualTo("900.00");
        assertThat(view.monthTotals()).isEqualTo(january.totals());
        var december = view.months().stream().filter(m -> m.month().equals("2026-12")).findFirst().orElseThrow();
        assertThat(december.totals().estimatedTotal()).isEqualTo("210.00");
    }

    @Test
    void forecastFiltersMatchTheExpensePredicate() {
        forecasts = List.of(forecast(LocalDate.of(2026, 11, 5), "Internet Fibra", "100.00", false, CATEGORY, null),
                forecast(LocalDate.of(2026, 11, 20), "Luz", "210.00", true, null, PERSON));
        var ana = service(MID_OCTOBER);

        assertThat(ana.planning("ana@example.com", new PlanningQuery(null, " fibra ", null, false, null, false, 0, 20))
                .totals().plannedTotal()).isEqualTo("100.00");
        assertThat(selections.getFirst().search()).isEqualTo("fibra");
        assertThat(ana.planning("ana@example.com", new PlanningQuery(null, "", null, false, null, false, 0, 20))
                .totals().count()).isEqualTo(2);
        assertThat(ana.planning("ana@example.com", new PlanningQuery(null, null, CATEGORY, false, null, false, 0, 20))
                .totals().plannedTotal()).isEqualTo("100.00");
        assertThat(ana.planning("ana@example.com", new PlanningQuery(null, null, null, true, null, false, 0, 20))
                .totals().plannedTotal()).isEqualTo("210.00");
        assertThat(ana.planning("ana@example.com", new PlanningQuery(null, null, null, false, PERSON, false, 0, 20))
                .totals().plannedTotal()).isEqualTo("210.00");
        assertThat(ana.planning("ana@example.com", new PlanningQuery(null, null, null, false, null, true, 0, 20))
                .totals().plannedTotal()).isEqualTo("100.00");
        assertThat(ana.planning("ana@example.com", new PlanningQuery(null, null, UUID.randomUUID(), false, null, false,
                0, 20)).totals().count()).isZero();
    }

    @Test
    void pagesMergeExpensesAndForecastsAndMapEveryField() {
        var purchase = UUID.randomUUID();
        days = List.of(new ExpenseDayCount(LocalDate.of(2027, 1, 20), 1), new ExpenseDayCount(LocalDate.of(2027, 1, 31), 1));
        entries = List.of(
                expense(LocalDate.of(2027, 1, 20), "IPVA", ExpenseStatus.PAID, "900", true, "880.5",
                        LocalDate.of(2026, 10, 14), LocalDate.of(2027, 1, 20)),
                new PlanningExpense(UUID.randomUUID(), "INSTALLMENT", new InstallmentLink(purchase, 2, 3), "Sofá",
                        LocalDate.of(2027, 1, 31), LocalDate.of(2027, 1, 31), new BigDecimal("333.33"), true,
                        ExpenseStatus.PENDING, null, null, "Casa", "Bia"));
        forecasts = List.of(forecast(LocalDate.of(2027, 1, 5), "Internet", "100", false, CATEGORY, null),
                forecast(LocalDate.of(2027, 1, 12), "Streaming", "40.00", false, null, null),
                forecast(LocalDate.of(2027, 1, 20), "Luz", "210.00", true, null, PERSON));
        var ana = service(MID_OCTOBER);

        var second = ana.planning("ana@example.com", query("2027-01", 1, 2));
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(second.totalPages()).isEqualTo(3);
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
        assertThat(windows).containsExactly("0+4");
        assertThat(second.content()).extracting(PlanningItemView::description).containsExactly("IPVA", "Luz");
        var paid = second.content().getFirst();
        assertThat(paid.kind()).isEqualTo("EXPENSE");
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.amount()).isEqualTo("900.00");
        assertThat(paid.paidAmount()).isEqualTo("880.50");
        assertThat(paid.paymentDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(paid.overdue()).isFalse();
        assertThat(paid.estimated()).isFalse();
        assertThat(paid.origin()).isEqualTo("ONE_OFF");
        var light = second.content().get(1);
        assertThat(light.kind()).isEqualTo("FORECAST");
        assertThat(light.status()).isEqualTo("FORECAST");
        assertThat(light.recurrenceId()).isEqualTo(RECURRENCE);
        assertThat(light.expenseId()).isNull();
        assertThat(light.origin()).isEqualTo("RECURRENCE");
        assertThat(light.estimated()).isTrue();
        assertThat(light.dueDate()).isEqualTo(LocalDate.of(2027, 1, 20));
        assertThat(light.responsibleDisplayName()).isEqualTo("Bia");
        assertThat(light.paidAmount()).isNull();

        var first = ana.planning("ana@example.com", query("2027-01", 0, 2));
        assertThat(first.content()).extracting(PlanningItemView::description).containsExactly("Internet", "Streaming");
        assertThat(first.content().getFirst().amount()).isEqualTo("100.00");
        var last = ana.planning("ana@example.com", query("2027-01", 2, 2));
        assertThat(last.content()).extracting(PlanningItemView::description).containsExactly("Sofá");
        assertThat(last.content().getFirst().installment().number()).isEqualTo(2);
        assertThat(last.content().getFirst().categoryName()).isEqualTo("Casa");
        assertThat(ana.planning("ana@example.com", query("2027-01", 3, 2)).content()).isEmpty();
    }

    @Test
    void overdueIsAPendingExpenseDueBeforeTheLocalToday() {
        days = List.of(new ExpenseDayCount(LocalDate.of(2026, 10, 5), 1), new ExpenseDayCount(LocalDate.of(2026, 10, 15), 1),
                new ExpenseDayCount(LocalDate.of(2026, 10, 14), 1));
        entries = List.of(
                expense(LocalDate.of(2026, 10, 5), "Internet", ExpenseStatus.PENDING, "100", true, null, null,
                        LocalDate.of(2026, 10, 5)),
                expense(LocalDate.of(2026, 10, 14), "Paga", ExpenseStatus.PAID, "50", true, "50",
                        LocalDate.of(2026, 10, 14), null),
                expense(LocalDate.of(2026, 10, 15), "Hoje", ExpenseStatus.PENDING, "20", false, null, null,
                        LocalDate.of(2026, 10, 15)));
        var content = service(MID_OCTOBER).planning("ana@example.com", query(null, 0, 20)).content();
        assertThat(content).extracting(PlanningItemView::overdue).containsExactly(true, false, false);
        assertThat(content.get(1).dueDate()).isNull();
        assertThat(content.get(2).estimated()).isTrue();
    }

    @Test
    void rejectsInvalidQueriesAndMonthsOutsideTheHorizonBeforeReading() {
        var ana = service(MID_OCTOBER);
        assertThatThrownBy(() -> ana.planning("ana@example.com", query(null, -1, 20)))
                .isInstanceOf(ReportQueryValidationException.class).extracting("field").isEqualTo("page");
        assertThatThrownBy(() -> ana.planning("ana@example.com", query(null, 0, 0)))
                .isInstanceOf(ReportQueryValidationException.class).extracting("field").isEqualTo("size");
        assertThatThrownBy(() -> ana.planning("ana@example.com", query(null, 0, 101)))
                .isInstanceOf(ReportQueryValidationException.class).extracting("field").isEqualTo("size");
        assertThat(ana.planning("ana@example.com", query(null, 0, 100)).size()).isEqualTo(100);
        assertThat(ana.planning("ana@example.com", query(null, 0, 1)).size()).isEqualTo(1);
        assertThatThrownBy(() -> ana.planning("ana@example.com", query("2026-13", 0, 20)))
                .isInstanceOf(ReportQueryValidationException.class).extracting("field").isEqualTo("month");
        assertThatThrownBy(() -> ana.planning("ana@example.com", query("2026-09", 0, 20)))
                .isInstanceOf(ReportQueryValidationException.class)
                .hasMessage("Escolha um mês entre 10/2026 e 10/2027, o horizonte do planejamento.");
        assertThatThrownBy(() -> ana.planning("ana@example.com", query("2027-11", 0, 20)))
                .isInstanceOf(ReportQueryValidationException.class);
        assertThat(ana.planning("ana@example.com", query("2027-10", 0, 20)).month()).isEqualTo("2027-10");
        assertThatThrownBy(() -> ana.planning("ana@example.com", new PlanningQuery(null, null, CATEGORY, true, null,
                false, 0, 20))).isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> ana.planning("ana@example.com", new PlanningQuery(null, "x".repeat(201), null,
                false, null, false, 0, 20))).isInstanceOf(ExpenseQueryValidationException.class);
        assertThatThrownBy(() -> ana.planning("bia@example.com", PlanningQuery.currentMonth()))
                .isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> ana.planning("ana@example.com", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void cancelledBucketsCannotReachThePlanning() {
        buckets = List.of(new PlanningExpenseBucket(YearMonth.of(2026, 11), "ONE_OFF", ExpenseStatus.CANCELLED, true,
                1, BigDecimal.TEN, BigDecimal.ZERO));
        assertThatThrownBy(() -> service(MID_OCTOBER).planning("ana@example.com", PlanningQuery.currentMonth()))
                .isInstanceOf(IllegalStateException.class);
    }
}
