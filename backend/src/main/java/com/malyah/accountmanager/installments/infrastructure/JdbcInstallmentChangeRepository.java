package com.malyah.accountmanager.installments.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.installments.application.ChangeClaim;
import com.malyah.accountmanager.installments.application.InstallmentIdempotencyConflictException;
import com.malyah.accountmanager.installments.application.StoredInstallmentChange;
import com.malyah.accountmanager.installments.application.port.InstallmentChangeRepository;

final class JdbcInstallmentChangeRepository implements InstallmentChangeRepository {
    private final JdbcTemplate jdbc;

    JdbcInstallmentChangeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean lockPurchase(UUID spaceId, UUID purchaseId) {
        return !jdbc.query("select id from installment_purchases where space_id = ? and id = ? for update",
                (rs, row) -> rs.getObject(1, UUID.class), spaceId, purchaseId).isEmpty();
    }

    @Override
    public ChangeClaim claim(UUID spaceId, UUID actorId, UUID key, String requestHash, Instant at) {
        int claimed = jdbc.update("""
                insert into installment_change_requests(space_id, actor_user_id, idempotency_key, request_hash, created_at)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """, spaceId, actorId, key, requestHash, Timestamp.from(at));
        if (claimed == 1) return new ChangeClaim(false, null);
        var existing = jdbc.queryForObject("""
                select request_hash, change_id from installment_change_requests
                 where space_id = ? and actor_user_id = ? and idempotency_key = ? for update
                """, (rs, row) -> new ChangeClaim(requestHash.equals(rs.getString(1)), rs.getObject(2, UUID.class)),
                spaceId, actorId, key);
        if (existing == null || !existing.replayed() || existing.changeId() == null)
            throw new InstallmentIdempotencyConflictException();
        return existing;
    }

    @Override
    public void insert(StoredInstallmentChange change, UUID spaceId, UUID actorId, String scope, Integer fromNumber,
            String changedFields, String reason, String impactHash, Instant at) {
        jdbc.update("""
                insert into installment_purchase_changes(id, purchase_id, space_id, actor_user_id, change_type, scope,
                    from_number, changed_fields, reason, impact_hash, affected_count, preserved_count,
                    replacement_purchase_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, change.id(), change.purchaseId(), spaceId, actorId, change.changeType(), scope, fromNumber,
                changedFields, reason, impactHash, change.affectedCount(), change.preservedCount(),
                change.replacementPurchaseId(), Timestamp.from(at));
    }

    @Override
    public void complete(UUID spaceId, UUID actorId, UUID key, UUID changeId, Instant at) {
        jdbc.update("""
                update installment_change_requests set change_id = ?, completed_at = ?
                 where space_id = ? and actor_user_id = ? and idempotency_key = ?
                """, changeId, Timestamp.from(at), spaceId, actorId, key);
    }

    @Override
    public Optional<StoredInstallmentChange> find(UUID spaceId, UUID changeId) {
        return jdbc.query("""
                select id, purchase_id, change_type, affected_count, preserved_count, replacement_purchase_id
                  from installment_purchase_changes where space_id = ? and id = ?
                """, (rs, row) -> new StoredInstallmentChange(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getObject(6, UUID.class)), spaceId, changeId)
                .stream().findFirst();
    }

    @Override
    public void markReplacement(UUID spaceId, UUID replacementId, UUID replacedId) {
        jdbc.update("update installment_purchases set replaces_purchase_id = ? where space_id = ? and id = ?",
                replacedId, spaceId, replacementId);
    }
}
