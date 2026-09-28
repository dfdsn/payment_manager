package com.malyah.accountmanager.recurrences.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RecurrenceCalendar {
    public List<LocalDate> firstDates(LocalDate firstDueDate, LocalDate lastDueDate,
            RecurrenceFrequency frequency, int limit) {
        if (firstDueDate == null || frequency == null) throw new RecurrenceValidationException("calendar", "Informe primeiro vencimento e frequência.");
        if (lastDueDate != null && lastDueDate.isBefore(firstDueDate))
            throw new RecurrenceValidationException("lastDueDate", "O término não pode ser anterior ao primeiro vencimento.");
        if (limit < 1 || limit > 12) throw new RecurrenceValidationException("limit", "A prévia aceita de 1 a 12 datas.");
        var dates = new ArrayList<LocalDate>();
        var baseMonth = YearMonth.from(firstDueDate);
        var baseDay = firstDueDate.getDayOfMonth();
        for (int position = 0; dates.size() < limit; position++) {
            var month = baseMonth.plusMonths((long) position * frequency.months());
            var date = month.atDay(Math.min(baseDay, month.lengthOfMonth()));
            if (lastDueDate != null && date.isAfter(lastDueDate)) break;
            dates.add(date);
        }
        return List.copyOf(dates);
    }

    public Optional<LocalDate> occurrenceInMonth(LocalDate firstDueDate, LocalDate lastDueDate,
            RecurrenceFrequency frequency, YearMonth targetMonth) {
        if (firstDueDate == null || frequency == null || targetMonth == null)
            throw new RecurrenceValidationException("calendar", "Informe primeiro vencimento, frequência e mês.");
        var firstMonth = YearMonth.from(firstDueDate);
        var distance = java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, targetMonth);
        if (distance < 0 || distance % frequency.months() != 0) return Optional.empty();
        var date = targetMonth.atDay(Math.min(firstDueDate.getDayOfMonth(), targetMonth.lengthOfMonth()));
        if (date.isBefore(firstDueDate) || lastDueDate != null && date.isAfter(lastDueDate)) return Optional.empty();
        return Optional.of(date);
    }
}
