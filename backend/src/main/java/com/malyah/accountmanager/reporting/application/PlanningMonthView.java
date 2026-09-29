package com.malyah.accountmanager.reporting.application;

/** Indicators of one month of the horizon ({@code AAAA-MM}). */
public record PlanningMonthView(String month, PlanningTotalsView totals) { }
