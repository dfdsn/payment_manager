package com.malyah.accountmanager.recurrences.infrastructure;

import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.ChargeConfirmationUseCase;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.recurrences.application.RecurrenceService;
import com.malyah.accountmanager.recurrences.domain.RecurrenceCalendar;

/** Test-only access to the package-private adapters, for ITs of other modules that need real recurrences. */
public final class RecurrenceTestFixtures {
    private RecurrenceTestFixtures() {
    }

    public static RecurrenceService service(JdbcTemplate jdbc, AuthenticatedUserContextQuery context,
            CategoryRepository categories, FinancialMemberAccess members, Clock clock,
            RecurringExpenseMaterializer materializer, ChargeConfirmationUseCase confirmation) {
        return new RecurrenceService(new JdbcRecurrenceRepository(jdbc), context, categories, members, clock,
                UUID::randomUUID, new RecurrenceCalendar(), materializer, confirmation);
    }
}
