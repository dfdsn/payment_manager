package com.malyah.accountmanager.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.reporting.application.CloseMonthCommand;
import com.malyah.accountmanager.reporting.application.MonthClosingUseCase;

/** Writing a closing is one read-write transaction; reading one is a read-only REPEATABLE READ snapshot. */
class TransactionalMonthClosingUseCaseTest {
    @Test
    void closesInOneWriteTransactionAndReadsInOneSnapshotAndRollsBackOnFailure() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var delegate = mock(MonthClosingUseCase.class);
        var useCase = new TransactionalMonthClosingUseCase(delegate, new TransactionTemplate(manager));
        var command = new CloseMonthCommand("2026-10", true, UUID.randomUUID());

        useCase.close("ana@example.com", command);
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isFalse();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        verify(delegate).close("ana@example.com", command);

        useCase.view("ana@example.com", "2026-10");
        verify(manager, org.mockito.Mockito.times(2)).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        verify(delegate).view("ana@example.com", "2026-10");

        when(delegate.close(any(), any())).thenThrow(new IllegalStateException("boom"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.close("ana@example.com", command))
                .hasMessage("boom");
        verify(manager).rollback(any());
        verify(manager, org.mockito.Mockito.times(2)).commit(any());
        verify(manager, never()).rollback(null);
    }
}
