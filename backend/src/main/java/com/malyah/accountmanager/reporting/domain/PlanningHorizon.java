package com.malyah.accountmanager.reporting.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RF-REC-08 and T18: the planning covers the current month of the space plus the next 12 months, the same fixed
 * horizon as the recurrence forecasts.
 */
public record PlanningHorizon(YearMonth start, YearMonth end) {
    public static final int FOLLOWING_MONTHS = 12;

    public PlanningHorizon {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.equals(start.plusMonths(FOLLOWING_MONTHS)))
            throw new IllegalArgumentException("O horizonte é o mês atual e os próximos 12 meses.");
    }

    public static PlanningHorizon from(YearMonth current) {
        return new PlanningHorizon(current, current.plusMonths(FOLLOWING_MONTHS));
    }

    public boolean contains(YearMonth month) {
        return !month.isBefore(start) && !month.isAfter(end);
    }

    public List<YearMonth> months() {
        var result = new ArrayList<YearMonth>();
        for (var month = start; !month.isAfter(end); month = month.plusMonths(1)) result.add(month);
        return List.copyOf(result);
    }
}
