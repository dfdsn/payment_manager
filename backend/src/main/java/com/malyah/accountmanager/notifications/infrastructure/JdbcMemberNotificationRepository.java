package com.malyah.accountmanager.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.notifications.application.StoredNotification;
import com.malyah.accountmanager.notifications.application.port.MemberNotificationRepository;
import com.malyah.accountmanager.notifications.domain.WhatsAppFailureReason;

/**
 * H08.3 persistence. Recipients come from a read projection of the active memberships (never written here). Every
 * statement is scoped by space and recipient, and an administrative notification only matches when the caller
 * states that the reader is the administrator now. The unique key (summary, recipient, type) keeps re-executions
 * and concurrent workers from duplicating a notification; read and dismissed keep their first instant.
 */
public final class JdbcMemberNotificationRepository implements MemberNotificationRepository {
    private static final String SELECT = """
            select n.id, n.type, n.failure_code, n.created_at, n.read_at, n.dismissed_at, s.id, s.local_date, s.slot,
                   s.scheduled_time, s.time_zone, s.item_count, s.total_amount, s.estimated_count, s.estimated_amount,
                   s.overdue_count
              from member_notifications n join reminder_summaries s on s.id = n.summary_id
            """;
    private static final String VISIBLE = " n.space_id = ? and n.recipient_user_id = ? and (n.audience = 'MEMBER' or ?)";

    private final JdbcTemplate jdbc;

    public JdbcMemberNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deliverSummary(UUID spaceId, UUID summaryId, Instant at) {
        var now = Timestamp.from(at);
        return jdbc.update("""
                insert into member_notifications(id, space_id, recipient_user_id, type, audience, summary_id,
                    created_at, updated_at)
                select gen_random_uuid(), m.space_id, m.user_id, 'REMINDER_SUMMARY', 'MEMBER', s.id, ?, ?
                  from space_memberships m join reminder_summaries s on s.space_id = m.space_id
                 where m.space_id = ? and m.active = true and s.id = ?
                 order by m.user_id
                on conflict (summary_id, recipient_user_id, type) do nothing
                """, now, now, spaceId, summaryId);
    }

    @Override
    public boolean recordWhatsAppFailure(UUID spaceId, UUID summaryId, WhatsAppFailureReason reason, Instant at) {
        var now = Timestamp.from(at);
        return jdbc.update("""
                insert into member_notifications(id, space_id, recipient_user_id, type, audience, summary_id,
                    failure_code, created_at, updated_at)
                select gen_random_uuid(), m.space_id, m.user_id, 'WHATSAPP_DELIVERY_FAILURE', 'ADMINISTRATOR', s.id,
                       ?, ?, ?
                  from space_memberships m join reminder_summaries s on s.space_id = m.space_id
                 where m.space_id = ? and m.active = true and m.role = 'ADMINISTRATOR' and s.id = ?
                on conflict (summary_id, recipient_user_id, type)
                do update set failure_code = excluded.failure_code, updated_at = excluded.updated_at
                """, reason.name(), now, now, spaceId, summaryId) > 0;
    }

    @Override
    public List<StoredNotification> page(UUID spaceId, UUID recipientId, boolean administrator, boolean dismissed,
            long offset, int limit) {
        return jdbc.query(SELECT + " where" + VISIBLE + " and (n.dismissed_at is not null) = ?"
                + " order by n.created_at desc, n.id desc offset ? limit ?", JdbcMemberNotificationRepository::row,
                spaceId, recipientId, administrator, dismissed, offset, limit);
    }

    @Override
    public long count(UUID spaceId, UUID recipientId, boolean administrator, boolean dismissed) {
        return jdbc.queryForObject("select count(*) from member_notifications n where" + VISIBLE
                + " and (n.dismissed_at is not null) = ?", Long.class, spaceId, recipientId, administrator, dismissed);
    }

    @Override
    public long unread(UUID spaceId, UUID recipientId, boolean administrator) {
        return jdbc.queryForObject("select count(*) from member_notifications n where" + VISIBLE
                + " and n.read_at is null", Long.class, spaceId, recipientId, administrator);
    }

    @Override
    public Optional<StoredNotification> markRead(UUID spaceId, UUID recipientId, boolean administrator, UUID id,
            Instant at) {
        return mark("read_at = coalesce(n.read_at, ?)", spaceId, recipientId, administrator, id, at);
    }

    @Override
    public Optional<StoredNotification> markDismissed(UUID spaceId, UUID recipientId, boolean administrator, UUID id,
            Instant at) {
        return mark("read_at = coalesce(n.read_at, ?), dismissed_at = coalesce(n.dismissed_at, ?)", spaceId,
                recipientId, administrator, id, at);
    }

    private Optional<StoredNotification> mark(String assignments, UUID spaceId, UUID recipientId,
            boolean administrator, UUID id, Instant at) {
        var now = Timestamp.from(at);
        var values = assignments.contains("dismissed_at")
                ? new Object[] {now, now, now, spaceId, recipientId, administrator, id}
                : new Object[] {now, now, spaceId, recipientId, administrator, id};
        var changed = jdbc.update("update member_notifications n set " + assignments + ", updated_at = ? where"
                + VISIBLE + " and n.id = ?", values);
        if (changed == 0) return Optional.empty();
        return jdbc.query(SELECT + " where n.id = ?", JdbcMemberNotificationRepository::row, id).stream().findFirst();
    }

    private static StoredNotification row(ResultSet rs, int row) throws SQLException {
        return new StoredNotification(rs.getObject(1, UUID.class),
                StoredNotification.Type.valueOf(rs.getString(2)), rs.getString(3), instant(rs, 4), instant(rs, 5),
                instant(rs, 6), new StoredNotification.SummaryHead(rs.getObject(7, UUID.class),
                        rs.getObject(8, LocalDate.class), rs.getString(9), rs.getObject(10, LocalTime.class),
                        rs.getString(11), rs.getInt(12), rs.getBigDecimal(13), rs.getInt(14), rs.getBigDecimal(15),
                        rs.getInt(16)));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
