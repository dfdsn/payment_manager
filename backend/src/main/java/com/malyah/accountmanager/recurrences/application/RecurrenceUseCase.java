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
    /** H04.5: impact of a change "from this period on", without writing. */
    RecurrenceImpactView previewChange(String actorEmail, ChangeRecurrenceCommand command);
    RecurrenceChangeResult change(String actorEmail, ChangeRecurrenceCommand command);
    /** H04.5: impact of ending the recurrence at a period, without writing. */
    RecurrenceImpactView previewClosure(String actorEmail, CloseRecurrenceCommand command);
    RecurrenceChangeResult close(String actorEmail, CloseRecurrenceCommand command);
}
