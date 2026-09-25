package com.malyah.accountmanager.identity.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.identity.application.ManagedMember;
import com.malyah.accountmanager.identity.application.MembershipManagementService;
import com.malyah.accountmanager.identity.application.MembershipManagementUseCase;

final class TransactionalMembershipManagementUseCase implements MembershipManagementUseCase {
    private final MembershipManagementService delegate;
    private final TransactionTemplate transactions;

    TransactionalMembershipManagementUseCase(MembershipManagementService delegate, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.transactions = transactions;
    }

    @Override
    public List<ManagedMember> members(String actorEmail) {
        return transactions.execute(status -> delegate.members(actorEmail));
    }

    @Override
    public void remove(String actorEmail, UUID memberUserId) {
        transactions.executeWithoutResult(status -> delegate.remove(actorEmail, memberUserId));
    }

    @Override
    public void leave(String actorEmail) {
        transactions.executeWithoutResult(status -> delegate.leave(actorEmail));
    }

    @Override
    public void transferAdministration(String actorEmail, UUID newAdministratorUserId) {
        transactions.executeWithoutResult(status -> delegate.transferAdministration(actorEmail, newAdministratorUserId));
    }
}
