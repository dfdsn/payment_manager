package com.malyah.accountmanager.reporting.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Totals of one category of a closing; {@code categoryId == null} is “Sem categoria”. */
public record ClosingCategory(UUID categoryId, String categoryName, long count, BigDecimal plannedTotal,
        BigDecimal plannedEstimated, BigDecimal paidTotal, long pendingCount, BigDecimal pendingTotal) { }
