package com.malyah.accountmanager.recurrences.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecurrenceCalendarTest {
    private final RecurrenceCalendar calendar=new RecurrenceCalendar();

    @Test void preservesBaseDayAcrossShortMonthsAndLeapYears() {
        assertThat(calendar.firstDates(LocalDate.of(2027,1,31),null,RecurrenceFrequency.MONTHLY,5))
                .containsExactly(LocalDate.of(2027,1,31),LocalDate.of(2027,2,28),LocalDate.of(2027,3,31),
                        LocalDate.of(2027,4,30),LocalDate.of(2027,5,31));
        assertThat(calendar.firstDates(LocalDate.of(2028,1,31),null,RecurrenceFrequency.MONTHLY,3))
                .containsExactly(LocalDate.of(2028,1,31),LocalDate.of(2028,2,29),LocalDate.of(2028,3,31));
    }

    @Test void supportsAllApprovedFrequenciesAndYearChangeWithoutBusinessDayShift() {
        assertThat(calendar.firstDates(LocalDate.of(2026,12,30),null,RecurrenceFrequency.BIMONTHLY,3))
                .containsExactly(LocalDate.of(2026,12,30),LocalDate.of(2027,2,28),LocalDate.of(2027,4,30));
        assertThat(calendar.firstDates(LocalDate.of(2026,11,30),null,RecurrenceFrequency.QUARTERLY,2).get(1))
                .isEqualTo(LocalDate.of(2027,2,28));
        assertThat(calendar.firstDates(LocalDate.of(2026,8,31),null,RecurrenceFrequency.SEMIANNUAL,2).get(1))
                .isEqualTo(LocalDate.of(2027,2,28));
        assertThat(calendar.firstDates(LocalDate.of(2024,2,29),null,RecurrenceFrequency.ANNUAL,3))
                .containsExactly(LocalDate.of(2024,2,29),LocalDate.of(2025,2,28),LocalDate.of(2026,2,28));
        assertThat(calendar.firstDates(LocalDate.of(2026,5,31),null,RecurrenceFrequency.MONTHLY,1).getFirst().getDayOfWeek())
                .isEqualTo(java.time.DayOfWeek.SUNDAY);
    }

    @Test void respectsInclusiveEndAndCanReturnNoLaterDate() {
        assertThat(calendar.firstDates(LocalDate.of(2026,1,31),LocalDate.of(2026,3,1),RecurrenceFrequency.MONTHLY,12))
                .containsExactly(LocalDate.of(2026,1,31),LocalDate.of(2026,2,28));
        assertThat(calendar.firstDates(LocalDate.of(2026,1,31),LocalDate.of(2026,1,31),RecurrenceFrequency.MONTHLY,12))
                .containsExactly(LocalDate.of(2026,1,31));
    }

    @Test void rejectsInvalidRangeAndPreviewLimit() {
        assertThatThrownBy(()->calendar.firstDates(LocalDate.of(2026,2,1),LocalDate.of(2026,1,1),RecurrenceFrequency.MONTHLY,12))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->calendar.firstDates(LocalDate.now(),null,RecurrenceFrequency.MONTHLY,13))
                .isInstanceOf(RecurrenceValidationException.class);
    }
}
