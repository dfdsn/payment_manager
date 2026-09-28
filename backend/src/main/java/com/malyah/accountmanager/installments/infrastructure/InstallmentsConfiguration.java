package com.malyah.accountmanager.installments.infrastructure;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseService;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseUseCase;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.datasource.url")
class InstallmentsConfiguration {
    @Bean
    InstallmentPurchaseUseCase installmentPurchaseUseCase(JdbcTemplate jdbc, InstallmentExpenses expenses,
            AuthenticatedUserContextQuery context, CategoryRepository categories, FinancialMemberAccess members,
            Clock applicationClock, PlatformTransactionManager manager) {
        var service = new InstallmentPurchaseService(new JdbcInstallmentPurchaseRepository(jdbc), expenses, context,
                categories, members, applicationClock, UUID::randomUUID);
        return new TransactionalInstallmentPurchaseUseCase(service, new TransactionTemplate(manager));
    }
}
