package com.malyah.accountmanager.reporting.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.reporting.application.ClosingClaim;
import com.malyah.accountmanager.reporting.application.ClosingHead;
import com.malyah.accountmanager.reporting.application.ClosingIdempotencyConflictException;
import com.malyah.accountmanager.reporting.application.ClosingVersion;
import com.malyah.accountmanager.reporting.application.StoredClosing;
import com.malyah.accountmanager.reporting.application.VersionEntry;
import com.malyah.accountmanager.reporting.application.port.MonthClosingRepository;
import com.malyah.accountmanager.reporting.domain.ClosingCategory;
import com.malyah.accountmanager.reporting.domain.ClosingLine;
import com.malyah.accountmanager.reporting.domain.DueIndicators;
import com.malyah.accountmanager.reporting.domain.Situation;

/**
 * Month closings in PostgreSQL. The unique (space, month) header serializes concurrent closings; versions,
 * categories, lines and events are append-only and protected by triggers against updates and deletes.
 */
public final class JdbcMonthClosingRepository implements MonthClosingRepository {
    private final JdbcTemplate jdbc;

    public JdbcMonthClosingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ClosingClaim claim(UUID spaceId, UUID actorId, String operation, UUID key, String requestHash,
            Instant at) {
        int claimed = jdbc.update("""
                insert into month_closing_requests(space_id, actor_user_id, operation, idempotency_key, request_hash,
                    created_at)
                values (?, ?, ?, ?, ?, ?) on conflict do nothing
                """, spaceId, actorId, operation, key, requestHash, Timestamp.from(at));
        if (claimed == 1) return new ClosingClaim(false, null);
        var existing = jdbc.queryForObject("""
                select request_hash, version_id from month_closing_requests
                 where space_id = ? and actor_user_id = ? and operation = ? and idempotency_key = ? for update
                """, (rs, row) -> new ClosingClaim(requestHash.equals(rs.getString(1)), rs.getObject(2, UUID.class)),
                spaceId, actorId, operation, key);
        if (existing == null || !existing.replayed() || existing.versionId() == null)
            throw new ClosingIdempotencyConflictException();
        return existing;
    }

    @Override
    public void complete(UUID spaceId, UUID actorId, String operation, UUID key, UUID versionId, Instant at) {
        jdbc.update("""
                update month_closing_requests set version_id = ?, completed_at = ?
                 where space_id = ? and actor_user_id = ? and operation = ? and idempotency_key = ?
                """, versionId, Timestamp.from(at), spaceId, actorId, operation, key);
    }

    @Override
    public boolean create(UUID closingId, UUID spaceId, YearMonth month, Instant at) {
        return jdbc.update("""
                insert into month_closings(id, space_id, month, current_version, created_at, updated_at)
                values (?, ?, ?, 1, ?, ?) on conflict (space_id, month) do nothing
                """, closingId, spaceId, month.atDay(1), Timestamp.from(at), Timestamp.from(at)) == 1;
    }

