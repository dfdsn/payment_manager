package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.expenses.application.InstallmentExpenseSnapshot;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.InstallmentExpensesCommand;
import com.malyah.accountmanager.expenses.domain.ExpenseAmount;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.domain.ExpenseValidationException;
import com.malyah.accountmanager.expenses.domain.OneOffExpense;

/** Writes installment entries; the caller's transaction makes purchase and entries atomic. */
public final class JdbcInstallmentExpenses implements InstallmentExpenses {
    private final JdbcTemplate jdbc;
    private final Supplier<UUID> identifiers;

    public JdbcInstallmentExpenses(JdbcTemplate jdbc, Supplier<UUID> identifiers) {
        this.jdbc = jdbc;
        this.identifiers = identifiers;
    }

    @Override
    public List<InstallmentExpenseSnapshot> create(InstallmentExpensesCommand c) {
        Objects.requireNonNull(c);
        var count = c.entries().size();
        if (count < 2) throw new ExpenseValidationException("installments", "Uma compra parcelada tem ao menos duas parcelas.");
        var rows = new ArrayList<Object[]>(count);
        for (int index = 0; index < count; index++) {
            var entry = c.entries().get(index);
            if (entry.number() != index + 1)
                throw new ExpenseValidationException("installments", "As parcelas devem ser numeradas de 1 a " + count + ".");
            // Same rules as any pending expense: description, positive amount within the limit, due date.
            var expense = new OneOffExpense(identifiers.get(), c.spaceId(), c.description(), new ExpenseAmount(entry.amount()),
                    ExpenseStatus.PENDING, entry.dueDate(), null, null, null, c.createdByUserId(), c.createdAt());
            rows.add(new Object[] { expense.id(), c.spaceId(), expense.description(), expense.amount().value(),
                    expense.dueDate(), expense.referenceDate(), c.createdByUserId(), Timestamp.from(c.createdAt()),
                    c.categoryId(), c.responsibleUserId(), c.purchaseId(), entry.number(), count });
        }
        jdbc.batchUpdate("""
                insert into expense_entries(id, space_id, origin, description, charge_amount, charge_confirmed, status,
                    due_date, reference_date, created_by_user_id, created_at, version, category_id, responsible_user_id,
                    installment_purchase_id, installment_number, installment_count)
                values (?, ?, 'INSTALLMENT', ?, ?, true, 'PENDING', ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """, rows);
        return find(c.spaceId(), c.purchaseId());
    }

    @Override
    public List<InstallmentExpenseSnapshot> find(UUID spaceId, UUID purchaseId) {
        return findByPurchases(spaceId, List.of(purchaseId));
    }

    @Override
    public List<InstallmentExpenseSnapshot> findByPurchases(UUID spaceId, Collection<UUID> purchaseIds) {
        if (purchaseIds.isEmpty()) return List.of();
        var ids = List.copyOf(purchaseIds);
        var parameters = new ArrayList<Object>(ids.size() + 1);
        parameters.add(spaceId);
        parameters.addAll(ids);
        return jdbc.query("""
                select e.id, e.installment_purchase_id, e.installment_number, e.installment_count, e.charge_amount,
                       e.due_date, e.status, e.version, e.description, e.category_id, category.name,
                       e.responsible_user_id, responsible.display_name, e.payment_date, e.paid_amount
                  from expense_entries e
                  left join expense_categories category on category.id = e.category_id and category.space_id = e.space_id
                  left join identity_users responsible on responsible.id = e.responsible_user_id
                 where e.space_id = ? and e.origin = 'INSTALLMENT' and e.installment_purchase_id in (%s)
                 order by e.installment_purchase_id, e.installment_number
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (rs, row) -> new InstallmentExpenseSnapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getInt(3), rs.getInt(4), rs.getBigDecimal(5), rs.getObject(6, LocalDate.class),
                        ExpenseStatus.valueOf(rs.getString(7)), rs.getLong(8), rs.getString(9),
                        rs.getObject(10, UUID.class), rs.getString(11), rs.getObject(12, UUID.class), rs.getString(13),
                        rs.getObject(14, LocalDate.class), rs.getBigDecimal(15)), parameters.toArray());
    }
}
