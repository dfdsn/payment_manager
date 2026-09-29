package com.malyah.accountmanager.installments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.malyah.accountmanager.expenses.application.InstallmentAdjuster;
import com.malyah.accountmanager.expenses.application.InstallmentExpenses;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.installments.application.InstallmentAdjustmentUseCase;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseUseCase;

/** The controller injects the use cases by type: exactly one bean each, and it must be the transactional one. */
class InstallmentsConfigurationTest {
    @Test
    void eachUseCaseHasASingleTransactionalBeanForTheController() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://unused/db")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(InstallmentExpenses.class, () -> mock(InstallmentExpenses.class))
                .withBean(InstallmentAdjuster.class, () -> mock(InstallmentAdjuster.class))
                .withBean(AuthenticatedUserContextQuery.class, () -> mock(AuthenticatedUserContextQuery.class))
                .withBean(CategoryRepository.class, () -> mock(CategoryRepository.class))
                .withBean(FinancialMemberAccess.class, () -> mock(FinancialMemberAccess.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withUserConfiguration(InstallmentsConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(InstallmentPurchaseUseCase.class)
                            .isInstanceOf(TransactionalInstallmentPurchaseUseCase.class);
                    assertThat(context).getBean(InstallmentAdjustmentUseCase.class)
                            .isInstanceOf(TransactionalInstallmentAdjustmentUseCase.class);
                });
    }
}
