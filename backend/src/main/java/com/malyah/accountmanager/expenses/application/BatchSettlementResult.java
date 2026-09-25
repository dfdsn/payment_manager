package com.malyah.accountmanager.expenses.application;

import java.util.List;
import java.util.UUID;

public record BatchSettlementResult(
        UUID operationId,
        List<BatchSettlementItemResult> items,
        boolean replayed) {
    public BatchSettlementResult {
        items = List.copyOf(items);
    }
}
