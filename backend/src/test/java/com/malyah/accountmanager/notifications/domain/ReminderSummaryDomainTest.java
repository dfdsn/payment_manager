package com.malyah.accountmanager.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** H08.2 calendar, window, composition and message, with the dates of the matrix (today = 05/10/2026). */
class ReminderSummaryDomainTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @ParameterizedTest(name = "due {0}: first {1}, second {2}")
    @CsvSource({
            "2026-10-11, false, false",  // -6
            "2026-10-10, true,  false",  // -5
            "2026-10-08, true,  false",  // -3
            "2026-10-07, true,  false",  // -2
            "2026-10-06, true,  true",   // -1
            "2026-10-05, true,  true",   // 0
            "2026-10-04, true,  false",  // +1 overdue
            "2026-08-01, true,  false"}) // long overdue, every day while pending
    void calendarOfPrd102(LocalDate due, boolean first, boolean second) {
        assertThat(ReminderCalendar.eligible(ReminderSlot.FIRST, due, TODAY)).isEqualTo(first);
        assertThat(ReminderCalendar.eligible(ReminderSlot.SECOND, due, TODAY)).isEqualTo(second);
    }

    @Test
    void lastDueDateOfEachSlot() {
        assertThat(ReminderCalendar.lastDueDate(ReminderSlot.FIRST, TODAY)).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(ReminderCalendar.lastDueDate(ReminderSlot.SECOND, TODAY)).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThatThrownBy(() -> ReminderCalendar.eligible(null, TODAY, TODAY)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void windowStartsAtTheSlotAndLastsOneHourInTheSpaceZone() {
        var first = ReminderWindow.of(ReminderSchedule.DEFAULT, SAO_PAULO, TODAY, ReminderSlot.FIRST);
        assertThat(first.scheduledAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(first.deadline()).isEqualTo(Instant.parse("2026-10-05T13:00:00Z"));
        assertThat(first.started(Instant.parse("2026-10-05T11:59:59Z"))).isFalse();
        assertThat(first.open(Instant.parse("2026-10-05T11:59:59Z"))).isFalse();
        assertThat(first.open(Instant.parse("2026-10-05T12:00:00Z"))).isTrue();
        assertThat(first.open(Instant.parse("2026-10-05T12:59:59Z"))).isTrue();
        assertThat(first.open(Instant.parse("2026-10-05T13:00:00Z"))).isFalse();
        assertThat(first.started(Instant.parse("2026-10-05T13:00:00Z"))).isTrue();
        var second = ReminderWindow.of(ReminderSchedule.DEFAULT, SAO_PAULO, TODAY, ReminderSlot.SECOND);
        assertThat(second.scheduledAt()).isEqualTo(Instant.parse("2026-10-05T21:00:00Z"));
        assertThat(second.deadline()).isEqualTo(Instant.parse("2026-10-05T22:00:00Z"));
        assertThat(second.date()).isEqualTo(TODAY);
        assertThat(second.slot()).isEqualTo(ReminderSlot.SECOND);
    }

    @Test
    void windowEndsEarlierWhenTheNextSlotComesFirst() {
        var close = new ReminderSchedule(LocalTime.of(9, 0), LocalTime.of(9, 30));
        assertThat(ReminderWindow.of(close, SAO_PAULO, TODAY, ReminderSlot.FIRST).deadline())
                .isEqualTo(Instant.parse("2026-10-05T12:30:00Z"));
        var late = new ReminderSchedule(LocalTime.of(0, 10), LocalTime.of(23, 50));
        // The second slot at 23:50 would last until 00:50; the next day's first slot at 00:10 ends it.
        assertThat(ReminderWindow.of(late, SAO_PAULO, TODAY, ReminderSlot.SECOND).deadline())
                .isEqualTo(Instant.parse("2026-10-06T03:10:00Z"));
        var exact = new ReminderSchedule(LocalTime.of(9, 0), LocalTime.of(10, 0));
        assertThat(ReminderWindow.of(exact, SAO_PAULO, TODAY, ReminderSlot.FIRST).deadline())
                .isEqualTo(Instant.parse("2026-10-05T13:00:00Z"));
    }

    @Test
    void noEligibleBillMeansNoSummary() {
        assertThat(ReminderSummary.compose(TODAY, ReminderSlot.FIRST, List.of())).isEmpty();
        assertThat(ReminderSummary.compose(TODAY, ReminderSlot.SECOND,
                List.of(expense("Longe", "10.00", "2026-10-07", false)))).isEmpty();
    }

    @Test
    void composesTheMatrixCalendarForBothSlots() {
        var bills = List.of(expense("Seis", "6.00", "2026-10-11", false), expense("Cinco", "5.00", "2026-10-10", false),
                expense("Dois", "2.00", "2026-10-07", false), expense("Um", "1.00", "2026-10-06", false),
                expense("Hoje", "10.00", "2026-10-05", false), expense("Ontem", "20.00", "2026-10-04", false));
        var first = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, bills).orElseThrow();
        assertThat(first.items()).extracting(ReminderItem::description).containsExactly("Ontem", "Hoje", "Um", "Dois", "Cinco");
        assertThat(first.total()).isEqualByComparingTo("38.00");
        assertThat(first.overdueCount()).isEqualTo(1);
        assertThat(first.remaining()).isZero();
        assertThat(first.date()).isEqualTo(TODAY);
        assertThat(first.slot()).isEqualTo(ReminderSlot.FIRST);
        var second = ReminderSummary.compose(TODAY, ReminderSlot.SECOND, bills).orElseThrow();
        assertThat(second.items()).extracting(ReminderItem::description).containsExactly("Hoje", "Um");
        assertThat(second.total()).isEqualByComparingTo("11.00");
        assertThat(second.overdueCount()).isZero();
    }

    @Test
    void countsAndTotalsCoverHiddenBillsExactly() {
        var bills = new ArrayList<ReminderItem>();
        var amounts = List.of("0.10", "0.20", "1234.56", "99.99", "0.01", "10.00", "5.05");
        for (int i = 0; i < amounts.size(); i++)
            bills.add(expense("Conta " + i, amounts.get(i), "2026-10-0" + (5 + i % 3), i == 3));
        var summary = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, bills).orElseThrow();
        assertThat(summary.count()).isEqualTo(7);
        assertThat(summary.details()).hasSize(5);
        assertThat(summary.remaining()).isEqualTo(2);
        assertThat(summary.total()).isEqualTo(new BigDecimal("1349.91"));
        assertThat(summary.estimatedCount()).isEqualTo(1);
        assertThat(summary.estimatedTotal()).isEqualTo(new BigDecimal("99.99"));
    }

    @Test
    void oneAndFiveBills() {
        var one = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, List.of(expense("Só", "1.00", "2026-10-05", false)))
                .orElseThrow();
        assertThat(one.details()).hasSize(1);
        assertThat(one.remaining()).isZero();
        var five = new ArrayList<ReminderItem>();
        for (int i = 0; i < 5; i++) five.add(expense("C" + i, "1.00", "2026-10-06", false));
        var summary = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, five).orElseThrow();
        assertThat(summary.details()).hasSize(5);
        assertThat(summary.remaining()).isZero();
    }

    @Test
    void overdueFirstThenNearestThenDescriptionThenIdentity() {
        var sameA = ReminderItem.expense(UUID.fromString("00000000-0000-0000-0000-000000000002"), "ONE_OFF", "Igual",
                new BigDecimal("1.00"), LocalDate.parse("2026-10-07"), false, null, null);
        var sameB = ReminderItem.expense(UUID.fromString("00000000-0000-0000-0000-000000000001"), "ONE_OFF", "igual",
                new BigDecimal("1.00"), LocalDate.parse("2026-10-07"), false, null, null);
        var bills = List.of(expense("gama", "1.00", "2026-10-07", false), expense("beta", "1.00", "2026-10-07", false),
                expense("Alfa", "1.00", "2026-10-07", false), expense("Recente", "1.00", "2026-10-03", false),
                expense("Antiga", "1.00", "2026-10-01", false), sameA, sameB);
        var order = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, bills).orElseThrow().items();
        assertThat(order).extracting(ReminderItem::description)
                .containsExactly("Antiga", "Recente", "Alfa", "beta", "gama", "igual", "Igual");
        assertThat(ReminderSummary.compose(TODAY, ReminderSlot.FIRST, bills.reversed()).orElseThrow().items())
                .isEqualTo(order);
    }

    @Test
    void theSameBillIsNeverCountedTwice() {
        var bill = expense("Luz", "90.00", "2026-10-06", true);
        var forecast = ReminderItem.forecast(UUID.randomUUID(), LocalDate.parse("2026-10-07"), "Água", new BigDecimal("50.00"), false);
        var summary = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, List.of(bill, bill, forecast, forecast)).orElseThrow();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.total()).isEqualByComparingTo("140.00");
    }

    @Test
    void itemIdentityAndLabels() {
        var id = UUID.randomUUID();
        var parcel = ReminderItem.expense(id, "INSTALLMENT", "Geladeira", new BigDecimal("300.00"),
                LocalDate.parse("2026-10-07"), false, 3, 10);
        assertThat(parcel.key()).isEqualTo("E:" + id);
        assertThat(parcel.label()).isEqualTo("Geladeira (3/10)");
        assertThat(parcel.forecast()).isFalse();
        var recurrence = UUID.randomUUID();
        var forecast = ReminderItem.forecast(recurrence, LocalDate.parse("2026-10-03"), "Internet", new BigDecimal("120.00"), true);
        assertThat(forecast.key()).isEqualTo("F:" + recurrence + ":2026-10-03");
        assertThat(forecast.label()).isEqualTo("Internet");
        assertThat(forecast.forecast()).isTrue();
        assertThat(forecast.origin()).isEqualTo(ReminderItem.FORECAST_ORIGIN);
        assertThat(forecast.dueDate()).isEqualTo(forecast.scheduledDueDate());
        assertThat(forecast.overdue(LocalDate.parse("2026-10-03"))).isFalse();
        assertThat(forecast.overdue(LocalDate.parse("2026-10-04"))).isTrue();
        assertThatThrownBy(() -> new ReminderItem(id, recurrence, null, "x", BigDecimal.ONE, TODAY, false, "ONE_OFF", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReminderItem(null, recurrence, null, "x", BigDecimal.ONE, TODAY, false, "ONE_OFF", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReminderItem(null, null, TODAY, "x", BigDecimal.ONE, TODAY, false, "ONE_OFF", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReminderItem(id, null, TODAY, "x", BigDecimal.ONE, TODAY, false, "ONE_OFF", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageWithEstimatesOverdueParcelAndRemainder() {
        var bills = new ArrayList<ReminderItem>(List.of(expense("Aluguel", "1500.00", "2026-10-01", false),
                expense("Luz", "90.00", "2026-10-06", true),
                ReminderItem.expense(UUID.randomUUID(), "INSTALLMENT", "Geladeira", new BigDecimal("300.00"),
                        LocalDate.parse("2026-10-07"), false, 3, 10)));
        for (int i = 0; i < 4; i++) bills.add(expense("Extra " + i, "1.00", "2026-10-08", false));
        var summary = ReminderSummary.compose(TODAY, ReminderSlot.FIRST, bills).orElseThrow();
        assertThat(ReminderSummaryText.render(summary, LocalTime.of(9, 0), "https://contas.example/lembretes/resumos/1"))
                .isEqualTo("""
                        Contas a pagar — 05/10/2026, 09:00
                        7 contas, total R$ 1.894,00, 1 atrasada (1 estimada: R$ 90,00)
                        • ATRASADA 01/10 Aluguel — R$ 1.500,00
                        • 06/10 Luz — R$ 90,00 (estimada)
                        • 07/10 Geladeira (3/10) — R$ 300,00
                        • 08/10 Extra 0 — R$ 1,00
                        • 08/10 Extra 1 — R$ 1,00
                        e mais 2 contas
                        Lista completa: https://contas.example/lembretes/resumos/1""");
    }

    @Test
    void messageSingularFormsAndNoOptionalLines() {
        var one = ReminderSummary.compose(TODAY, ReminderSlot.SECOND, List.of(expense("Água", "80.50", "2026-10-05", false)))
                .orElseThrow();
        assertThat(ReminderSummaryText.render(one, LocalTime.of(18, 0), "L")).isEqualTo("""
                Contas a pagar — 05/10/2026, 18:00
                1 conta, total R$ 80,50
                • 05/10 Água — R$ 80,50
                Lista completa: L""");
        var bills = new ArrayList<ReminderItem>(List.of(expense("A", "1.00", "2026-10-01", true), expense("B", "1.00", "2026-10-02", true)));
        for (int i = 0; i < 4; i++) bills.add(expense("C" + i, "1.00", "2026-10-06", false));
        var text = ReminderSummaryText.render(ReminderSummary.compose(TODAY, ReminderSlot.FIRST, bills).orElseThrow(),
                LocalTime.of(9, 0), "L");
        assertThat(text).contains("6 contas, total R$ 6,00, 2 atrasadas (2 estimadas: R$ 2,00)").contains("e mais 1 conta\n");
    }

    @Test
    void moneyInBrazilianFormat() {
        assertThat(ReminderSummaryText.money(new BigDecimal("0.1"))).isEqualTo("R$ 0,10");
        assertThat(ReminderSummaryText.money(new BigDecimal("1234567.89"))).isEqualTo("R$ 1.234.567,89");
        assertThatThrownBy(() -> ReminderSummaryText.money(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
    }

    static ReminderItem expense(String description, String amount, String due, boolean estimated) {
        return ReminderItem.expense(UUID.randomUUID(), estimated ? "RECURRENCE" : "ONE_OFF", description,
                new BigDecimal(amount), LocalDate.parse(due), estimated, null, null);
    }
}
