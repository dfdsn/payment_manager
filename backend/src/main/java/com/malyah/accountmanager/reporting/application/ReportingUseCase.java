package com.malyah.accountmanager.reporting.application;

public interface ReportingUseCase {
    DueDashboardView dueDashboard(String actorEmail, ReportFilters filters);
}
