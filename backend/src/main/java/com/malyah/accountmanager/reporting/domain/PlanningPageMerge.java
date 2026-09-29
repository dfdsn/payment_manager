package com.malyah.accountmanager.reporting.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;

/**
 * Pages the items of a month that merge materialized expenses (paged by the database) with forecasts (computed in
 * memory). The order is the date, then materialized before forecasts, then each source's own stable order, so the
 * merged position of every item follows from dates alone:
 * <ul>
 * <li>materialized item {@code j} (0-based in its source): {@code j} + forecasts dated strictly before it;</li>
 * <li>forecast {@code i}: {@code i} + materialized items dated on or before it.</li>
 * </ul>
 * With {@code F} forecasts, the materialized items of the page are within the source window starting at
 * {@code offset - F}, so a page never reads more than {@code size + F} rows and never skips or repeats an item.
 */
public final class PlanningPageMerge {
    private PlanningPageMerge() {
    }

    /** Where the database window of materialized items starts for a page. */
    public static long windowStart(long offset, int forecastCount) {
        return Math.max(0, offset - forecastCount);
    }

    /** How many materialized items the window must read so the page is complete. */
    public static int windowLimit(long offset, int size, int forecastCount) {
        return Math.toIntExact(offset + size - windowStart(offset, forecastCount));
    }

    /**
     * Items of the page, in order.
     *
     * @param materializedDates dates of the window read from the database, starting at {@link #windowStart}
     * @param forecastDates dates of every forecast of the month, in order
     * @param materializedPerDay materialized items of the whole month by date
     */
    public static List<Slot> page(long offset, int size, long windowStart, List<LocalDate> materializedDates,
            List<LocalDate> forecastDates, NavigableMap<LocalDate, Long> materializedPerDay) {
        var slots = new ArrayList<Positioned>();
        for (int k = 0; k < materializedDates.size(); k++) {
            var date = materializedDates.get(k);
            var position = windowStart + k + forecastDates.stream().filter(d -> d.isBefore(date)).count();
            if (position >= offset && position < offset + size) slots.add(new Positioned(position, new Slot(false, k)));
        }
        for (int i = 0; i < forecastDates.size(); i++) {
            var upTo = materializedPerDay.headMap(forecastDates.get(i), true).values().stream()
                    .mapToLong(Long::longValue).sum();
            var position = i + upTo;
            if (position >= offset && position < offset + size) slots.add(new Positioned(position, new Slot(true, i)));
        }
        slots.sort(Comparator.comparingLong(Positioned::position));
        return slots.stream().map(Positioned::slot).toList();
    }

    /** An item of the page: the index in the materialized window or in the forecast list. */
    public record Slot(boolean forecast, int index) { }

    private record Positioned(long position, Slot slot) { }
}
