package com.malyah.accountmanager.recurrences.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The calendar of a recurrence over time (H04.5). Each period (month) has at most one occurrence; the period is
 * the logical identity, so changing the due day or frequency never creates a second occurrence in a month.
 * The end is the last period of charge (inclusive), never extended by a change.
 */
public final class RecurrenceSchedule {
    private final YearMonth firstMonth;
    private final YearMonth endMonth;
    private final List<RecurrenceSegment> segments;

    private RecurrenceSchedule(YearMonth firstMonth, YearMonth endMonth, List<RecurrenceSegment> segments) {
        this.firstMonth = Objects.requireNonNull(firstMonth);
        this.segments = segments.stream().sorted(Comparator.comparing(RecurrenceSegment::effectiveMonth)).toList();
        if (this.segments.isEmpty() || !this.segments.getFirst().effectiveMonth().equals(firstMonth))
            throw new IllegalArgumentException("The first segment must start at the first period.");
        if (endMonth != null && endMonth.isBefore(firstMonth))
            throw new RecurrenceValidationException("lastDueDate", "O término não pode ser anterior ao primeiro vencimento.");
        this.endMonth = endMonth;
    }

    /**
     * Builds the schedule from persisted segments. The stored inclusive end date is normalized to its period: the
     * last period whose occurrence falls on or before it, which is how the end has been interpreted since H04.1.
     */
    public static RecurrenceSchedule of(LocalDate firstDueDate, LocalDate lastDueDate, List<RecurrenceSegment> segments) {
        Objects.requireNonNull(firstDueDate);
        var unbounded = new RecurrenceSchedule(YearMonth.from(firstDueDate), null, segments);
        if (lastDueDate == null) return unbounded;
        if (lastDueDate.isBefore(firstDueDate))
            throw new RecurrenceValidationException("lastDueDate", "O término não pode ser anterior ao primeiro vencimento.");
        for (var month = YearMonth.from(lastDueDate); !month.isBefore(unbounded.firstMonth); month = month.minusMonths(1)) {
            var occurrence = unbounded.occurrenceIn(month);
            if (occurrence.isPresent() && !occurrence.get().dueDate().isAfter(lastDueDate))
                return new RecurrenceSchedule(unbounded.firstMonth, month, segments);
        }
        return new RecurrenceSchedule(unbounded.firstMonth, unbounded.firstMonth, segments);
    }

    public Optional<ScheduledOccurrence> occurrenceIn(YearMonth month) {
        Objects.requireNonNull(month);
        if (month.isBefore(firstMonth) || endMonth != null && month.isAfter(endMonth)) return Optional.empty();
        var segment = segmentFor(month);
        var configuration = segment.configuration();
        var distance = ChronoUnit.MONTHS.between(segment.effectiveMonth(), month);
        if (distance % configuration.frequency().months() != 0) return Optional.empty();
        var date = month.atDay(Math.min(configuration.baseDay(), month.lengthOfMonth()));
        return Optional.of(new ScheduledOccurrence(date, segment));
    }

    public RecurrenceSegment segmentFor(YearMonth month) {
        RecurrenceSegment result = segments.getFirst();
        for (var segment : segments) if (!segment.effectiveMonth().isAfter(month)) result = segment;
        return result;
    }

    public List<LocalDate> dates(YearMonth from, YearMonth to, int limit) {
        var result = new ArrayList<LocalDate>();
        for (var month = from; !month.isAfter(to) && result.size() < limit; month = month.plusMonths(1))
            occurrenceIn(month).ifPresent(occurrence -> result.add(occurrence.dueDate()));
        return List.copyOf(result);
    }

    /** First {@code limit} dates from the first period, as the H04.1 preview shows them. */
    public List<LocalDate> firstDates(int limit) {
        var result = new ArrayList<LocalDate>();
        var last = endMonth == null ? firstMonth.plusYears(limit + 1L) : endMonth;
        for (var month = firstMonth; !month.isAfter(last) && result.size() < limit; month = month.plusMonths(1))
            occurrenceIn(month).ifPresent(occurrence -> result.add(occurrence.dueDate()));
        return List.copyOf(result);
    }

