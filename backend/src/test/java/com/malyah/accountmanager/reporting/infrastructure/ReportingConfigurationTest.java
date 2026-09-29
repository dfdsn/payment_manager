package com.malyah.accountmanager.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.malyah.accountmanager.expenses.application.ExpenseExportQueries;
import com.malyah.accountmanager.expenses.application.ExpensePlanningQueries;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.recurrences.application.RecurrenceForecastQueries;
import com.malyah.accountmanager.reporting.application.ExpenseExportQuery;
import com.malyah.accountmanager.reporting.application.ExportUseCase;
import com.malyah.accountmanager.reporting.application.PlanningQuery;
import com.malyah.accountmanager.reporting.application.PlanningUseCase;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

/** One bean per report use case, and every report reads a single read-only REPEATABLE READ snapshot. */
class ReportingConfigurationTest {
    @Test
    void theOnlyUseCaseBeanRunsReportsInAReadOnlyRepeatableReadTransaction() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var queries = mock(ExpenseReportQueries.class);
        when(queries.totals(any(), any())).thenReturn(List.of());
        var planningQueries = mock(ExpensePlanningQueries.class);
        var forecasts = mock(RecurrenceForecastQueries.class);
        var exportQueries = mock(ExpenseExportQueries.class);
        var contexts = mock(AuthenticatedUserContextQuery.class);
        when(contexts.findByEmail("ana@example.com")).thenReturn(new AuthenticatedUserContext(UUID.randomUUID(),
                "Ana", "ana@example.com", UUID.randomUUID(), "Casa", SpaceRole.ADMINISTRATOR, "BRL", "pt-BR",
                "America/Sao_Paulo"));
        new ApplicationContextRunner()
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://unused/db")
                .withBean(ExpenseReportQueries.class, () -> queries)
                .withBean(ExpensePlanningQueries.class, () -> planningQueries)
                .withBean(RecurrenceForecastQueries.class, () -> forecasts)
                .withBean(ExpenseExportQueries.class, () -> exportQueries)
                .withBean(AuthenticatedUserContextQuery.class, () -> contexts)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(PlatformTransactionManager.class, () -> manager)
                .withUserConfiguration(ReportingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var useCase = context.getBean(ReportingUseCase.class);
                    assertThat(useCase).isInstanceOf(TransactionalReportingUseCase.class);
                    assertThat(useCase.dueDashboard("ana@example.com", ReportFilters.currentMonth())
                            .indicators().plannedTotal()).isEqualTo("0.00");
                    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
                    verify(manager).getTransaction(definition.capture());
                    assertThat(definition.getValue().isReadOnly()).isTrue();
                    assertThat(definition.getValue().getIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    var planning = context.getBean(PlanningUseCase.class);
                    assertThat(planning).isInstanceOf(TransactionalPlanningUseCase.class);
                    assertThat(planning.planning("ana@example.com", PlanningQuery.currentMonth()).totals()
                            .plannedTotal()).isEqualTo("0.00");
                    verify(manager, org.mockito.Mockito.times(2)).getTransaction(definition.capture());
                    assertThat(definition.getValue().isReadOnly()).isTrue();
                    assertThat(definition.getValue().getIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    var exports = context.getBean(ExportUseCase.class);
                    assertThat(exports).isInstanceOf(TransactionalExportUseCase.class);
                    assertThat(exports.expenses("ana@example.com", new ExpenseExportQuery(null, null, null, null, null,
                            false, null, false, null, null, null, null)).rows()).isZero();
                    verify(manager, org.mockito.Mockito.times(3)).getTransaction(definition.capture());
                    assertThat(definition.getValue().isReadOnly()).isTrue();
                    assertThat(definition.getValue().getIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                });
    }
}
