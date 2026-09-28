package com.malyah.accountmanager.recurrences.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.malyah.accountmanager.expenses.application.*;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.infrastructure.JdbcCategoryRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringOccurrenceAdjuster;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess;
import com.malyah.accountmanager.recurrences.application.*;
import com.malyah.accountmanager.recurrences.domain.*;

/**
 * H04.5 against real PostgreSQL: changes "este e os próximos", closure, effects on each occurrence situation,
 * forecasts, job revalidation, concurrency, idempotency, stale versions, rollback, permissions and isolation.
 */
@Testcontainers
class RecurrenceChangePostgresIT {
    private static final Instant NOW=Instant.parse("2026-09-27T15:00:00Z");
    private static final UUID SPACE=UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID OTHER=UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN=UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID GUEST=UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER=UUID.fromString("70000000-0000-0000-0000-000000000002");
    private static final String A="admin@example.com", G="guest@example.com";
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_change_test").withUsername("account_manager").withPassword("test-only-password");
    private JdbcTemplate jdbc; private TransactionTemplate tx; private RecurrenceUseCase recurrences; private ExpenseService expenses;
    private MutableClock clock;

    @BeforeEach void reset() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var flyway=Flyway.configure().dataSource(ds).cleanDisabled(false).load(); flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(19);
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        insertSpace(SPACE,"Casa"); insertSpace(OTHER,"Outra");
        insertUser(ADMIN,"Admin",A,SPACE,"ADMINISTRATOR");
        insertUser(GUEST,"Convidado",G,SPACE,"GUEST");
        insertUser(OUTSIDER,"Outro","other@example.com",OTHER,"ADMINISTRATOR");
        clock=new MutableClock(NOW);
        var context=new AuthenticatedUserContextService(contextRepository());
        var members=new JdbcFinancialMemberAccess(jdbc);
        expenses=new ExpenseService(new JdbcExpenseRepository(jdbc),context,UUID::randomUUID,clock,members);
        ChargeConfirmationUseCase confirmation=(email,command)->tx.execute(s->expenses.confirmCharge(email,command));
        var service=new RecurrenceService(new JdbcRecurrenceRepository(jdbc),context,new JdbcCategoryRepository(jdbc),members,
                clock,UUID::randomUUID,new RecurrenceCalendar(),new JdbcRecurringExpenseMaterializer(jdbc,tx),confirmation,
                new JdbcRecurringOccurrenceAdjuster(jdbc));
        recurrences=new TransactionalRecurrenceUseCase(service,tx);
    }

    @Test void changeFromAPeriodUpdatesPendingMetadataAndPreservesConfirmedValuesPaidCancelledAndEarlierPeriods() {
        var category=insertCategory("Casa",false);
        var energy=create("Energia","180.00",RecurrenceValueType.VARIABLE_ESTIMATE,RecurrenceFrequency.MONTHLY,d(2026,9,5),null);
        var sep=generate(NOW,energy);
        var oct=anticipate(energy,d(2026,10,5)); var nov=anticipate(energy,d(2026,11,5));
        var dec=anticipate(energy,d(2026,12,5)); var jan=anticipate(energy,d(2027,1,5));
        tx.execute(s->expenses.confirmCharge(A,new ConfirmChargeCommand(oct,0,"195.00",UUID.randomUUID())));
        tx.execute(s->expenses.settle(G,new SettleExpenseCommand(nov,expense(nov).version(),"200.00",d(2026,11,5),GUEST,null,
                UUID.randomUUID(),"200.00")));
        tx.execute(s->expenses.cancel(A,new CancelExpenseCommand(dec,expense(dec).version(),"Mudei de plano",UUID.randomUUID())));
        var before=snapshot();

        var command=change(energy,0,d(2026,10,5),"Energia elétrica","250.00",RecurrenceFrequency.MONTHLY,10,category,GUEST);
        var impact=recurrences.previewChange(G,command);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(impact.changedFields()).containsExactly("description","amount","dueDay","categoryId","responsibleUserId");
        assertThat(effect(impact,oct)).satisfies(e->{assertThat(e.action()).isEqualTo("UPDATE");
            assertThat(e.changes()).extracting(ImpactFieldChange::field).containsExactly("description","categoryId","responsibleUserId");
            assertThat(e.preservedFields()).containsExactly("amount","dueDate");});
        assertThat(effect(impact,nov)).satisfies(e->{assertThat(e.action()).isEqualTo("PRESERVE");assertThat(e.reason()).isEqualTo("PAID");});
        assertThat(effect(impact,dec)).satisfies(e->{assertThat(e.action()).isEqualTo("PRESERVE");assertThat(e.reason()).isEqualTo("CANCELLED");});
        assertThat(effect(impact,jan)).satisfies(e->{assertThat(e.action()).isEqualTo("UPDATE");
            assertThat(e.changes()).extracting(ImpactFieldChange::field).containsExactly("description","categoryId","responsibleUserId","dueDate");});
        assertThat(impact.occurrences()).extracting(ImpactOccurrenceView::expenseId).doesNotContain(sep);
        assertThat(impact.forecasts()).first().satisfies(f->{assertThat(f.month()).isEqualTo(d(2027,2,1));
            assertThat(f.action()).isEqualTo("CHANGED");assertThat(f.newDueDate()).isEqualTo(d(2027,2,10));
            assertThat(f.newDescription()).isEqualTo("Energia elétrica");assertThat(f.newAmount()).isEqualTo("200.00");});
        assertThat(impact.updatedCount()).isEqualTo(2); assertThat(impact.preservedCount()).isEqualTo(2);

        var key=UUID.randomUUID();
        var applied=recurrences.change(G,withToken(command,impact.impactToken(),key));

        assertThat(applied.replayed()).isFalse();
        assertThat(applied.recurrence().version()).isEqualTo(1);
        assertThat(applied.change()).satisfies(c->{assertThat(c.type()).isEqualTo("CHANGE");assertThat(c.actorUserId()).isEqualTo(GUEST);
            assertThat(c.occurredAt()).isEqualTo(NOW);assertThat(c.effectiveDueDate()).isEqualTo(d(2026,10,5));
            assertThat(c.updatedCount()).isEqualTo(2);});
        assertThat(expense(oct)).satisfies(e->{assertThat(e.description()).isEqualTo("Energia elétrica");
            assertThat(e.amount()).isEqualByComparingTo("195.00");assertThat(e.dueDate()).isEqualTo(d(2026,10,5));
            assertThat(e.categoryId()).isEqualTo(category);assertThat(e.responsibleUserId()).isEqualTo(GUEST);
            assertThat(e.chargeConfirmed()).isTrue();});
        assertThat(expense(jan)).satisfies(e->{assertThat(e.dueDate()).isEqualTo(d(2027,1,10));
            assertThat(e.amount()).isEqualByComparingTo("200.00");assertThat(e.chargeConfirmed()).isFalse();});
        assertThat(state(nov)).isEqualTo(before.get(nov.toString()));
        assertThat(state(dec)).isEqualTo(before.get(dec.toString()));
        assertThat(state(sep)).isEqualTo(before.get(sep.toString()));
        assertThat(jdbc.queryForObject("select scheduled_due_date from recurrence_occurrences where expense_id=?",LocalDate.class,jan))
                .isEqualTo(d(2027,1,5));
        assertThat(expenses.get(A,jan).history()).extracting(ExpenseHistoryEvent::type).contains("RECURRENCE_CHANGE_APPLIED");
        var forecast=recurrences.forecasts(A).occurrences();
        assertThat(item(forecast,energy,YearMonth.of(2027,2))).satisfies(f->{assertThat(f.scheduledDueDate()).isEqualTo(d(2027,2,10));
            assertThat(f.description()).isEqualTo("Energia elétrica");assertThat(f.state()).isEqualTo("FORECAST");});
        assertThat(item(forecast,energy,YearMonth.of(2026,9)).description()).isEqualTo("Energia");
        var view=recurrences.list(A).getFirst();
        assertThat(view.segments()).hasSize(2); assertThat(view.changes()).hasSize(1);
        assertThat(view.description()).isEqualTo("Energia");

        // Repetition, changed content under the same key and a stale version never apply a second change.
        assertThat(recurrences.change(G,withToken(command,impact.impactToken(),key)).replayed()).isTrue();
        assertThatThrownBy(()->recurrences.change(G,withToken(change(energy,0,d(2026,10,5),"Outra","250.00",
                RecurrenceFrequency.MONTHLY,10,category,GUEST),impact.impactToken(),key)))
                .isInstanceOf(RecurrenceIdempotencyConflictException.class);
        assertThatThrownBy(()->recurrences.change(A,withToken(command,impact.impactToken(),UUID.randomUUID())))
                .isInstanceOf(RecurrenceVersionConflictException.class);
        assertThat(count("select count(*) from recurrence_change_events")).isOne();
        assertThat(count("select count(*) from expense_entries")).isEqualTo(5);
    }

    @Test void fixedValueChangeReachesPendingLaunchesButKeepsIndividuallyCorrectedDueDatesAndSingleCorrectionsStayLocal() {
        var rent=create("Aluguel","1000.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,10),null);
        var oct=anticipate(rent,d(2026,10,10)); var nov=anticipate(rent,d(2026,11,10));
        tx.execute(s->expenses.correct(A,new CorrectExpenseCommand(nov,0,ExpenseStatus.PENDING,"Aluguel","1000.00",
                d(2026,11,15),null,null,null,null,null,UUID.randomUUID())));
        // "Somente este lançamento": the individual correction does not change the definition or other periods.
        tx.execute(s->expenses.correct(A,new CorrectExpenseCommand(oct,0,ExpenseStatus.PENDING,"Aluguel outubro","1000.00",
                d(2026,10,10),null,null,null,null,null,UUID.randomUUID())));
        assertThat(item(recurrences.forecasts(A).occurrences(),rent,YearMonth.of(2026,12)).description()).isEqualTo("Aluguel");

        var result=apply(A,change(rent,0,d(2026,10,10),"Aluguel","1100.00",RecurrenceFrequency.MONTHLY,20,null,null));

        assertThat(expense(oct)).satisfies(e->{assertThat(e.amount()).isEqualByComparingTo("1100.00");
            assertThat(e.dueDate()).isEqualTo(d(2026,10,20));assertThat(e.description()).isEqualTo("Aluguel outubro");});
        assertThat(expense(nov)).satisfies(e->{assertThat(e.amount()).isEqualByComparingTo("1100.00");
            assertThat(e.dueDate()).isEqualTo(d(2026,11,15));});
        assertThat(result.change().updatedCount()).isEqualTo(2);
        assertThat(item(recurrences.forecasts(A).occurrences(),rent,YearMonth.of(2026,12))).satisfies(f->{
            assertThat(f.amount()).isEqualTo("1100.00");assertThat(f.scheduledDueDate()).isEqualTo(d(2026,12,20));});
        assertThat(item(recurrences.forecasts(A).occurrences(),rent,YearMonth.of(2026,9))).satisfies(f->{
            assertThat(f.amount()).isEqualTo("1000.00");assertThat(f.scheduledDueDate()).isEqualTo(d(2026,9,10));});
    }

    @Test void calendarChangesKeepOnePeriodIdentityRemoveUnscheduledEstimatesAndNeverDuplicate() {
        var internet=create("Internet","120.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,5),null);
        var oct=anticipate(internet,d(2026,10,5)); var nov=anticipate(internet,d(2026,11,5));

        var bimonthly=apply(A,change(internet,0,d(2026,10,5),"Internet","120.00",RecurrenceFrequency.BIMONTHLY,5,null,null));

        assertThat(bimonthly.change().removedCount()).isOne();
        assertThat(expense(oct).status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(expense(oct).version()).isZero();
        assertThat(expense(nov)).satisfies(e->{assertThat(e.status()).isEqualTo(ExpenseStatus.CANCELLED);
            assertThat(e.version()).isOne();});
        assertThat(expenses.get(A,nov).history()).extracting(ExpenseHistoryEvent::type).contains("RECURRENCE_OCCURRENCE_REMOVED");
        assertThatThrownBy(()->anticipate(internet,d(2026,11,5))).isInstanceOf(RecurrenceOccurrenceException.class);
        var forecast=recurrences.forecasts(A).occurrences();
        assertThat(forecast).filteredOn(f->f.recurrenceId().equals(internet)).extracting(f->YearMonth.from(f.scheduledDueDate()))
                .doesNotContain(YearMonth.of(2026,11),YearMonth.of(2027,1)).contains(YearMonth.of(2026,12),YearMonth.of(2027,2));

        var dec=anticipate(internet,d(2026,12,5));
        apply(A,change(internet,1,d(2026,12,5),"Internet","120.00",RecurrenceFrequency.BIMONTHLY,20,null,null));
        assertThat(expense(dec).dueDate()).isEqualTo(d(2026,12,20));
        assertThat(anticipateResult(internet,d(2026,12,20)).occurrence().expenseId()).isEqualTo(dec);
        // A frequency that leaves a later programmed change off its calendar is rejected without writing.
        assertThatThrownBy(()->recurrences.previewChange(A,change(internet,2,d(2026,10,5),"Internet","120.00",
                RecurrenceFrequency.QUARTERLY,5,null,null))).isInstanceOf(RecurrenceValidationException.class)
                .hasMessageContaining("fora da nova frequência");
        clock.set(Instant.parse("2026-12-02T12:00:00Z"));
        new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),clock,Duration.ofMinutes(2),25).poll();
        assertThat(count("select count(*) from recurrence_occurrences where scheduled_month='2026-12-01'")).isOne();
        assertThat(count("select count(*) from expense_entries")).isEqualTo(3);
    }

    @Test void closureStopsNewOccurrencesRemovesOnlyEstimatedLaunchesAfterTheEndAndFlagsPaidOrConfirmedForReview() {
        var gas=create("Gás","80.00",RecurrenceValueType.VARIABLE_ESTIMATE,RecurrenceFrequency.MONTHLY,d(2026,9,15),null);
        var sep=generate(NOW,gas);
        var oct=anticipate(gas,d(2026,10,15)); var nov=anticipate(gas,d(2026,11,15));
        var dec=anticipate(gas,d(2026,12,15)); var jan=anticipate(gas,d(2027,1,15));
        tx.execute(s->expenses.settle(A,new SettleExpenseCommand(nov,0,"85.00",d(2026,11,15),ADMIN,null,UUID.randomUUID(),"85.00")));
        tx.execute(s->expenses.confirmCharge(A,new ConfirmChargeCommand(dec,expense(dec).version(),"90.00",UUID.randomUUID())));
        // An individually corrected due date is protected like a confirmed value: review, never automatic cancellation.
        var feb=anticipate(gas,d(2027,2,15));
        tx.execute(s->expenses.correct(A,new CorrectExpenseCommand(feb,expense(feb).version(),ExpenseStatus.PENDING,"Gás",
                expense(feb).amount().toPlainString(),d(2027,2,20),null,null,null,null,null,UUID.randomUUID())));
        var before=snapshot();

        var command=new CloseRecurrenceCommand(gas,0,d(2026,11,14),null,null,null);
        var impact=recurrences.previewClosure(G,command);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(impact.effectiveDueDate()).isEqualTo(d(2026,10,15));
        assertThat(effect(impact,nov)).satisfies(e->{assertThat(e.action()).isEqualTo("REVIEW");assertThat(e.reason()).isEqualTo("AFTER_END");});
        assertThat(effect(impact,dec).action()).isEqualTo("REVIEW");
        assertThat(effect(impact,jan).action()).isEqualTo("REMOVE");
        assertThat(effect(impact,feb).action()).isEqualTo("REVIEW");
        assertThat(impact.occurrences()).extracting(ImpactOccurrenceView::expenseId).doesNotContain(sep,oct);
        assertThat(impact.forecasts()).allSatisfy(f->assertThat(f.action()).isEqualTo("REMOVED")).hasSize(7);
        assertThatThrownBy(()->recurrences.close(G,new CloseRecurrenceCommand(gas,0,d(2026,11,14)," ",impact.impactToken(),UUID.randomUUID())))
                .isInstanceOf(RecurrenceValidationException.class);

        var closed=recurrences.close(G,new CloseRecurrenceCommand(gas,0,d(2026,11,14),"Mudança de endereço",impact.impactToken(),UUID.randomUUID()));

        assertThat(closed.recurrence()).satisfies(r->{assertThat(r.lastDueDate()).isEqualTo(d(2026,10,15));
            assertThat(r.closedAt()).isEqualTo(NOW);assertThat(r.closureReason()).isEqualTo("Mudança de endereço");
            assertThat(r.closedByDisplayName()).isEqualTo("Convidado");assertThat(r.upcomingDates()).containsExactly(d(2026,9,15),d(2026,10,15));});
        assertThat(expense(jan)).satisfies(e->{assertThat(e.status()).isEqualTo(ExpenseStatus.CANCELLED);});
        assertThat(jdbc.queryForObject("select cancellation_reason from expense_entries where id=?",String.class,jan))
                .isEqualTo("Recorrência encerrada: Mudança de endereço");
        assertThat(state(nov)).isEqualTo(before.get(nov.toString()));
        assertThat(state(dec)).isEqualTo(before.get(dec.toString()));
        assertThat(state(oct)).isEqualTo(before.get(oct.toString()));
        assertThat(state(feb)).isEqualTo(before.get(feb.toString()));
        assertThat(count("select count(*) from recurrence_occurrences where review_reason='AFTER_END'")).isEqualTo(3);
        var forecast=recurrences.forecasts(A).occurrences().stream().filter(f->f.recurrenceId().equals(gas)).toList();
        assertThat(forecast).extracting(ForecastView::expenseId).containsExactly(sep,oct,nov,dec,feb);
        assertThat(forecast).filteredOn(f->f.expenseId().equals(nov)).singleElement()
                .satisfies(f->assertThat(f.reviewReason()).isEqualTo("AFTER_END"));
        assertThatThrownBy(()->anticipate(gas,d(2027,2,15))).isInstanceOf(RecurrenceOccurrenceException.class);
        assertThatThrownBy(()->recurrences.previewClosure(A,new CloseRecurrenceCommand(gas,1,d(2026,12,15),null,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->recurrences.previewChange(A,change(gas,1,d(2026,11,15),"Gás","80.00",RecurrenceFrequency.MONTHLY,15,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);

        clock.set(Instant.parse("2026-11-02T12:00:00Z"));
        new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),clock,Duration.ofMinutes(2),25).poll();
        assertThat(count("select count(*) from expense_entries")).isEqualTo(6);
        assertThat(count("select count(*) from recurrence_change_events where change_type='CLOSURE' and reason='Mudança de endereço'")).isOne();
    }

    @Test void closureHonorsTheInclusiveCutOffDateAtMonthEnds() {
        var fee=create("Taxa","10.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,10,31),null);
        assertThat(recurrences.previewClosure(A,new CloseRecurrenceCommand(fee,0,d(2027,2,27),null,null,null)).effectiveDueDate())
                .isEqualTo(d(2027,1,31));
        assertThat(recurrences.previewClosure(A,new CloseRecurrenceCommand(fee,0,d(2027,2,28),null,null,null)).effectiveDueDate())
                .isEqualTo(d(2027,2,28));
        assertThatThrownBy(()->recurrences.previewClosure(A,new CloseRecurrenceCommand(fee,0,d(2026,10,30),null,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        var ending=create("Curso","10.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,10,31),d(2027,3,31));
        assertThatThrownBy(()->recurrences.previewClosure(A,new CloseRecurrenceCommand(ending,0,d(2027,4,30),null,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThat(recurrences.previewClosure(A,new CloseRecurrenceCommand(ending,0,d(2027,3,30),null,null,null)).effectiveDueDate())
                .isEqualTo(d(2027,2,28));
    }

    @Test void theJobRevalidatesTheCurrentDefinitionAfterAChangeOrClosure() {
        clock.set(Instant.parse("2026-10-01T12:00:00Z"));
        var water=create("Água","60.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,10,5),null);
        var closedOne=create("Seguro","30.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,8),null);
        var job=new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),clock,Duration.ofMinutes(2),0);
        job.enqueue(clock.instant());
        assertThat(count("select count(*) from recurrence_generation_jobs where status='PENDING'")).isEqualTo(2);

        apply(A,change(water,0,d(2026,10,5),"Água","65.00",RecurrenceFrequency.MONTHLY,20,null,null));
        var closure=recurrences.previewClosure(A,new CloseRecurrenceCommand(closedOne,0,d(2026,9,30),null,null,null));
        recurrences.close(A,new CloseRecurrenceCommand(closedOne,0,d(2026,9,30),"Cancelado",closure.impactToken(),UUID.randomUUID()));
        new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),clock,Duration.ofMinutes(2),25).poll();
        new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),clock,Duration.ofMinutes(2),25).poll();

        assertThat(jdbc.queryForObject("select status from recurrence_generation_jobs where recurrence_id=?",String.class,closedOne))
                .isEqualTo("SKIPPED");
        assertThat(jdbc.query("select description,charge_amount,due_date from expense_entries",(r,n)->r.getString(1)+":"
                +r.getBigDecimal(2)+":"+r.getObject(3,LocalDate.class))).containsExactly("Água:65.00:2026-10-20");
        assertThat(count("select count(*) from recurrence_occurrences")).isOne();
    }

    @Test void archivedCategoryAndDepartedResponsibleAreRejectedForChangesAndFallBackInLaterGeneration() {
        var category=insertCategory("Serviços",false); var archived=insertCategory("Antiga",true);
        var otherCategory=insertCategoryIn(OTHER,"Alheia");
        var phone=create("Telefone","50.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,12),null);
        assertThatThrownBy(()->recurrences.previewChange(A,change(phone,0,d(2026,10,12),"Telefone","50.00",
                RecurrenceFrequency.MONTHLY,12,archived,null))).isInstanceOf(CategoryConflictException.class);
        assertThatThrownBy(()->recurrences.previewChange(A,change(phone,0,d(2026,10,12),"Telefone","50.00",
                RecurrenceFrequency.MONTHLY,12,otherCategory,null))).isInstanceOf(CategoryConflictException.class);
        assertThatThrownBy(()->recurrences.previewChange(A,change(phone,0,d(2026,10,12),"Telefone","50.00",
                RecurrenceFrequency.MONTHLY,12,null,OUTSIDER))).isInstanceOf(AuthenticatedUserContextNotFoundException.class);

        apply(A,change(phone,0,d(2026,10,12),"Telefone","50.00",RecurrenceFrequency.MONTHLY,12,category,GUEST));
        jdbc.update("update expense_categories set archived_at=? where id=?",Timestamp.from(NOW),category);
        jdbc.update("update space_memberships set active=false,ended_at=?,ended_by_user_id=?,end_reason='ADMIN_REMOVAL' where user_id=?",
                Timestamp.from(NOW),ADMIN,GUEST);
        var nov=anticipate(phone,d(2026,11,12));
        assertThat(expense(nov)).satisfies(e->{assertThat(e.categoryId()).isNull();assertThat(e.responsibleUserId()).isNull();});
        assertThatThrownBy(()->recurrences.list(G)).isInstanceOf(AuthenticatedUserContextNotFoundException.class);
    }

    @Test void rejectsInvalidPeriodsUnchangedRequestsForeignSpacesAndTokensThatNoLongerMatch() {
        var tv=create("TV","40.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,3),null);
        assertThatThrownBy(()->recurrences.previewChange(A,change(tv,0,d(2026,8,3),"TV","41.00",RecurrenceFrequency.MONTHLY,3,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->recurrences.previewChange(A,change(tv,0,d(2027,10,3),"TV","41.00",RecurrenceFrequency.MONTHLY,3,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->recurrences.previewChange(A,change(tv,0,d(2026,10,3),"TV","40.00",RecurrenceFrequency.MONTHLY,3,null,null)))
                .isInstanceOf(RecurrenceValidationException.class).hasMessageContaining("Nenhum campo");
        assertThatThrownBy(()->recurrences.previewChange(A,change(tv,0,d(2026,10,3),"TV","0",RecurrenceFrequency.MONTHLY,3,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->recurrences.previewChange(A,change(tv,0,d(2026,10,3),"TV","40.00",RecurrenceFrequency.MONTHLY,32,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->recurrences.previewChange("other@example.com",change(tv,0,d(2026,10,3),"TV","41.00",
                RecurrenceFrequency.MONTHLY,3,null,null))).isInstanceOf(RecurrenceNotFoundException.class);
        var command=change(tv,0,d(2026,10,3),"TV","41.00",RecurrenceFrequency.MONTHLY,3,null,null);
        var impact=recurrences.previewChange(A,command);
        assertThatThrownBy(()->recurrences.close("other@example.com",new CloseRecurrenceCommand(tv,0,d(2026,10,3),"x",
                impact.impactToken(),UUID.randomUUID()))).isInstanceOf(RecurrenceNotFoundException.class);

        // The data shown in the preview changed (the period was materialized): nothing is applied.
        var oct=anticipate(tv,d(2026,10,3));
        assertThatThrownBy(()->recurrences.change(A,withToken(command,impact.impactToken(),UUID.randomUUID())))
                .isInstanceOf(RecurrenceImpactChangedException.class);
        assertThat(expense(oct).amount()).isEqualByComparingTo("40.00");
        assertThat(count("select count(*) from recurrence_change_events")).isZero();
        assertThat(count("select count(*) from recurrence_change_requests")).isZero();
        assertThat(count("select count(*) from recurrence_segments where recurrence_id='"+tv+"'")).isOne();
    }

    @Test void failureWhileApplyingRollsBackTheWholeChangeAndTheRetrySucceeds() {
        var club=create("Clube","70.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,9),null);
        var oct=anticipate(club,d(2026,10,9)); var nov=anticipate(club,d(2026,11,9));
        var command=change(club,0,d(2026,10,9),"Clube","75.00",RecurrenceFrequency.MONTHLY,9,null,null);
        var impact=recurrences.previewChange(A,command);
        jdbc.execute("alter table expense_correction_events add constraint force_failure check(new_due_date<>'2026-11-09')");
        var key=UUID.randomUUID();

        assertThatThrownBy(()->recurrences.change(A,withToken(command,impact.impactToken(),key)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(expense(oct).amount()).isEqualByComparingTo("70.00"); assertThat(expense(oct).version()).isZero();
        assertThat(expense(nov).amount()).isEqualByComparingTo("70.00");
        assertThat(count("select count(*) from recurrence_change_events")).isZero();
        assertThat(count("select count(*) from recurrence_change_requests")).isZero();
        assertThat(count("select count(*) from expense_correction_events")).isZero();
        assertThat(count("select count(*) from recurrence_segments where recurrence_id='"+club+"'")).isOne();
        assertThat(jdbc.queryForObject("select version from recurrence_definitions where id=?",Long.class,club)).isZero();

        jdbc.execute("alter table expense_correction_events drop constraint force_failure");
        var retry=recurrences.change(A,withToken(command,impact.impactToken(),key));
        assertThat(retry.replayed()).isFalse();
        assertThat(expense(nov).amount()).isEqualByComparingTo("75.00");
    }

    @Test void concurrentChangeWithGenerationAnticipationConfirmationAndSettlementNeverAppliesAStaleImpact() throws Exception {
        // Generation of the current month against a change starting in it.
        var fixed=create("Condomínio","100.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,20),null);
        var generationChange=change(fixed,0,d(2026,9,20),"Condomínio","150.00",RecurrenceFrequency.MONTHLY,20,null,null);
        var generationImpact=recurrences.previewChange(A,generationChange);
        var generation=race(()->recurrences.change(A,withToken(generationChange,generationImpact.impactToken(),UUID.randomUUID())),
                ()->{new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),clock,Duration.ofMinutes(2),25).poll();return "generated";});
        var september=jdbc.queryForObject("select expense_id from recurrence_occurrences where recurrence_id=?",UUID.class,fixed);
        if(generation.getFirst() instanceof RecurrenceChangeResult) assertThat(expense(september).amount()).isEqualByComparingTo("150.00");
        else {
            assertThat(generation.getFirst()).isInstanceOf(RecurrenceImpactChangedException.class);
            assertThat(expense(september).amount()).isEqualByComparingTo("100.00");
        }
        assertThat(count("select count(*) from recurrence_occurrences where recurrence_id='"+fixed+"'")).isOne();

        // Anticipation of a period against a change starting in it.
        var fee=create("Mensalidade","300.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,7),null);
        var feeChange=change(fee,0,d(2026,10,7),"Mensalidade","320.00",RecurrenceFrequency.MONTHLY,7,null,null);
        var feeImpact=recurrences.previewChange(A,feeChange);
        var anticipation=race(()->recurrences.change(A,withToken(feeChange,feeImpact.impactToken(),UUID.randomUUID())),
                ()->anticipate(fee,d(2026,10,7)));
        var october=jdbc.queryForObject("select expense_id from recurrence_occurrences where recurrence_id=? and scheduled_month='2026-10-01'",UUID.class,fee);
        assertThat(expense(october).amount()).isEqualByComparingTo(anticipation.getFirst() instanceof RecurrenceChangeResult ? "320.00" : "300.00");

        // Confirmation of a variable charge against a metadata change covering it: exactly one wins.
        var power=create("Luz","200.00",RecurrenceValueType.VARIABLE_ESTIMATE,RecurrenceFrequency.MONTHLY,d(2026,9,6),null);
        var powerOct=anticipate(power,d(2026,10,6));
        var powerChange=change(power,0,d(2026,10,6),"Luz casa","200.00",RecurrenceFrequency.MONTHLY,6,null,null);
        var powerImpact=recurrences.previewChange(A,powerChange);
        var confirmation=race(()->recurrences.change(A,withToken(powerChange,powerImpact.impactToken(),UUID.randomUUID())),
                ()->tx.execute(s->expenses.confirmCharge(G,new ConfirmChargeCommand(powerOct,0,"210.00",UUID.randomUUID()))));
        assertThat(confirmation).filteredOn(o->o instanceof Exception).hasSize(1);
        assertThat(expense(powerOct).version()).isOne();

        // Settlement against an amount change covering the same launch: exactly one wins, nothing paid is rewritten.
        var gym=create("Academia","90.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,11),null);
        var gymOct=anticipate(gym,d(2026,10,11));
        var gymChange=change(gym,0,d(2026,10,11),"Academia","99.00",RecurrenceFrequency.MONTHLY,11,null,null);
        var gymImpact=recurrences.previewChange(A,gymChange);
        var settlement=race(()->recurrences.change(A,withToken(gymChange,gymImpact.impactToken(),UUID.randomUUID())),
                ()->tx.execute(s->expenses.settle(G,new SettleExpenseCommand(gymOct,0,"90.00",d(2026,10,11),GUEST,null,UUID.randomUUID()))));
        assertThat(settlement).filteredOn(o->o instanceof Exception).hasSize(1);
        var gymExpense=expense(gymOct);
        if(gymExpense.status()==ExpenseStatus.PAID) assertThat(gymExpense.amount()).isEqualByComparingTo("90.00");
        else assertThat(gymExpense.amount()).isEqualByComparingTo("99.00");

        // Two identical changes with different keys: only one applies, the other sees the new version.
        var net=create("Rede","60.00",RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,d(2026,9,14),null);
        var netChange=change(net,0,d(2026,10,14),"Rede","61.00",RecurrenceFrequency.MONTHLY,14,null,null);
        var netImpact=recurrences.previewChange(A,netChange);
        var twice=race(()->recurrences.change(A,withToken(netChange,netImpact.impactToken(),UUID.randomUUID())),
                ()->recurrences.change(G,withToken(netChange,netImpact.impactToken(),UUID.randomUUID())));
        assertThat(twice).filteredOn(o->o instanceof RecurrenceVersionConflictException).hasSize(1);
        assertThat(count("select count(*) from recurrence_change_events where recurrence_id='"+net+"'")).isOne();
    }

    private RecurrenceChangeResult apply(String email,ChangeRecurrenceCommand command) {
        var impact=recurrences.previewChange(email,command);
        return recurrences.change(email,withToken(command,impact.impactToken(),UUID.randomUUID()));
    }
    private static ChangeRecurrenceCommand change(UUID id,long version,LocalDate from,String description,String amount,
            RecurrenceFrequency frequency,int dueDay,UUID category,UUID responsible) {
        return new ChangeRecurrenceCommand(id,version,from,description,amount,frequency,dueDay,category,responsible,null,null);
    }
    private static ChangeRecurrenceCommand withToken(ChangeRecurrenceCommand c,String token,UUID key) {
        return new ChangeRecurrenceCommand(c.recurrenceId(),c.version(),c.effectiveDueDate(),c.description(),c.amount(),
                c.frequency(),c.dueDay(),c.categoryId(),c.responsibleUserId(),token,key);
    }
    private static ImpactOccurrenceView effect(RecurrenceImpactView impact,UUID expense) {
        return impact.occurrences().stream().filter(o->o.expenseId().equals(expense)).findFirst().orElseThrow();
    }
    private UUID create(String description,String amount,RecurrenceValueType type,RecurrenceFrequency frequency,LocalDate first,LocalDate last) {
        return recurrences.create(A,new CreateRecurrenceCommand(description,amount,type,frequency,first,last,null,null,UUID.randomUUID()))
                .recurrence().id();
    }
    private UUID anticipate(UUID recurrence,LocalDate date) { return anticipateResult(recurrence,date).occurrence().expenseId(); }
    private AnticipationResult anticipateResult(UUID recurrence,LocalDate date) {
        return recurrences.anticipate(A,recurrence,date,UUID.randomUUID());
    }
    private UUID generate(Instant at,UUID recurrence) {
        new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),Clock.fixed(at,ZoneOffset.UTC),Duration.ofMinutes(2),25).poll();
        var month=YearMonth.from(at.atZone(ZoneId.of("America/Sao_Paulo")));
        return jdbc.queryForObject("select expense_id from recurrence_occurrences where recurrence_id=? and scheduled_month=?",
                UUID.class,recurrence,month.atDay(1));
    }
    private List<Object> race(Callable<Object> first,Callable<Object> second) throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{start.await();return outcome(first);});
            var b=executor.submit(()->{start.await();return outcome(second);});
            start.countDown();
            return List.of(a.get(),b.get());
        }
    }
    private Object outcome(Callable<Object> action) { try { return action.call(); } catch(Exception error) { return error; } }
    private ForecastView item(List<ForecastView> forecast,UUID recurrence,YearMonth month) {
        return forecast.stream().filter(o->o.recurrenceId().equals(recurrence)&&YearMonth.from(o.scheduledDueDate()).equals(month))
                .findFirst().orElseThrow();
    }
    private StoredExpense expense(UUID id) { return new JdbcExpenseRepository(jdbc).findById(SPACE,id); }
    private String state(UUID id) {
        return jdbc.queryForObject("select concat_ws('|',status,description,charge_amount,due_date,version,category_id,responsible_user_id,charge_confirmed) from expense_entries where id=?",
                String.class,id);
    }
    private java.util.Map<String,String> snapshot() {
        var result=new java.util.TreeMap<String,String>();
        jdbc.query("select id from expense_entries",(org.springframework.jdbc.core.RowCallbackHandler)rs->{
            var id=rs.getObject(1,UUID.class);result.put(id.toString(),state(id));});
        result.put("definitions",jdbc.queryForObject("select string_agg(concat_ws('|',id,version,last_due_date,closed_at),',' order by id) from recurrence_definitions",String.class));
        result.put("segments",String.valueOf(count("select count(*) from recurrence_segments")));
        result.put("events",String.valueOf(count("select count(*) from recurrence_change_events")));
        return result;
    }
    private int count(String sql) { return jdbc.queryForObject(sql,Integer.class); }
    private static LocalDate d(int y,int m,int day) { return LocalDate.of(y,m,day); }
    private UUID insertCategory(String name,boolean archived) {
        var id=insertCategoryIn(SPACE,name);
        if(archived) jdbc.update("update expense_categories set archived_at=? where id=?",Timestamp.from(NOW),id);
        return id;
    }
    private UUID insertCategoryIn(UUID space,String name) {
        var id=UUID.randomUUID();
        jdbc.update("insert into expense_categories(id,space_id,name,normalized_name,version,created_by_user_id,created_at,updated_at) values(?,?,?,?,0,?,?,?)",
                id,space,name,name.toLowerCase(),space.equals(SPACE)?ADMIN:OUTSIDER,Timestamp.from(NOW),Timestamp.from(NOW));
        return id;
    }
    private AuthenticatedUserContextRepository contextRepository() { return email -> jdbc.query("""
        select u.id,u.display_name,u.normalized_email,s.id,s.name,m.role,s.currency_code,s.locale,s.time_zone
          from identity_users u join space_memberships m on m.user_id=u.id and m.active=true join family_spaces s on s.id=m.space_id
         where u.normalized_email=?
        """,(rs,row)->new AuthenticatedUserContext(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),SpaceRole.valueOf(rs.getString(6)),rs.getString(7),rs.getString(8),rs.getString(9)),email).stream().findFirst(); }
    private void insertSpace(UUID id,String name){jdbc.update("insert into family_spaces(id,name,currency_code,locale,time_zone,created_at) values (?,?,'BRL','pt-BR','America/Sao_Paulo',?)",id,name,Timestamp.from(NOW));}
    private void insertUser(UUID id,String name,String email,UUID space,String role){jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,created_at) values (?,?,?,'{test}x',true,?)",id,name,email,Timestamp.from(NOW));jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at) values (?,?,?,?,true,?)",UUID.randomUUID(),id,space,role,Timestamp.from(NOW));}

    /** Controllable clock: the tests move it to run the job in later months. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant instant) { this.instant=instant; }
        void set(Instant value) { instant=value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { var parent=this; return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId other) { return parent.withZone(other); }
            @Override public Instant instant() { return parent.instant(); } }; }
        @Override public Instant instant() { return instant; }
    }
}
