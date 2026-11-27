package com.malyah.accountmanager.notifications.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.expenses.application.ExpenseReminderQueries;
import com.malyah.accountmanager.identity.application.AdministrationTransferHandler;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.notifications.application.MemberNotificationService;
import com.malyah.accountmanager.notifications.application.MemberNotificationUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSettingsService;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.ReminderSummaryService;
import com.malyah.accountmanager.notifications.application.ReminderSummaryUseCase;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryService;
import com.malyah.accountmanager.notifications.application.WhatsAppDeliveryUseCase;
import com.malyah.accountmanager.notifications.application.WhatsAppWebhookUseCase;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.recurrences.application.UpcomingOccurrenceGeneration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.datasource.url")
class NotificationsConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationsConfiguration.class);

    /**
     * T36: the Evolution API settings. The key and the webhook secret come from {@code EVOLUTION_*} variables, which
     * the container entrypoint fills from the {@code *_FILE} secrets; nothing is sent unless
     * {@code EVOLUTION_API_ENABLED=true} and every value is present.
     */
    @Bean
    EvolutionWhatsAppProperties evolutionWhatsAppProperties(Environment environment,
            @Value("${app.whatsapp.enabled:false}") boolean enabled,
            @Value("${app.whatsapp.api-base-url:}") String apiBaseUrl,
            @Value("${app.whatsapp.api-key:}") String apiKey,
            @Value("${app.whatsapp.instance:}") String instance,
            @Value("${app.whatsapp.webhook-token:}") String webhookToken,
            @Value("${app.whatsapp.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.whatsapp.request-timeout:15s}") Duration requestTimeout) {
        var properties = new EvolutionWhatsAppProperties(enabled, apiBaseUrl, apiKey, instance, webhookToken,
                connectTimeout, requestTimeout);
        properties.requireSecureIn("production".equalsIgnoreCase(environment.getProperty("APP_ENVIRONMENT", "local")));
        if (enabled && !properties.configured())
            LOG.warn("whatsapp_provider_incomplete missing={}", properties.missing());
        return properties;
    }

    @Bean
    EvolutionWhatsAppProvider whatsAppProvider(EvolutionWhatsAppProperties properties) {
        return new EvolutionWhatsAppProvider(properties);
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

    /** H08.3: each member's in-app notifications. */
    @Bean
    MemberNotificationUseCase memberNotificationUseCase(JdbcTemplate jdbcTemplate,
            AuthenticatedUserContextQuery contexts, Clock applicationClock,
            @Value("${app.public-base-url}") String publicBaseUrl, PlatformTransactionManager transactionManager) {
        return new TransactionalMemberNotificationUseCase(new MemberNotificationService(
                new JdbcMemberNotificationRepository(jdbcTemplate), contexts, applicationClock, publicBaseUrl),
                new TransactionTemplate(transactionManager));
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
    /** H08.4: administrator tracking and test message. */
    @Bean
    WhatsAppDeliveryUseCase whatsAppDeliveryUseCase(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            EvolutionWhatsAppProvider provider, AuthenticatedUserContextQuery contexts, Clock applicationClock,
            @Value("${app.public-base-url}") String publicBaseUrl, PlatformTransactionManager transactionManager) {
        return new TransactionalWhatsAppDeliveryUseCase(deliveries(jdbcTemplate, expenses, forecasts, generation,
                provider, contexts, applicationClock, publicBaseUrl), provider,
                new TransactionTemplate(transactionManager));
    }

    /** T36: the Evolution API webhook (shared secret checked before anything is read). */
    @Bean
    WhatsAppWebhookUseCase whatsAppWebhookUseCase(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            EvolutionWhatsAppProvider provider, EvolutionWhatsAppProperties properties, AuthenticatedUserContextQuery contexts,
            Clock applicationClock, @Value("${app.public-base-url}") String publicBaseUrl,
            PlatformTransactionManager transactionManager) {
        return new EvolutionWhatsAppWebhook(properties, deliveries(jdbcTemplate, expenses, forecasts, generation, provider,
                contexts, applicationClock, publicBaseUrl), new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(name = "app.jobs.whatsapp.enabled", matchIfMissing = true)
    WhatsAppDeliveryJob whatsAppDeliveryJob(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            EvolutionWhatsAppProvider provider, AuthenticatedUserContextQuery contexts, Clock applicationClock,
            @Value("${app.public-base-url}") String publicBaseUrl, PlatformTransactionManager transactionManager) {
        return new WhatsAppDeliveryJob(deliveries(jdbcTemplate, expenses, forecasts, generation, provider, contexts,
                applicationClock, publicBaseUrl), provider, new TransactionTemplate(transactionManager));
    }

    private static WhatsAppDeliveryService deliveries(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            WhatsAppProviderStatus provider, AuthenticatedUserContextQuery contexts, Clock clock,
            String publicBaseUrl) {
        return new WhatsAppDeliveryService(new JdbcWhatsAppDeliveryRepository(jdbcTemplate),
                new JdbcReminderSummaryRepository(jdbcTemplate), new JdbcReminderSettingsRepository(jdbcTemplate),
                new JdbcMemberNotificationRepository(jdbcTemplate), summaries(jdbcTemplate, expenses, forecasts,
                        generation, provider, contexts, clock, publicBaseUrl), provider, contexts, clock,
                UUID::randomUUID);
    }

    private static ReminderSummaryService summaries(JdbcTemplate jdbcTemplate, ExpenseReminderQueries expenses,
            RecurrenceForecastQueries forecasts, ObjectProvider<UpcomingOccurrenceGeneration> generation,
            WhatsAppProviderStatus provider, AuthenticatedUserContextQuery contexts, Clock clock,
            String publicBaseUrl) {
        return new ReminderSummaryService(new JdbcReminderSummaryRepository(jdbcTemplate),
                new JdbcMemberNotificationRepository(jdbcTemplate), new JdbcReminderSettingsRepository(jdbcTemplate), expenses, forecasts,
                generation.getIfAvailable(() -> (spaceId, dueThrough) -> 0), provider, contexts, clock,
                UUID::randomUUID, publicBaseUrl);
    }

    private static ReminderSettingsService service(JdbcTemplate jdbcTemplate, AuthenticatedUserContextQuery contexts,
            FinancialMemberAccess members, WhatsAppProviderStatus provider, Clock clock) {
        return new ReminderSettingsService(new JdbcReminderSettingsRepository(jdbcTemplate), contexts, members,
                provider, clock, UUID::randomUUID);
    }
}
