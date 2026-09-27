package com.malyah.accountmanager.recurrences.application;

import java.time.LocalDate;
import java.util.List;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

public interface RecurrenceUseCase {
    RecurrenceCreationResult create(String actorEmail, CreateRecurrenceCommand command);
    List<RecurrenceView> list(String actorEmail);
    List<LocalDate> preview(LocalDate firstDueDate, LocalDate lastDueDate, RecurrenceFrequency frequency);
}
