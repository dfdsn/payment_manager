package com.malyah.accountmanager.reporting.application;

public interface ExportUseCase {
    CsvFile expenses(String actorEmail, ExpenseExportQuery query);

    CsvFile forecasts(String actorEmail, ForecastExportQuery query);
}
