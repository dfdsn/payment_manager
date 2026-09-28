package com.malyah.accountmanager.recurrences.application;
import java.time.YearMonth; import java.util.List;
public record ForecastPeriodView(YearMonth from,YearMonth to,List<ForecastView> occurrences) { }
