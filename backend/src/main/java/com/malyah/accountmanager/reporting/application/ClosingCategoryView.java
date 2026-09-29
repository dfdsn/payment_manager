package com.malyah.accountmanager.reporting.application;

import java.util.UUID;

import com.malyah.accountmanager.reporting.domain.ClosingCategory;

/** Totals of one category of a closing; {@code categoryId} and {@code categoryName} null mean “Sem categoria”. */
public record ClosingCategoryView(UUID categoryId, String categoryName, long count, String plannedTotal,
        String plannedEstimated, String paidTotal, long pendingCount, String pendingTotal) {
    static ClosingCategoryView of(ClosingCategory category) {
        return new ClosingCategoryView(category.categoryId(), category.categoryName(), category.count(),
                category.plannedTotal().toPlainString(), category.plannedEstimated().toPlainString(),
                category.paidTotal().toPlainString(), category.pendingCount(), category.pendingTotal().toPlainString());
    }
}
