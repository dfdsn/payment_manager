package com.malyah.accountmanager.recurrences.application;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * H06.3: public read contract through which the reporting module obtains the forecasts of a space without
 * repeating the calendar, the segments, the closure or the estimate rules. The caller resolves the authorized
 * space; the result never writes or materializes anything.
 */
public interface RecurrenceForecastQueries {
    /** Forecasts of the months {@code from} to {@code to} without a materialized occurrence, by due date. */
    List<PlannedForecast> unmaterialized(UUID spaceId, YearMonth from, YearMonth to);
}
