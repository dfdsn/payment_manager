package com.malyah.accountmanager.reporting.infrastructure;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.expenses.application.ExpensePlanningQueries;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.reporting.application.PlanningService;
import com.malyah.accountmanager.reporting.application.PlanningUseCase;
import com.malyah.accountmanager.reporting.application.ReportingService;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.datasource.url")
class ReportingConfiguration {
    /** Only the transactional use case is a bean, so injection by type stays unambiguous. */
    @Bean
    ReportingUseCase reportingUseCase(ExpenseReportQueries expenses, AuthenticatedUserContextQuery contexts,
            Clock applicationClock, PlatformTransactionManager transactionManager) {
        return new TransactionalReportingUseCase(new ReportingService(expenses, contexts, applicationClock),
                new TransactionTemplate(transactionManager));
    }

    @Bean
    PlanningUseCase planningUseCase(ExpensePlanningQueries expenses, RecurrenceForecastQueries forecasts,
            AuthenticatedUserContextQuery contexts, Clock applicationClock,
            PlatformTransactionManager transactionManager) {
        return new TransactionalPlanningUseCase(new PlanningService(expenses, forecasts, contexts, applicationClock),
                new TransactionTemplate(transactionManager));
    }
}
