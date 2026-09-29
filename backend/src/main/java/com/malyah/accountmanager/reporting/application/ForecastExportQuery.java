package com.malyah.accountmanager.reporting.application;

import java.util.UUID;

/** H06.4: the filters of the planning (H06.3) for the separate export of forecasts. */
public record ForecastExportQuery(String search, UUID categoryId, boolean withoutCategory, UUID responsibleUserId,
        boolean withoutResponsible) { }
