package com.malyah.accountmanager.recurrences.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;
import com.malyah.accountmanager.recurrences.application.*;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;

final class TransactionalRecurrenceUseCase implements RecurrenceUseCase {
    private final RecurrenceService delegate; private final TransactionTemplate transactions;
    TransactionalRecurrenceUseCase(RecurrenceService delegate, TransactionTemplate transactions) { this.delegate=delegate;this.transactions=transactions; }
    @Override public RecurrenceCreationResult create(String email, CreateRecurrenceCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.create(email,command)));
    }
    @Override public List<RecurrenceView> list(String email) { return delegate.list(email); }
    @Override public List<LocalDate> preview(LocalDate first, LocalDate last, RecurrenceFrequency frequency) {
        return delegate.preview(first,last,frequency);
    }
    @Override public ForecastPeriodView forecasts(String email) { return delegate.forecasts(email); }
    @Override public AnticipationResult anticipate(String email,java.util.UUID recurrenceId,LocalDate due,
            java.util.UUID key) {
        return Objects.requireNonNull(transactions.execute(status->delegate.anticipate(email,recurrenceId,due,key)));
    }
    @Override public AnticipationResult confirmForecastCharge(String email,java.util.UUID recurrenceId,LocalDate due,
            String amount,java.util.UUID key) {
        return Objects.requireNonNull(transactions.execute(status->delegate.confirmForecastCharge(email,recurrenceId,due,amount,key)));
    }
    @Override public RecurrenceImpactView previewChange(String email, ChangeRecurrenceCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.previewChange(email,command)));
    }
    @Override public RecurrenceChangeResult change(String email, ChangeRecurrenceCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.change(email,command)));
    }
    @Override public RecurrenceImpactView previewClosure(String email, CloseRecurrenceCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.previewClosure(email,command)));
    }
    @Override public RecurrenceChangeResult close(String email, CloseRecurrenceCommand command) {
        return Objects.requireNonNull(transactions.execute(status -> delegate.close(email,command)));
    }
}
