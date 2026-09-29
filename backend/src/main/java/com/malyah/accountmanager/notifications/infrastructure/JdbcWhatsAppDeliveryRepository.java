package com.malyah.accountmanager.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.notifications.application.StoredDelivery;
import com.malyah.accountmanager.notifications.application.port.WhatsAppDeliveryRepository;
import com.malyah.accountmanager.notifications.application.port.WhatsAppSender;
import com.malyah.accountmanager.notifications.domain.ReminderSlot;
import com.malyah.accountmanager.notifications.domain.WhatsAppDeliveryStatus;

/**
 * H08.4 persistence. The unique keys (summary, space + test key, provider message id, message + status) are what
 * make re-executions, concurrent workers and repeated webhooks harmless: every insert is {@code on conflict do
 * nothing} and reports whether it created the row.
 */
public final class JdbcWhatsAppDeliveryRepository implements WhatsAppDeliveryRepository {
    private static final String SELECT = """
            select id, space_id, kind, summary_id, status, skip_reason, failure_code, provider_error_code,
                   recipient_last_digits, item_count, created_at, attempted_at, accepted_at, sent_at, delivered_at,
                   read_at, failed_at
              from whatsapp_deliveries
            """;
    private final JdbcTemplate jdbc;

    public JdbcWhatsAppDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> plannedSummaries(int limit) {
        return jdbc.query("""
                select s.id from reminder_summaries s
                  join reminder_summary_channels c on c.summary_id = s.id
                 where c.channel = 'WHATSAPP' and c.status = 'PLANNED'
                   and not exists (select 1 from whatsapp_deliveries d where d.summary_id = s.id)
                 order by s.generated_at, s.id limit ?
                """, (rs, row) -> rs.getObject(1, UUID.class), limit);
    }

