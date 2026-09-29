package com.malyah.accountmanager.notifications.infrastructure;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.notifications.application.ReminderSlotOutcome;
import com.malyah.accountmanager.notifications.application.ReminderSlotTask;
import com.malyah.accountmanager.notifications.application.ReminderSummaryService;

/**
 * H08.2 polling job. Each slot is one REPEATABLE READ transaction, so the expenses and the forecasts of a summary
 * come from the same snapshot (an occurrence materialized meanwhile can neither vanish nor appear twice). The
 * recurrence generation runs before it, outside any transaction. A worker that loses the race for a slot (unique
 * key or serialization failure) treats it as already processed. Logs carry counts only, never content.
 */
public final class ReminderSummaryJob {
    private static final Logger LOG = LoggerFactory.getLogger(ReminderSummaryJob.class);
    private final ReminderSummaryService service;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;
    private final Clock clock;

    public ReminderSummaryJob(ReminderSummaryService service, TransactionTemplate transactions, Clock clock) {
        this.service = Objects.requireNonNull(service);
        var manager = Objects.requireNonNull(transactions.getTransactionManager());
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(manager);
        this.writes.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString = "${app.jobs.reminders.fixed-delay-ms:60000}",
            initialDelayString = "${app.jobs.reminders.initial-delay-ms:30000}")
    public void poll() {
        var outcomes = new EnumMap<ReminderSlotOutcome, Integer>(ReminderSlotOutcome.class);
        var failed = 0;
        for (var task : Objects.requireNonNull(reads.execute(status -> service.dueSlots(clock.instant())))) {
            try {
                outcomes.merge(process(task), 1, Integer::sum);
            } catch (RuntimeException error) {
                failed++;
                LOG.warn("reminder_slot_failed spaceId={} date={} slot={} errorCode={}", task.space().spaceId(),
                        task.window().date(), task.window().slot(), error.getClass().getSimpleName());
            }
        }
        if (!outcomes.isEmpty() || failed > 0) LOG.info("reminder_slots outcomes={} failed={}", outcomes, failed);
    }

    ReminderSlotOutcome process(ReminderSlotTask task) {
        if (task.action() == ReminderSlotTask.Action.GENERATE) {
            try {
                service.materializeAhead(task);
            } catch (RuntimeException error) {
                // The occurrences stay as forecasts in the summary; the periodic generation retries them.
                LOG.warn("reminder_materialization_failed spaceId={} errorCode={}", task.space().spaceId(),
                        error.getClass().getSimpleName());
            }
        }
        try {
            return Objects.requireNonNull(writes.execute(status -> service.process(task, clock.instant())));
        } catch (ConcurrencyFailureException | DataIntegrityViolationException lostRace) {
            return ReminderSlotOutcome.ALREADY_PROCESSED;
        }
    }
}
