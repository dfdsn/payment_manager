package com.malyah.accountmanager.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.notifications.application.ReminderSettingsIdempotencyConflictException;
import com.malyah.accountmanager.notifications.application.SettingsClaim;
import com.malyah.accountmanager.notifications.application.SettingsEvent;
import com.malyah.accountmanager.notifications.application.StoredConsent;
import com.malyah.accountmanager.notifications.application.StoredReminderSettings;
import com.malyah.accountmanager.notifications.application.port.ReminderSettingsRepository;
import com.malyah.accountmanager.notifications.domain.ConsentRevocationReason;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.WhatsAppRecipient;
import com.malyah.accountmanager.notifications.domain.WhatsAppSuspensionReason;

/** H08.1 persistence; display names come from a read projection of the identity users, never written here. */
public final class JdbcReminderSettingsRepository implements ReminderSettingsRepository {
    private final JdbcTemplate jdbc;

    public JdbcReminderSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredReminderSettings> find(UUID spaceId) {
        return jdbc.query(SELECT + " where space_id = ?", this::settings, spaceId).stream().findFirst();
    }

    @Override
    public StoredReminderSettings lock(UUID spaceId, Instant at) {
        jdbc.update("""
                insert into reminder_settings(space_id, version, updated_at) values (?, 0, ?)
                on conflict (space_id) do nothing
                """, spaceId, Timestamp.from(at));
        return jdbc.queryForObject(SELECT + " where space_id = ? for update", this::settings, spaceId);
    }

    @Override
    public boolean update(StoredReminderSettings settings, UUID actorId) {
        var recipient = settings.recipient() == null ? null : settings.recipient().e164();
        return jdbc.update("""
                update reminder_settings set first_time = ?, second_time = ?, whatsapp_recipient = ?,
                       whatsapp_enabled = ?, version = ?, updated_at = ?, updated_by_user_id = ?,
                       whatsapp_suspension_reason = ?, whatsapp_suspended_at = ?
                 where space_id = ? and version = ?
                """, settings.schedule().first(), settings.schedule().second(), recipient, settings.enabled(),
                settings.version(), Timestamp.from(settings.updatedAt()), actorId,
                settings.suspension() == null ? null : settings.suspension().name(),
                settings.suspendedAt() == null ? null : Timestamp.from(settings.suspendedAt()), settings.spaceId(),
                settings.version() - 1) == 1;
    }

    @Override
    public Optional<StoredConsent> activeConsent(UUID spaceId) {
        return jdbc.query("""
                select c.id, c.user_id, u.display_name, c.recipient, c.consent_text_version, c.granted_at
                  from whatsapp_consents c join identity_users u on u.id = c.user_id
                 where c.space_id = ? and c.revoked_at is null
                """, (rs, row) -> new StoredConsent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), new WhatsAppRecipient(rs.getString(4)), rs.getString(5),
                rs.getTimestamp(6).toInstant()), spaceId).stream().findFirst();
    }

    @Override
    public void insertConsent(StoredConsent consent, UUID spaceId) {
        jdbc.update("""
                insert into whatsapp_consents(id, space_id, user_id, recipient, consent_text_version, granted_at)
                values (?, ?, ?, ?, ?, ?)
                """, consent.id(), spaceId, consent.userId(), consent.recipient().e164(), consent.textVersion(),
                Timestamp.from(consent.grantedAt()));
    }

    @Override
    public void revokeConsent(UUID consentId, UUID actorId, ConsentRevocationReason reason, Instant at) {
        jdbc.update("""
                update whatsapp_consents set revoked_at = ?, revoked_by_user_id = ?, revocation_reason = ?
                 where id = ? and revoked_at is null
                """, Timestamp.from(at), actorId, reason.name(), consentId);
    }

    @Override
    public void appendEvent(SettingsEvent event) {
        jdbc.update("""
                insert into reminder_settings_events(id, space_id, actor_user_id, event_type, from_version, to_version,
                    detail, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.spaceId(), event.actorId(), event.type().name(), event.fromVersion(),
                event.toVersion(), event.detail(), Timestamp.from(event.occurredAt()));
    }

    @Override
    public List<SettingsEvent> events(UUID spaceId, int limit) {
        return jdbc.query("""
                select e.id, e.space_id, e.actor_user_id, coalesce(u.display_name, 'Sistema'), e.event_type,
                       e.from_version, e.to_version, e.detail, e.occurred_at
                  from reminder_settings_events e left join identity_users u on u.id = e.actor_user_id
                 where e.space_id = ? order by e.occurred_at desc, e.to_version desc, e.event_type, e.id limit ?
                """, (rs, row) -> new SettingsEvent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), SettingsEvent.Type.valueOf(rs.getString(5)),
                rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getTimestamp(9).toInstant()), spaceId, limit);
    }

    @Override
    public SettingsClaim claim(UUID spaceId, UUID actorId, String operation, UUID key, String requestHash,
            Instant at) {
        int claimed = jdbc.update("""
                insert into reminder_settings_requests(space_id, actor_user_id, operation, idempotency_key,
                    request_hash, created_at)
                values (?, ?, ?, ?, ?, ?) on conflict do nothing
                """, spaceId, actorId, operation, key, requestHash, Timestamp.from(at));
        if (claimed == 1) return new SettingsClaim(false, null);
        var existing = jdbc.queryForObject("""
                select request_hash, result_version from reminder_settings_requests
                 where space_id = ? and actor_user_id = ? and operation = ? and idempotency_key = ? for update
                """, (rs, row) -> new SettingsClaim(requestHash.equals(rs.getString(1)),
                rs.getObject(2, Long.class)), spaceId, actorId, operation, key);
        if (existing == null || !existing.replayed() || existing.resultVersion() == null)
            throw new ReminderSettingsIdempotencyConflictException();
        return existing;
    }

    @Override
    public void complete(UUID spaceId, UUID actorId, String operation, UUID key, long resultVersion, Instant at) {
        jdbc.update("""
                update reminder_settings_requests set result_version = ?, completed_at = ?
                 where space_id = ? and actor_user_id = ? and operation = ? and idempotency_key = ?
                """, resultVersion, Timestamp.from(at), spaceId, actorId, operation, key);
    }

    private static final String SELECT = """
            select space_id, first_time, second_time, whatsapp_recipient, whatsapp_enabled, version, updated_at,
                   whatsapp_suspension_reason, whatsapp_suspended_at
              from reminder_settings""";

    private StoredReminderSettings settings(ResultSet rs, int row) throws SQLException {
        var recipient = rs.getString(4);
        return new StoredReminderSettings(rs.getObject(1, UUID.class),
                new ReminderSchedule(rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class)),
                recipient == null ? null : new WhatsAppRecipient(recipient), rs.getBoolean(5), rs.getLong(6),
                rs.getTimestamp(7).toInstant(), rs.getString(8) == null ? null
                        : WhatsAppSuspensionReason.valueOf(rs.getString(8)),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant());
    }
}
