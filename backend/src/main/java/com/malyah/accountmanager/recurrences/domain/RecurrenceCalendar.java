package com.malyah.accountmanager.recurrences.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

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
}
