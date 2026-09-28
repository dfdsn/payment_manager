package com.malyah.accountmanager.installments.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.installments.application.InstallmentIdempotencyConflictException;
import com.malyah.accountmanager.installments.application.PurchaseClaim;
import com.malyah.accountmanager.installments.application.StoredInstallmentPurchase;
import com.malyah.accountmanager.installments.application.port.InstallmentPurchaseRepository;
import com.malyah.accountmanager.installments.domain.InstallmentPlan;

final class JdbcInstallmentPurchaseRepository implements InstallmentPurchaseRepository {
    private final JdbcTemplate jdbc;

    JdbcInstallmentPurchaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public PurchaseClaim claim(UUID spaceId, UUID actorId, UUID key, String requestHash, Instant at) {
        int claimed = jdbc.update("""
                insert into installment_purchase_requests(space_id, actor_user_id, idempotency_key, request_hash, created_at)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """, spaceId, actorId, key, requestHash, Timestamp.from(at));
        if (claimed == 1) return new PurchaseClaim(false, null);
        var existing = jdbc.queryForObject("""
                select request_hash, purchase_id from installment_purchase_requests
                 where space_id = ? and actor_user_id = ? and idempotency_key = ? for update
                """, (rs, row) -> new PurchaseClaim(requestHash.equals(rs.getString(1)), rs.getObject(2, UUID.class)),
                spaceId, actorId, key);
        if (existing == null || !existing.replayed() || existing.purchaseId() == null)
            throw new InstallmentIdempotencyConflictException();
        return existing;
    }

    @Override
    public void insert(UUID id, UUID spaceId, InstallmentPlan plan, UUID categoryId, UUID responsibleUserId,
            UUID actorId, Instant at) {
        jdbc.update("""
                insert into installment_purchases(id, space_id, description, total_amount, installment_count,
                    first_due_date, category_id, responsible_user_id, created_by_user_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, spaceId, plan.description(), plan.total(), plan.count(), plan.firstDueDate(), categoryId,
                responsibleUserId, actorId, Timestamp.from(at));
        jdbc.update("""
                insert into installment_purchase_events(id, purchase_id, space_id, actor_user_id, event_type, occurred_at)
                values (?, ?, ?, ?, 'PURCHASE_CREATED', ?)
                """, UUID.randomUUID(), id, spaceId, actorId, Timestamp.from(at));
    }

    @Override
    public void complete(UUID spaceId, UUID actorId, UUID key, UUID purchaseId, Instant at) {
        jdbc.update("""
                update installment_purchase_requests set purchase_id = ?, completed_at = ?
                 where space_id = ? and actor_user_id = ? and idempotency_key = ?
                """, purchaseId, Timestamp.from(at), spaceId, actorId, key);
    }

    @Override
    public StoredInstallmentPurchase find(UUID spaceId, UUID purchaseId) {
        return jdbc.queryForObject("""
                select p.id, p.space_id, p.description, p.total_amount, p.installment_count, p.first_due_date,
                       p.category_id, category.name, p.responsible_user_id, responsible.display_name,
                       p.created_by_user_id, creator.display_name, p.created_at
                  from installment_purchases p
                  join identity_users creator on creator.id = p.created_by_user_id
                  left join expense_categories category on category.id = p.category_id and category.space_id = p.space_id
                  left join identity_users responsible on responsible.id = p.responsible_user_id
                 where p.space_id = ? and p.id = ?
                """, (rs, row) -> new StoredInstallmentPurchase(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getBigDecimal(4), rs.getInt(5), rs.getObject(6, java.time.LocalDate.class),
                rs.getObject(7, UUID.class), rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10),
                rs.getObject(11, UUID.class), rs.getString(12), rs.getTimestamp(13).toInstant()), spaceId, purchaseId);
    }
}
