package com.malyah.accountmanager.recurrences.application;
import java.time.LocalDate; import java.util.UUID;
public record ForecastView(UUID recurrenceId,String description,String amount,boolean estimated,
        LocalDate scheduledDueDate,String state,UUID expenseId,LocalDate actualDueDate,String expenseStatus,
        boolean chargeConfirmed,String reviewReason) {
    public ForecastView(UUID recurrenceId,String description,String amount,boolean estimated,LocalDate scheduledDueDate,
            String state,UUID expenseId,LocalDate actualDueDate,String expenseStatus,boolean chargeConfirmed) {
        this(recurrenceId,description,amount,estimated,scheduledDueDate,state,expenseId,actualDueDate,expenseStatus,
                chargeConfirmed,null);
    }
}
