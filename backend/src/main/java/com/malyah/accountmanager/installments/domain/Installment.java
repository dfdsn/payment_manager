package com.malyah.accountmanager.installments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One installment n/N of a purchase: its position, charge and due date. */
public record Installment(int number, BigDecimal amount, LocalDate dueDate) { }
