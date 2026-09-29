package com.malyah.accountmanager.installments.infrastructure;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.expenses.application.InstallmentAdjuster;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.installments.application.InstallmentAdjustmentService;
import com.malyah.accountmanager.installments.application.InstallmentAdjustmentUseCase;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseService;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseUseCase;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.datasource.url")
class InstallmentsConfiguration {
    @Bean
    InstallmentPurchaseUseCase installmentPurchaseUseCase(JdbcTemplate jdbc, InstallmentExpenses expenses,
            AuthenticatedUserContextQuery context, CategoryRepository categories, FinancialMemberAccess members,
            Clock applicationClock, PlatformTransactionManager manager) {
        return new TransactionalInstallmentPurchaseUseCase(
                purchaseService(jdbc, expenses, context, categories, members, applicationClock),
                new TransactionTemplate(manager));
    }

    @Bean
    InstallmentAdjustmentUseCase installmentAdjustmentUseCase(JdbcTemplate jdbc, InstallmentExpenses expenses,
            InstallmentAdjuster adjuster, AuthenticatedUserContextQuery context, CategoryRepository categories,
            FinancialMemberAccess members, Clock applicationClock, PlatformTransactionManager manager) {
        var service = new InstallmentAdjustmentService(new JdbcInstallmentChangeRepository(jdbc),
                new JdbcInstallmentPurchaseRepository(jdbc),
                purchaseService(jdbc, expenses, context, categories, members, applicationClock), expenses, adjuster,
                context, members, applicationClock, UUID::randomUUID);
        return new TransactionalInstallmentAdjustmentUseCase(service, new TransactionTemplate(manager));
    }

    /**
     * Not a bean: it implements {@link InstallmentPurchaseUseCase} without transactions, so exposing it would make
     * the use case ambiguous. Each use case gets its own stateless instance inside its transaction.
     */
    private static InstallmentPurchaseService purchaseService(JdbcTemplate jdbc, InstallmentExpenses expenses,
            AuthenticatedUserContextQuery context, CategoryRepository categories, FinancialMemberAccess members,
            Clock clock) {
        return new InstallmentPurchaseService(new JdbcInstallmentPurchaseRepository(jdbc), expenses, context,
                categories, members, clock, UUID::randomUUID);
    }
}