    @Override
    public Optional<PlannedSummary> planned(UUID summaryId) {
        return jdbc.query("""
                select s.id, s.space_id, c.recipient_user_id, s.local_date, s.slot, s.scheduled_time, s.time_zone,
                       s.scheduled_at
                  from reminder_summaries s join reminder_summary_channels c on c.summary_id = s.id
                 where s.id = ? and c.channel = 'WHATSAPP' and c.status = 'PLANNED'
                   and not exists (select 1 from whatsapp_deliveries d where d.summary_id = s.id)
                """, (rs, row) -> new PlannedSummary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class), ReminderSlot.valueOf(rs.getString(5)),
                rs.getObject(6, LocalTime.class), rs.getString(7), rs.getTimestamp(8).toInstant()), summaryId)
                .stream().findFirst();
    }

    @Override
    public boolean insert(NewDelivery delivery) {
        var at = Timestamp.from(delivery.at());
        var attempting = delivery.status() == WhatsAppDeliveryStatus.ATTEMPTING;
        return jdbc.update("""
                insert into whatsapp_deliveries(id, space_id, kind, summary_id, test_key, recipient_user_id, consent_id,
                    recipient_last_digits, status, skip_reason, failure_code, item_count, created_at, updated_at,
                    attempted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing
                """, delivery.id(), delivery.spaceId(), delivery.kind().name(), delivery.summaryId(),
                delivery.testKey(), delivery.recipientUserId(), delivery.consentId(), delivery.recipientLastDigits(),
                delivery.status().name(), delivery.skipReason(), delivery.failureCode(), delivery.itemCount(), at, at,
                attempting ? at : null) == 1;
    }

    @Override
    public void insertAttempt(UUID attemptId, UUID deliveryId, int number, Instant at) {
        jdbc.update("""
                insert into whatsapp_attempts(id, delivery_id, attempt_number, started_at) values (?, ?, ?, ?)
                """, attemptId, deliveryId, number, Timestamp.from(at));
    }

    @Override
    public boolean finish(UUID deliveryId, UUID attemptId, WhatsAppDeliveryStatus status, String providerMessageId,
            String providerErrorCode, String failureCode, Instant at) {
        var now = Timestamp.from(at);
        var updated = jdbc.update("""
                update whatsapp_deliveries
                   set status = ?, provider_message_id = ?, provider_error_code = ?, failure_code = ?, updated_at = ?,
                       accepted_at = case when ? = 'ACCEPTED' then ? else accepted_at end,
                       failed_at = case when ? in ('FAILED', 'REJECTED') then ? else failed_at end
                 where id = ? and (status = 'ATTEMPTING' or (status = 'UNCERTAIN' and provider_message_id is null
                       and ? <> 'UNCERTAIN'))
                """, status.name(), providerMessageId, providerErrorCode, failureCode, now, status.name(), now,
                status.name(), now, deliveryId, status.name());
        if (updated == 0) return false;
        jdbc.update("""
                update whatsapp_attempts set finished_at = coalesce(finished_at, ?), outcome = ?,
                       provider_error_code = ?
                 where id = ? and delivery_id = ?
                """, now, attemptOutcome(status), providerErrorCode, attemptId, deliveryId);
        return true;
    }

    @Override
    public List<StaleDelivery> staleAttempts(Instant before) {
        return jdbc.query("""
                select d.id, a.id, d.space_id, d.summary_id from whatsapp_deliveries d
                  join whatsapp_attempts a on a.delivery_id = d.id and a.finished_at is null
                 where d.status = 'ATTEMPTING' and d.attempted_at < ?
                 order by d.attempted_at
                """, (rs, row) -> new StaleDelivery(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)), Timestamp.from(before));
    }

    @Override
    public Optional<DeliveryRef> lockByProviderMessageId(String providerMessageId) {
        return jdbc.query("""
                select id, space_id, summary_id, status from whatsapp_deliveries where provider_message_id = ?
                   for update
                """, (rs, row) -> new DeliveryRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), WhatsAppDeliveryStatus.valueOf(rs.getString(4))), providerMessageId)
                .stream().findFirst();
    }

    @Override
    public boolean anyAttempting() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from whatsapp_deliveries where status = 'ATTEMPTING')", Boolean.class));
    }

    @Override
    public boolean recordEvent(String providerMessageId, UUID deliveryId, WhatsAppDeliveryStatus status,
            Instant providerAt, String providerErrorCode, Instant receivedAt) {
        return jdbc.update("""
                insert into whatsapp_status_events(provider_message_id, status, delivery_id, provider_at,
                    provider_error_code, received_at)
                values (?, ?, ?, ?, ?, ?) on conflict do nothing
                """, providerMessageId, status.name(), deliveryId, Timestamp.from(providerAt), providerErrorCode,
                Timestamp.from(receivedAt)) == 1;
    }

    @Override
    public void confirm(UUID deliveryId, WhatsAppDeliveryStatus status, Instant providerAt, String providerErrorCode,
            boolean advance, Instant at) {
        var column = switch (status) {
            case SENT -> "sent_at";
            case DELIVERED -> "delivered_at";
            case READ -> "read_at";
            case FAILED -> "failed_at";
            default -> throw new IllegalArgumentException("Situação sem confirmação: " + status);
        };
        var failed = status == WhatsAppDeliveryStatus.FAILED;
        var sql = new StringBuilder("update whatsapp_deliveries set ").append(column).append(" = coalesce(")
                .append(column).append(", ?), updated_at = ?");
        var args = new ArrayList<Object>(List.of(Timestamp.from(providerAt), Timestamp.from(at)));
        if (advance) {
            sql.append(", status = ?");
            args.add(status.name());
        }
        if (failed) {
            sql.append(", provider_error_code = ?, failure_code = 'DELIVERY_FAILED'");
            args.add(providerErrorCode);
        }
        args.add(deliveryId);
        jdbc.update(sql.append(" where id = ?").toString(), args.toArray());
    }

    @Override
    public Optional<StoredDelivery> forSummary(UUID spaceId, UUID summaryId) {
        return one(SELECT + " where space_id = ? and summary_id = ?", spaceId, summaryId);
    }

    @Override
    public Optional<StoredDelivery> test(UUID spaceId, UUID key) {
        return one(SELECT + " where space_id = ? and kind = 'TEST' and test_key = ?", spaceId, key);
    }

    @Override
    public Optional<Instant> lastTestAt(UUID spaceId) {
        return jdbc.query("""
                select created_at from whatsapp_deliveries where space_id = ? and kind = 'TEST'
                 order by created_at desc limit 1
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), spaceId).stream().findFirst();
    }

    private Optional<StoredDelivery> one(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> row(rs), args).stream().findFirst();
    }

    private StoredDelivery row(ResultSet rs) throws SQLException {
        var id = rs.getObject(1, UUID.class);
        var attempts = jdbc.query("""
                select attempt_number, started_at, finished_at, outcome from whatsapp_attempts
                 where delivery_id = ? order by attempt_number
                """, (attempt, row) -> new StoredDelivery.Attempt(attempt.getInt(1), instant(attempt, 2),
                instant(attempt, 3), attempt.getString(4)), id);
        return new StoredDelivery(id, rs.getObject(2, UUID.class), WhatsAppSender.Kind.valueOf(rs.getString(3)),
                rs.getObject(4, UUID.class), WhatsAppDeliveryStatus.valueOf(rs.getString(5)), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, Integer.class), instant(rs, 11),
                instant(rs, 12), instant(rs, 13), instant(rs, 14), instant(rs, 15), instant(rs, 16), instant(rs, 17),
                attempts);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String attemptOutcome(WhatsAppDeliveryStatus status) {
        return switch (status) {
            case ACCEPTED -> "ACCEPTED";
            case REJECTED -> "REJECTED";
            case FAILED -> "FAILED";
            default -> "UNCERTAIN";
        };
    }
}
