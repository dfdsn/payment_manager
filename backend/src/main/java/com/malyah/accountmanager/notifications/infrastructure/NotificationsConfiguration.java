package com.malyah.accountmanager.notifications.infrastructure;

import java.time.Clock;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.identity.application.AdministrationTransferHandler;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.notifications.application.ReminderSettingsService;
import com.malyah.accountmanager.notifications.application.ReminderSettingsUseCase;
import com.malyah.accountmanager.notifications.application.port.WhatsAppProviderStatus;

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

    private static ReminderSettingsService service(JdbcTemplate jdbcTemplate, AuthenticatedUserContextQuery contexts,
            FinancialMemberAccess members, WhatsAppProviderStatus provider, Clock clock) {
        return new ReminderSettingsService(new JdbcReminderSettingsRepository(jdbcTemplate), contexts, members,
                provider, clock, UUID::randomUUID);
    }
}
