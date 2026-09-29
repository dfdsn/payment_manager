package com.malyah.accountmanager.reporting.domain;

/** Kind of expense a planned value comes from; forecasts always come from recurrences. */
public enum PlanningOrigin {
    ONE_OFF,
    INSTALLMENT,
    RECURRENCE
}
