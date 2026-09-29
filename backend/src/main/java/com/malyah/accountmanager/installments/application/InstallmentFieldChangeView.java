package com.malyah.accountmanager.installments.application;

/** One field of one installment before and after; ids for category and responsible, ISO dates for due dates. */
public record InstallmentFieldChangeView(String field, String from, String to) { }
