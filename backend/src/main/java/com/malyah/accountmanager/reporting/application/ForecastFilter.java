package com.malyah.accountmanager.reporting.application;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.malyah.accountmanager.recurrences.application.PlannedForecast;

/**
 * Applies to forecasts the filters that the shared expense predicate applies in PostgreSQL: case-insensitive
 * substring of the description, category (or none) and responsible (or none). Keeping both sides equal is what
 * lets materialized expenses and forecasts share one set of totals.
 */
record ForecastFilter(String search, UUID categoryId, boolean withoutCategory, UUID responsibleUserId,
        boolean withoutResponsible) {
    boolean accepts(PlannedForecast forecast) {
        if (search != null && !search.isBlank()
                && !forecast.description().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
            return false;
        if (withoutCategory && forecast.categoryId() != null) return false;
        if (!withoutCategory && categoryId != null && !categoryId.equals(forecast.categoryId())) return false;
        if (withoutResponsible && forecast.responsibleUserId() != null) return false;
        return withoutResponsible || responsibleUserId == null
                || Objects.equals(responsibleUserId, forecast.responsibleUserId());
    }
}
