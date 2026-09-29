package com.malyah.accountmanager.identity.infrastructure;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.identity.application.AdministrationTransferHandler;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.MembershipDepartureHandler;
import com.malyah.accountmanager.identity.application.MembershipManagementService;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;

/** Real identity adapters for integration tests of other modules (the adapters themselves are package-private). */
public final class IdentityTestFixtures {
    private IdentityTestFixtures() {
    }

    public static AuthenticatedUserContextQuery contexts(JdbcTemplate jdbc) {
        return new AuthenticatedUserContextService(new JdbcAuthenticatedUserContextRepository(jdbc));
    }

    /** The production membership use case: one transaction per operation, with the given cross-module handlers. */
    public static MembershipManagementUseCase memberships(JdbcTemplate jdbc, TransactionTemplate transactions,
            Clock clock, List<MembershipDepartureHandler> departures, List<AdministrationTransferHandler> transfers) {
        return new TransactionalMembershipManagementUseCase(new MembershipManagementService(
                new JdbcMembershipRepository(jdbc), new JdbcSessionRevoker(jdbc), UUID::randomUUID, clock, departures,
                transfers), transactions);
    }
}
