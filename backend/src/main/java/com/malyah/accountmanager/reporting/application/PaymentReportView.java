package com.malyah.accountmanager.reporting.application;

import java.time.LocalDate;
import java.util.List;

/**
 * H06.2 payment view of one month by effective payment date ({@code dateBasis = PAYMENT_DATE}). The indicators
 * cover the whole selection; {@code content} is one page of it.
 */
public record PaymentReportView(String month, LocalDate periodStart, LocalDate periodEnd, String dateBasis,
        String timeZone, PaymentIndicatorsView indicators, List<PaymentRowView> content, int page, int size,
        long totalElements, int totalPages, String sort, String direction) {
    public PaymentReportView {
        content = List.copyOf(content);
    }
}
