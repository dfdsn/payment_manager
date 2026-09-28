package com.malyah.accountmanager.expenses.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * H05.1: public contract through which a purchase creates its installment entries. Each installment is an ordinary
 * pending expense of origin INSTALLMENT, validated by the same rules as any other expense; the purchase itself is
 * never an expense. Must run inside the caller's transaction.
 */
public interface InstallmentExpenses {
    List<InstallmentExpenseSnapshot> create(InstallmentExpensesCommand command);

    List<InstallmentExpenseSnapshot> find(UUID spaceId, UUID purchaseId);

    /** H05.2: installments of several purchases of the same space, ordered by purchase and number. */
    List<InstallmentExpenseSnapshot> findByPurchases(UUID spaceId, Collection<UUID> purchaseIds);
}
