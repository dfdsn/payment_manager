package com.malyah.accountmanager.notifications.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.notifications.application.SpaceReminderSchedule;
import com.malyah.accountmanager.notifications.application.StoredSummary;
import com.malyah.accountmanager.notifications.application.port.ReminderSummaryRepository;
import com.malyah.accountmanager.notifications.domain.ReminderItem;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;
import com.malyah.accountmanager.notifications.domain.ReminderSlot;
import com.malyah.accountmanager.notifications.domain.ReminderSummary;

/**
 * H08.2 persistence. Spaces, their time zone and the active administrator come from a read projection of the
 * identity tables, never written here. A slot is claimed with {@code on conflict do nothing} on its primary key, so
 * concurrent workers and re-executions record it once.
 */
public final class JdbcReminderSummaryRepository implements ReminderSummaryRepository {
    private final JdbcTemplate jdbc;

    public JdbcReminderSummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SpaceReminderSchedule> spaces() {
        return jdbc.query("""
                select s.id, s.time_zone, coalesce(r.first_time, time '09:00'), coalesce(r.second_time, time '18:00')
                  from family_spaces s left join reminder_settings r on r.space_id = s.id
                 order by s.id
                """, (rs, row) -> new SpaceReminderSchedule(rs.getObject(1, UUID.class), ZoneId.of(rs.getString(2)),
                new ReminderSchedule(rs.getObject(3, LocalTime.class), rs.getObject(4, LocalTime.class))));
    }

    @Override
    public boolean slotProcessed(UUID spaceId, LocalDate date, ReminderSlot slot) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from reminder_slot_runs where space_id = ? and local_date = ? and slot = ?)
                """, Boolean.class, spaceId, date, slot.name()));
    }

    @Override
    public boolean claimSlot(UUID spaceId, LocalDate date, ReminderSlot slot, LocalTime scheduledTime, String outcome,
            Instant at) {
        return jdbc.update("""
                insert into reminder_slot_runs(space_id, local_date, slot, scheduled_time, outcome, processed_at)
                values (?, ?, ?, ?, ?, ?) on conflict do nothing
                """, spaceId, date, slot.name(), scheduledTime, outcome, Timestamp.from(at)) == 1;
    }

    @Override
    public void markGenerated(UUID spaceId, LocalDate date, ReminderSlot slot, UUID summaryId) {
        jdbc.update("""
                update reminder_slot_runs set outcome = 'GENERATED', summary_id = ?
                 where space_id = ? and local_date = ? and slot = ?
                """, summaryId, spaceId, date, slot.name());
    }

    @Override
    public void insert(StoredSummary stored) {
        var summary = stored.summary();
        jdbc.update("""
                insert into reminder_summaries(id, space_id, local_date, slot, scheduled_time, time_zone, scheduled_at,
                    generated_at, item_count, total_amount, estimated_count, estimated_amount, overdue_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stored.id(), stored.spaceId(), summary.date(), summary.slot().name(), stored.scheduledTime(),
                stored.timeZone(), Timestamp.from(stored.scheduledAt()), Timestamp.from(stored.generatedAt()),
                summary.count(), summary.total(), summary.estimatedCount(), summary.estimatedTotal(),
                summary.overdueCount());
        var rows = new ArrayList<Object[]>();
        var position = 1;
        for (var item : summary.items())
            rows.add(new Object[] {stored.id(), position++, item.expenseId(), item.recurrenceId(),
                    item.scheduledDueDate(), item.description(), item.amount(), item.dueDate(), item.origin(),
                    item.estimated(), item.overdue(summary.date()), item.installmentNumber(), item.installmentCount()});
        jdbc.batchUpdate("""
                insert into reminder_summary_items(summary_id, position, expense_id, recurrence_id, scheduled_due_date,
                    description, amount, due_date, origin, estimated, overdue, installment_number, installment_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        for (var channel : stored.channels())
            jdbc.update("""
                    insert into reminder_summary_channels(summary_id, channel, status, skip_reason, recipient_user_id,
                        created_at) values (?, ?, ?, ?, ?, ?)
                    """, stored.id(), channel.channel().name(), channel.status().name(), channel.skipReason(),
                    channel.recipientUserId(), Timestamp.from(stored.generatedAt()));
    }

    @Override
    public Optional<StoredSummary> find(UUID spaceId, UUID summaryId) {
        var heads = jdbc.query("""
                select local_date, slot, scheduled_time, time_zone, scheduled_at, generated_at, total_amount,
                       estimated_count, estimated_amount, overdue_count
                  from reminder_summaries where id = ? and space_id = ?
                """, (rs, row) -> new Head(rs.getObject(1, LocalDate.class), ReminderSlot.valueOf(rs.getString(2)),
                rs.getObject(3, LocalTime.class), rs.getString(4), rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6).toInstant(), rs.getBigDecimal(7), rs.getInt(8), rs.getBigDecimal(9), rs.getInt(10)),
                summaryId, spaceId);
        if (heads.isEmpty()) return Optional.empty();
        var head = heads.getFirst();
        var items = jdbc.query("""
                select expense_id, recurrence_id, scheduled_due_date, description, amount, due_date, estimated, origin,
                       installment_number, installment_count
                  from reminder_summary_items where summary_id = ? order by position
                """, (rs, row) -> new ReminderItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, LocalDate.class), rs.getString(4), rs.getBigDecimal(5), rs.getObject(6, LocalDate.class),
                rs.getBoolean(7), rs.getString(8), rs.getObject(9, Integer.class), rs.getObject(10, Integer.class)),
                summaryId);
        var channels = jdbc.query("""
                select channel, status, skip_reason, recipient_user_id from reminder_summary_channels
                 where summary_id = ? order by channel
                """, (rs, row) -> new StoredSummary.Channel(StoredSummary.ChannelType.valueOf(rs.getString(1)),
                StoredSummary.ChannelStatus.valueOf(rs.getString(2)), rs.getString(3), rs.getObject(4, UUID.class)),
                summaryId);
        var summary = new ReminderSummary(head.date(), head.slot(), items, head.total(), head.estimatedCount(),
                head.estimatedTotal(), head.overdueCount());
        return Optional.of(new StoredSummary(summaryId, spaceId, head.time(), head.zone(), head.scheduledAt(),
                head.generatedAt(), summary, channels));
    }

    @Override
    public Optional<UUID> activeAdministrator(UUID spaceId) {
        return jdbc.query("""
                select user_id from space_memberships where space_id = ? and role = 'ADMINISTRATOR' and active = true
                """, (rs, row) -> rs.getObject(1, UUID.class), spaceId).stream().findFirst();
    }

    private record Head(LocalDate date, ReminderSlot slot, LocalTime time, String zone, Instant scheduledAt,
            Instant generatedAt, BigDecimal total, int estimatedCount, BigDecimal estimatedTotal, int overdueCount) { }
}
