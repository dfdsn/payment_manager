package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.List;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

public interface RecurrenceUseCase {
    RecurrenceCreationResult create(String actorEmail, CreateRecurrenceCommand command);
    List<RecurrenceView> list(String actorEmail);
    List<LocalDate> preview(LocalDate firstDueDate, LocalDate lastDueDate, RecurrenceFrequency frequency);
    ForecastPeriodView forecasts(String actorEmail);
    AnticipationResult anticipate(String actorEmail, java.util.UUID recurrenceId, LocalDate scheduledDueDate,
            java.util.UUID idempotencyKey);
    AnticipationResult confirmForecastCharge(String actorEmail, java.util.UUID recurrenceId, LocalDate scheduledDueDate,
            String confirmedAmount, java.util.UUID idempotencyKey);
}
