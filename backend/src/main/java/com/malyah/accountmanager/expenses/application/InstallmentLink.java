package com.malyah.accountmanager.expenses.application;

import java.util.UUID;

/** Identifies an installment entry as n/N of a purchase (RF-PAR-02). */
public record InstallmentLink(UUID purchaseId, int number, int count) { }
