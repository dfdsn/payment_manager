package com.malyah.accountmanager.installments.domain;

import java.time.LocalDate;
import java.util.UUID;

/** What a change needs to know about one installment: its position, whether it is still pending and its values. */
public record InstallmentState(int number, boolean pending, LocalDate dueDate, String description, UUID categoryId,
        UUID responsibleUserId) { }
