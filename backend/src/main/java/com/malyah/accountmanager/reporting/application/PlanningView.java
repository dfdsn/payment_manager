package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;
import java.util.List;

/**
 * H06.3 integrated planning. {@code totals} and {@code months} cover the whole horizon and the whole filtered
 * selection; {@code content} is one page of the items of {@code month}. The date basis is the due date (reference
 * date of a paid expense without due date; projected due date of a forecast).
 */
public record PlanningView(String horizonStart, String horizonEnd, LocalDate periodStart, LocalDate periodEnd,
        String dateBasis, LocalDate today, String timeZone, PlanningTotalsView totals, List<PlanningMonthView> months,
        String month, PlanningTotalsView monthTotals, List<PlanningItemView> content, int page, int size,
        long totalElements, int totalPages) {
    public PlanningView {
        months = List.copyOf(months);
        content = List.copyOf(content);
    }
}
