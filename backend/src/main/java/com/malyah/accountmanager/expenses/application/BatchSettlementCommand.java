package com.malyah.accountmanager.expenses.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BatchSettlementCommand(
        List<BatchSettlementItem> items,
        LocalDate paymentDate,
        UUID paidByUserId,
        boolean confirmed,
        UUID idempotencyKey) { }
