package com.malyah.accountmanager.recurrences.infrastructure;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.recurrences.application.RecurrenceService;
import com.malyah.accountmanager.recurrences.application.RecurrenceUseCase;
import com.malyah.accountmanager.recurrences.domain.RecurrenceCalendar;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="spring.datasource.url")
@EnableScheduling
class RecurrencesConfiguration {
    @Bean
    @ConditionalOnProperty(name="app.jobs.recurrence.enabled",matchIfMissing=true)
    JdbcRecurrenceGenerationJob recurrenceGenerationJob(JdbcTemplate jdbc,
            com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer materializer,
            Clock applicationClock,PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Value("${app.jobs.recurrence.lease-seconds:120}") long leaseSeconds,
            @org.springframework.beans.factory.annotation.Value("${app.jobs.recurrence.batch-size:25}") int batchSize) {
        return new JdbcRecurrenceGenerationJob(jdbc,new TransactionTemplate(manager),materializer,applicationClock,
                new RecurrenceCalendar(),java.time.Duration.ofSeconds(leaseSeconds),batchSize);
    }
    @Bean RecurrenceUseCase recurrenceUseCase(JdbcTemplate jdbc, AuthenticatedUserContextQuery context,
            CategoryRepository categories, FinancialMemberAccess members, Clock applicationClock,
            PlatformTransactionManager manager,
            com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer materializer) {
        var service=new RecurrenceService(new JdbcRecurrenceRepository(jdbc),context,categories,members,
                applicationClock,UUID::randomUUID,new RecurrenceCalendar(),materializer);
        return new TransactionalRecurrenceUseCase(service,new TransactionTemplate(manager));
    }
}
