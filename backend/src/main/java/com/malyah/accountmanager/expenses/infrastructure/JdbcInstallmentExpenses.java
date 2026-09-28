package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.util.ArrayList;
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
        return jdbc.query("""
                select id, installment_number, installment_count, charge_amount, due_date, status, version
                  from expense_entries
                 where space_id = ? and installment_purchase_id = ? and origin = 'INSTALLMENT'
                 order by installment_number
                """, (rs, row) -> new InstallmentExpenseSnapshot(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3),
                rs.getBigDecimal(4), rs.getObject(5, java.time.LocalDate.class), ExpenseStatus.valueOf(rs.getString(6)),
                rs.getLong(7)), spaceId, purchaseId);
    }
}
