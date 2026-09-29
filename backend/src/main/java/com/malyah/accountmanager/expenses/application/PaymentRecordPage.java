package com.malyah.accountmanager.expenses.application;

import java.util.List;

public record PaymentRecordPage(List<PaymentRecord> content, long totalElements) {
    public PaymentRecordPage {
        content = List.copyOf(content);
    }
}
