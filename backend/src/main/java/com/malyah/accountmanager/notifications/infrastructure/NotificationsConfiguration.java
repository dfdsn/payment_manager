package com.malyah.accountmanager.notifications.infrastructure;

import java.time.Clock;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseReminderQueries;
import com.malyah.accountmanager.identity.application.AdministrationTransferHandler;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.notifications.application.ReminderSettingsService;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryService;
import com.malyah.accountmanager.notifications.application.ReminderSummaryUseCase;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.recurrences.application.UpcomingOccurrenceGeneration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.datasource.url")
class NotificationsConfiguration {
    @Bean
    WhatsAppProviderStatus whatsAppProviderStatus() {
        return new UnavailableWhatsAppProvider();
    }

    /** Only the transactional use case is a bean of this type, so injection by type stays unambiguous. */
    @Bean
    ReminderSettingsUseCase reminderSettingsUseCase(JdbcTemplate jdbcTemplate, AuthenticatedUserContextQuery contexts,
            FinancialMemberAccess members, WhatsAppProviderStatus provider, Clock applicationClock,
            PlatformTransactionManager transactionManager) {
        return new TransactionalReminderSettingsUseCase(service(jdbcTemplate, contexts, members, provider,
                applicationClock), new TransactionTemplate(transactionManager));
    }

    /** Runs inside the transfer transaction of the identity module (RF-ACC-10). */
    @Bean
    AdministrationTransferHandler reminderConsentTransferHandler(JdbcTemplate jdbcTemplate,
            AuthenticatedUserContextQuery contexts, FinancialMemberAccess members, WhatsAppProviderStatus provider,
            Clock applicationClock) {
        return service(jdbcTemplate, contexts, members, provider, applicationClock)::afterAdministrationTransferred;
    }

    /** H08.2 reads for both members (simulation and the target of the summary link). */
    @Bean
    ReminderSummaryUseCase reminderSummaryUseCase(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            WhatsAppProviderStatus provider, AuthenticatedUserContextQuery contexts, Clock applicationClock,
            @Value("${app.public-base-url}") String publicBaseUrl, PlatformTransactionManager transactionManager) {
        return new TransactionalReminderSummaryUseCase(summaries(jdbcTemplate, expenses, forecasts, generation,
                provider, contexts, applicationClock, publicBaseUrl), new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(name = "app.jobs.reminders.enabled", matchIfMissing = true)
    ReminderSummaryJob reminderSummaryJob(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            WhatsAppProviderStatus provider, AuthenticatedUserContextQuery contexts, Clock applicationClock,
            @Value("${app.public-base-url}") String publicBaseUrl, PlatformTransactionManager transactionManager) {
        return new ReminderSummaryJob(summaries(jdbcTemplate, expenses, forecasts, generation, provider, contexts,
                applicationClock, publicBaseUrl), new TransactionTemplate(transactionManager), applicationClock);
    }

    /**
     * The service itself is not a bean, so {@link ReminderSummaryUseCase} has a single candidate. Without the
     * recurrence job (disabled by configuration) nothing is materialized ahead of time and the occurrences of the
     * window stay in the summary as forecasts.
     */
    private static ReminderSummaryService summaries(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            WhatsAppProviderStatus provider, AuthenticatedUserContextQuery contexts, Clock clock,
            String publicBaseUrl) {
        return new ReminderSummaryService(new JdbcReminderSummaryRepository(jdbcTemplate),
                new JdbcReminderSettingsRepository(jdbcTemplate), expenses, forecasts,
                generation.getIfAvailable(() -> (spaceId, dueThrough) -> 0), provider, contexts, clock,
                UUID::randomUUID, publicBaseUrl);
    }

    private static ReminderSettingsService service(JdbcTemplate jdbcTemplate, AuthenticatedUserContextQuery contexts,
            FinancialMemberAccess members, WhatsAppProviderStatus provider, Clock clock) {
        return new ReminderSettingsService(new JdbcReminderSettingsRepository(jdbcTemplate), contexts, members,
                provider, clock, UUID::randomUUID);
    }
}
