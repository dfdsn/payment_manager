package com.malyah.accountmanager.installments.infrastructure;

import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseService;

/** Test-only access to the package-private adapters, for ITs of other modules that need real installments. */
public final class InstallmentTestFixtures {
    private InstallmentTestFixtures() {
    }

    public static InstallmentPurchaseService purchases(JdbcTemplate jdbc, InstallmentExpenses expenses,
            AuthenticatedUserContextQuery context, CategoryRepository categories, FinancialMemberAccess members,
            Clock clock) {
        return new InstallmentPurchaseService(new JdbcInstallmentPurchaseRepository(jdbc), expenses, context,
                categories, members, clock, UUID::randomUUID);
    }
}
