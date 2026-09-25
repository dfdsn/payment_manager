package com.malyah.accountmanager.expenses.infrastructure;

import java.time.Clock;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseService;
import com.malyah.accountmanager.expenses.application.ExpenseUseCase;
import com.malyah.accountmanager.expenses.application.port.ExpenseIdentifierGenerator;
import com.malyah.accountmanager.expenses.application.port.ExpenseRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.datasource.url")
class ExpensesConfiguration {
    @Bean
    ExpenseRepository expenseRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcExpenseRepository(jdbcTemplate);
    }

    @Bean
    ExpenseIdentifierGenerator expenseIdentifierGenerator() {
        return UUID::randomUUID;
    }

    @Bean
    ExpenseUseCase expenseUseCase(
            ExpenseRepository repository,
            AuthenticatedUserContextQuery contextQuery,
            ExpenseIdentifierGenerator identifiers,
            Clock applicationClock,
            PlatformTransactionManager transactionManager,
            com.malyah.accountmanager.identity.application.FinancialMemberAccess memberAccess) {
        var service = new ExpenseService(repository, contextQuery, identifiers, applicationClock, memberAccess);
        return new TransactionalExpenseUseCase(service, new TransactionTemplate(transactionManager));
    }
}