    @Override
    public List<ClosingHead> list(UUID spaceId, Year year) {
        return jdbc.query("""
                select c.month, c.current_version, v.author_display_name, v.created_at, v.content_digest
                  from month_closings c
                  join month_closing_versions v on v.closing_id = c.id and v.version_number = c.current_version
                 where c.space_id = ? and c.month between ? and ?
                 order by c.month
                """, (rs, row) -> new ClosingHead(YearMonth.from(rs.getObject(1, LocalDate.class)), rs.getInt(2),
                rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)), spaceId, year.atDay(1),
                year.atMonth(12).atDay(1));
    }

    @Override
    public Optional<StoredClosing> find(UUID spaceId, YearMonth month) {
        return jdbc.query("""
                select id, month, current_version, created_at, updated_at from month_closings
                 where space_id = ? and month = ?
                """, (rs, row) -> new StoredClosing(rs.getObject(1, UUID.class),
                YearMonth.from(rs.getObject(2, LocalDate.class)), rs.getInt(3), rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5).toInstant()), spaceId, month.atDay(1)).stream().findFirst();
    }

    @Override
    public Optional<StoredClosing> lock(UUID spaceId, YearMonth month) {
        return jdbc.query("""
                select id, month, current_version, created_at, updated_at from month_closings
                 where space_id = ? and month = ? for update
                """, (rs, row) -> new StoredClosing(rs.getObject(1, UUID.class),
                YearMonth.from(rs.getObject(2, LocalDate.class)), rs.getInt(3), rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5).toInstant()), spaceId, month.atDay(1)).stream().findFirst();
    }

    @Override
    public boolean advance(UUID spaceId, UUID closingId, int fromVersion, int toVersion, Instant at) {
        return jdbc.update("""
                update month_closings set current_version = ?, updated_at = ?
                 where space_id = ? and id = ? and current_version = ?
                   and exists (select 1 from month_closing_versions
                                where closing_id = ? and space_id = ? and version_number = ?)
                """, toVersion, Timestamp.from(at), spaceId, closingId, fromVersion, closingId, spaceId,
                toVersion) == 1;
    }

    @Override
    public List<VersionEntry> versions(UUID spaceId, UUID closingId) {
        return jdbc.query("""
                select version_number, author_user_id, author_display_name, created_at, business_date,
                       pending_acknowledged, planned_count, planned_total, planned_estimated, paid_count, paid_total,
                       pending_count, pending_total, pending_estimated, overdue_count, overdue_total,
                       overdue_estimated, adjustment_increase, adjustment_discount
                  from month_closing_versions where space_id = ? and closing_id = ?
                 order by version_number
                """, (rs, row) -> new VersionEntry(rs.getInt(1), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getObject(5, LocalDate.class), rs.getBoolean(6),
                new DueIndicators(rs.getLong(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getLong(10),
                        rs.getBigDecimal(11), rs.getLong(12), rs.getBigDecimal(13), rs.getBigDecimal(14),
                        rs.getLong(15), rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getBigDecimal(18),
                        rs.getBigDecimal(19))), spaceId, closingId);
    }

    @Override
    public void insertVersion(UUID spaceId, ClosingVersion version) {
        var totals = version.indicators();
        jdbc.update("""
                insert into month_closing_versions(id, closing_id, space_id, month, version_number, author_user_id,
                    author_display_name, created_at, business_date, time_zone, pending_acknowledged, content_digest,
                    planned_count, planned_total, planned_estimated, paid_count, paid_total, pending_count,
                    pending_total, pending_estimated, overdue_count, overdue_total, overdue_estimated,
                    adjustment_increase, adjustment_discount)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, version.id(), version.closingId(), spaceId, version.month().atDay(1), version.number(),
                version.authorUserId(), version.authorDisplayName(), Timestamp.from(version.createdAt()),
                version.businessDate(), version.timeZone(), version.pendingAcknowledged(), version.contentDigest(),
                totals.plannedCount(), totals.plannedTotal(), totals.plannedEstimated(), totals.paidCount(),
                totals.paidTotal(), totals.pendingCount(), totals.pendingTotal(), totals.pendingEstimated(),
                totals.overdueCount(), totals.overdueTotal(), totals.overdueEstimated(), totals.adjustmentIncrease(),
                totals.adjustmentDiscount());
        var categories = version.categories();
        jdbc.batchUpdate("""
                insert into month_closing_categories(version_id, position, category_id, category_name, entry_count,
                    planned_total, planned_estimated, paid_total, pending_count, pending_total)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, categories, 200, (statement, category) -> {
                    statement.setObject(1, version.id());
                    statement.setInt(2, categories.indexOf(category));
                    statement.setObject(3, category.categoryId());
                    statement.setString(4, category.categoryName());
                    statement.setLong(5, category.count());
                    statement.setBigDecimal(6, category.plannedTotal());
                    statement.setBigDecimal(7, category.plannedEstimated());
                    statement.setBigDecimal(8, category.paidTotal());
                    statement.setLong(9, category.pendingCount());
                    statement.setBigDecimal(10, category.pendingTotal());
                });
        jdbc.batchUpdate("""
                insert into month_closing_lines(version_id, expense_id, description, origin, installment_number,
                    installment_count, reference_date, due_date_informed, status, charge_amount, charge_confirmed,
                    paid_amount, overdue, category_id, category_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, version.lines(), 500, (statement, line) -> {
                    statement.setObject(1, version.id());
                    statement.setObject(2, line.expenseId());
                    statement.setString(3, line.description());
                    statement.setString(4, line.origin());
                    statement.setObject(5, line.installmentNumber());
                    statement.setObject(6, line.installmentCount());
                    statement.setObject(7, line.referenceDate());
                    statement.setBoolean(8, line.dueDateInformed());
                    statement.setString(9, line.situation().name());
                    statement.setBigDecimal(10, line.chargeAmount());
                    statement.setBoolean(11, !line.estimated());
                    statement.setBigDecimal(12, line.paidAmount());
                    statement.setBoolean(13, line.overdue());
                    statement.setObject(14, line.categoryId());
                    statement.setString(15, line.categoryName());
                });
    }

    @Override
    public void recordEvent(UUID closingId, UUID spaceId, int versionNumber, String eventType, UUID actorId,
            Instant at) {
        jdbc.update("""
                insert into month_closing_events(id, closing_id, space_id, version_number, event_type, actor_user_id,
                    occurred_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), closingId, spaceId, versionNumber, eventType, actorId, Timestamp.from(at));
    }

    @Override
    public Optional<ClosingVersion> version(UUID spaceId, UUID closingId, int number) {
        return load(" where v.space_id = ? and v.closing_id = ? and v.version_number = ?", spaceId, closingId,
                number);
    }

    @Override
    public Optional<ClosingVersion> versionById(UUID spaceId, UUID versionId) {
        return load(" where v.space_id = ? and v.id = ?", spaceId, versionId);
    }

    private Optional<ClosingVersion> load(String where, Object... parameters) {
        return jdbc.query("""
                select v.id, v.closing_id, v.month, v.version_number, v.author_user_id, v.author_display_name,
                       v.created_at, v.business_date, v.time_zone, v.pending_acknowledged, v.content_digest,
                       v.planned_count, v.planned_total, v.planned_estimated, v.paid_count, v.paid_total,
                       v.pending_count, v.pending_total, v.pending_estimated, v.overdue_count, v.overdue_total,
                       v.overdue_estimated, v.adjustment_increase, v.adjustment_discount
                  from month_closing_versions v
                """ + where, (rs, row) -> version(rs), parameters).stream().findFirst();
    }

    private ClosingVersion version(ResultSet rs) throws SQLException {
        var id = rs.getObject(1, UUID.class);
        var indicators = new DueIndicators(rs.getLong(12), rs.getBigDecimal(13), rs.getBigDecimal(14),
                rs.getLong(15), rs.getBigDecimal(16), rs.getLong(17), rs.getBigDecimal(18), rs.getBigDecimal(19),
                rs.getLong(20), rs.getBigDecimal(21), rs.getBigDecimal(22), rs.getBigDecimal(23),
                rs.getBigDecimal(24));
        return new ClosingVersion(id, rs.getObject(2, UUID.class), YearMonth.from(rs.getObject(3, LocalDate.class)),
                rs.getInt(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getTimestamp(7).toInstant(),
                rs.getObject(8, LocalDate.class), rs.getString(9), rs.getBoolean(10), rs.getString(11), indicators,
                categories(id), lines(id));
    }

    private List<ClosingCategory> categories(UUID versionId) {
        return jdbc.query("""
                select category_id, category_name, entry_count, planned_total, planned_estimated, paid_total,
                       pending_count, pending_total
                  from month_closing_categories where version_id = ? order by position
                """, (rs, row) -> new ClosingCategory(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getLong(7), rs.getBigDecimal(8)),
                versionId);
    }

    private List<ClosingLine> lines(UUID versionId) {
        return jdbc.query("""
                select expense_id, description, origin, installment_number, installment_count, reference_date,
                       due_date_informed, status, charge_amount, charge_confirmed, paid_amount, overdue, category_id,
                       category_name
                  from month_closing_lines where version_id = ?
                 order by reference_date, lower(description), expense_id
                """, (rs, row) -> new ClosingLine(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                (Integer) rs.getObject(4, Integer.class), (Integer) rs.getObject(5, Integer.class),
                rs.getObject(6, LocalDate.class), rs.getBoolean(7), Situation.valueOf(rs.getString(8)),
                rs.getBigDecimal(9), !rs.getBoolean(10), rs.getBigDecimal(11), rs.getBoolean(12),
                rs.getObject(13, UUID.class), rs.getString(14)), versionId);
    }
}
