package com.malyah.accountmanager.expenses.infrastructure;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.expenses.domain.VariableEstimateReference.EstimateBase;

/** Read projection of the estimates explicitly set for a variable recurrence, one per segment (H04.5). */
final class RecurrenceEstimateBases {
    private RecurrenceEstimateBases() { }

    static List<EstimateBase> load(JdbcTemplate jdbc, UUID recurrenceId, UUID spaceId) {
        return jdbc.query("""
                select effective_month, amount from recurrence_segments
                 where recurrence_id=? and space_id=? and estimate_reset order by effective_month
                """, (rs, row) -> new EstimateBase(YearMonth.from(rs.getObject(1, LocalDate.class)), rs.getBigDecimal(2)),
                recurrenceId, spaceId);
    }
}
