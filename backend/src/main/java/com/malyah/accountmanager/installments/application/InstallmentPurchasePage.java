package com.malyah.accountmanager.installments.application;

import java.util.List;

public record InstallmentPurchasePage(List<InstallmentPurchaseSummary> items, int page, int size, long totalItems) {
    public InstallmentPurchasePage {
        items = List.copyOf(items);
    }
}
