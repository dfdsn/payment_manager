package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;

/** Effect on a forecast (not materialized) period within the 12-month horizon: CHANGED, ADDED or REMOVED. */
public record ImpactForecastView(LocalDate month, String action, LocalDate previousDueDate, LocalDate newDueDate,
        String previousAmount, String newAmount, String previousDescription, String newDescription) { }