    /**
     * RF-REC-14: applies the edited fields from {@code from} on. A segment starts at {@code from}; later segments keep
     * their own values for the fields not edited. A frequency change must keep later segments on the new calendar.
     */
    public RecurrenceSchedule withChange(YearMonth from, RecurrenceConfiguration requested) {
        var start = occurrenceIn(from).orElseThrow(() -> new RecurrenceValidationException("effectiveDueDate",
                "Escolha um período que tenha cobrança na programação atual."));
        var edited = requested.differencesFrom(start.segment().configuration());
        if (edited.isEmpty())
            throw new RecurrenceValidationException("configuration", "Nenhum campo foi alterado a partir deste período.");
        var amountEdited = edited.contains(RecurrenceChangeField.AMOUNT);
        var result = new ArrayList<RecurrenceSegment>();
        boolean startExists = false;
        for (var segment : segments) {
            if (segment.effectiveMonth().isBefore(from)) { result.add(segment); continue; }
            if (segment.effectiveMonth().equals(from)) startExists = true;
            else if (edited.contains(RecurrenceChangeField.FREQUENCY)
                    && ChronoUnit.MONTHS.between(from, segment.effectiveMonth()) % requested.frequency().months() != 0)
                throw new RecurrenceValidationException("frequency",
                        "Há outra alteração programada a partir de " + segment.effectiveMonth()
                                + " que ficaria fora da nova frequência. Altere a partir desse período ou escolha outra frequência.");
            result.add(segment.with(segment.configuration().with(requested, edited),
                    segment.estimateReset() || amountEdited));
        }
        if (!startExists)
            result.add(new RecurrenceSegment(from, start.segment().configuration().with(requested, edited), amountEdited));
        var changed = new RecurrenceSchedule(firstMonth, endMonth, result);
        if (endMonth != null && changed.occurrenceIn(endMonth).isEmpty())
            throw new RecurrenceValidationException("frequency",
                    "O último período da recorrência ficaria sem cobrança com a nova frequência. Ajuste o término antes.");
        return changed;
    }

    /**
     * RF-REC-16: the last period is the latest one whose due date is on or before {@code lastDueDate} (the cut-off
     * date is inclusive). The end can only be brought forward; no occurrence after it is generated.
     */
    public RecurrenceSchedule endingAt(LocalDate lastDueDate) {
        Objects.requireNonNull(lastDueDate);
        for (var month = YearMonth.from(lastDueDate); !month.isBefore(firstMonth); month = month.minusMonths(1)) {
            var occurrence = occurrenceIn(month);
            if (occurrence.isEmpty() || occurrence.get().dueDate().isAfter(lastDueDate)) continue;
            if (endMonth != null && !month.isBefore(endMonth))
                throw new RecurrenceValidationException("lastDueDate",
                        "A recorrência já termina neste vencimento ou antes dele. O término só pode ser antecipado.");
            return new RecurrenceSchedule(firstMonth, month, segments);
        }
        throw new RecurrenceValidationException("lastDueDate", "O término não pode ser anterior ao primeiro vencimento.");
    }

    public Set<RecurrenceChangeField> editedFields(YearMonth from, RecurrenceConfiguration requested) {
        var start = occurrenceIn(from);
        return start.isEmpty() ? EnumSet.noneOf(RecurrenceChangeField.class)
                : requested.differencesFrom(start.get().segment().configuration());
    }

    public YearMonth firstMonth() { return firstMonth; }
    public YearMonth endMonth() { return endMonth; }
    public List<RecurrenceSegment> segments() { return segments; }

    /** The due date of the last period, or null when the recurrence has no end. */
    public LocalDate lastDueDate() {
        return endMonth == null ? null : occurrenceIn(endMonth).map(ScheduledOccurrence::dueDate).orElseThrow();
    }

    public record ScheduledOccurrence(LocalDate dueDate, RecurrenceSegment segment) { }
}
