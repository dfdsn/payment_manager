package com.malyah.accountmanager.recurrences.application;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Exposes the forecast reconciliation of {@link RecurrenceService} as {@link RecurrenceForecastQueries}. It is a
 * separate type so the service itself never becomes a second {@link RecurrenceUseCase} bean.
 */
public final class RecurrenceForecastCatalog implements RecurrenceForecastQueries {
    private final RecurrenceService service;

    public RecurrenceForecastCatalog(RecurrenceService service) {
        this.service = Objects.requireNonNull(service);
    }

    @Override
    public List<PlannedForecast> unmaterialized(UUID spaceId, YearMonth from, YearMonth to) {
        return service.plannedForecasts(spaceId, from, to);
    }
}
