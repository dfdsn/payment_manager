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
import com.malyah.accountmanager.expenses.domain.ExpenseValidationException;
import com.malyah.accountmanager.expenses.infrastructure.JdbcExpenseRepository;
import com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextService;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess;
import com.malyah.accountmanager.recurrences.application.*;
import com.malyah.accountmanager.recurrences.domain.*;

/** H04.4 against real PostgreSQL: confirmation, forward propagation, settlement rules, conflicts and rollback. */
@Testcontainers
class VariableChargeConfirmationPostgresIT {
    private static final Instant NOW=Instant.parse("2026-09-27T15:00:00Z");
    private static final UUID SPACE=UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID OTHER=UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN=UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID GUEST=UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID OUTSIDER=UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final LocalDate SEP=LocalDate.of(2026,9,5), OCT=LocalDate.of(2026,10,5), NOV=LocalDate.of(2026,11,5),
            DEC=LocalDate.of(2026,12,5);
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_charge_test").withUsername("account_manager").withPassword("test-only-password");
    private JdbcTemplate jdbc; private TransactionTemplate tx; private RecurrenceUseCase recurrences; private ExpenseService expenses;

    @BeforeEach void reset() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var flyway=Flyway.configure().dataSource(ds).cleanDisabled(false).load(); flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(22);
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        insertSpace(SPACE,"Casa"); insertSpace(OTHER,"Outra");
        insertUser(ADMIN,"Admin","admin@example.com",SPACE,"ADMINISTRATOR");
        insertUser(GUEST,"Convidado","guest@example.com",SPACE,"GUEST");
        insertUser(OUTSIDER,"Outro","other@example.com",OTHER,"ADMINISTRATOR");
        var context=new AuthenticatedUserContextService(contextRepository());
        var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        var members=new JdbcFinancialMemberAccess(jdbc);
        expenses=new ExpenseService(new JdbcExpenseRepository(jdbc),context,UUID::randomUUID,clock,members);
        ChargeConfirmationUseCase confirmation=(email,command)->tx.execute(s->expenses.confirmCharge(email,command));
        var service=new RecurrenceService(new JdbcRecurrenceRepository(jdbc),context,new NoCategoryRepository(),members,clock,
                UUID::randomUUID,new RecurrenceCalendar(),new JdbcRecurringExpenseMaterializer(jdbc,tx),confirmation);
        recurrences=new TransactionalRecurrenceUseCase(service,tx);
    }

    @Test void confirmsFromForecastOnceKeepsEstimateAndPropagatesOnlyToLaterEstimatesInDueOrder() {
        var energy=variable("Energia","180.00",SEP);
        var november=recurrences.anticipate("admin@example.com",energy,NOV,UUID.randomUUID()).occurrence().expenseId();
        assertThat(expense(november).chargeConfirmed()).isFalse();
        var key=UUID.randomUUID();

        var october=recurrences.confirmForecastCharge("guest@example.com",energy,OCT,"195",key);
        var replay=recurrences.confirmForecastCharge("guest@example.com",energy,OCT,"195",key);

        assertThat(october.replayed()).isFalse(); assertThat(replay.replayed()).isTrue();
        assertThat(replay.occurrence().expenseId()).isEqualTo(october.occurrence().expenseId());
        assertThat(october.occurrence().state()).isEqualTo("MATERIALIZED");
        assertThat(october.occurrence().estimated()).isFalse();
        var confirmed=expense(october.occurrence().expenseId());
        assertThat(confirmed.amount()).isEqualByComparingTo("195.00");
        assertThat(confirmed.status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(confirmed.paymentDate()).isNull();
        assertThat(confirmed.dueDate()).isEqualTo(OCT);
        assertThat(confirmed.version()).isEqualTo(1);
        assertThat(confirmed.chargeConfirmation().estimatedAmount()).isEqualByComparingTo("180.00");
        assertThat(confirmed.chargeConfirmation().confirmedByUserId()).isEqualTo(GUEST);
        assertThat(confirmed.chargeConfirmation().confirmedAt()).isEqualTo(NOW);
        assertThat(expense(november).amount()).isEqualByComparingTo("195.00");
        assertThat(expense(november).chargeConfirmed()).isFalse();
        assertThat(expense(november).version()).isEqualTo(1);
        assertThat(count("select count(*) from expense_entries")).isEqualTo(2);
        assertThat(count("select count(*) from recurrence_occurrence_events")).isEqualTo(2);
        assertThat(count("select count(*) from expense_charge_events where event_type='CHARGE_CONFIRMED'")).isEqualTo(1);
        assertThat(count("select count(*) from expense_charge_events where event_type='ESTIMATE_UPDATED'")).isEqualTo(1);
        assertThatThrownBy(()->recurrences.confirmForecastCharge("guest@example.com",energy,OCT,"196",key))
                .isInstanceOf(ExpenseIdempotencyConflictException.class);

        var forecast=recurrences.forecasts("admin@example.com").occurrences();
        assertThat(item(forecast,energy,SEP)).satisfies(o->{assertThat(o.amount()).isEqualTo("180.00");assertThat(o.estimated()).isTrue();});
        assertThat(item(forecast,energy,DEC)).satisfies(o->{assertThat(o.amount()).isEqualTo("195.00");
            assertThat(o.state()).isEqualTo("FORECAST");assertThat(o.estimated()).isTrue();});
        assertThat(item(forecast,energy,NOV).estimated()).isTrue();

        // A later confirmation becomes the reference of later periods only.
        tx.execute(s->expenses.confirmCharge("admin@example.com",new ConfirmChargeCommand(november,1,"210.00",UUID.randomUUID())));
        assertThat(item(recurrences.forecasts("admin@example.com").occurrences(),energy,DEC).amount()).isEqualTo("210.00");
        assertThat(expense(october.occurrence().expenseId()).amount()).isEqualByComparingTo("195.00");

        // Correcting the older confirmation is individual and does not replace the more recent reference.
        tx.execute(s->expenses.correct("guest@example.com",new CorrectExpenseCommand(october.occurrence().expenseId(),1,
                ExpenseStatus.PENDING,"Energia","150.00",OCT,null,null,null,null,null,UUID.randomUUID())));
        assertThat(expense(november).amount()).isEqualByComparingTo("210.00");
        assertThat(item(recurrences.forecasts("admin@example.com").occurrences(),energy,DEC).amount()).isEqualTo("210.00");
        var history=expenses.get("admin@example.com",october.occurrence().expenseId()).history();
        assertThat(history).extracting(ExpenseHistoryEvent::type)
                .containsExactly("EXPENSE_CREATED","CHARGE_CONFIRMED","EXPENSE_CORRECTED");
        assertThat(history.get(1).changes()).singleElement().satisfies(change->{
            assertThat(change.previousValue()).isEqualTo("180.00");assertThat(change.currentValue()).isEqualTo("195.00");});
        assertThat(history.get(1).actorUserId()).isEqualTo(GUEST);
        assertThat(expenses.get("admin@example.com",november).history()).extracting(ExpenseHistoryEvent::type)
                .containsExactly("EXPENSE_CREATED","ESTIMATE_UPDATED","CHARGE_CONFIRMED");
    }

    @Test void correctingTheLatestConfirmationRefreshesLaterEstimatesButNeverEarlierOrConfirmedOnes() {
        var water=variable("Água","100.00",SEP);
        var september=generateCurrentMonth(NOW);
        var october=recurrences.anticipate("admin@example.com",water,OCT,UUID.randomUUID()).occurrence().expenseId();
        var november=recurrences.anticipate("admin@example.com",water,NOV,UUID.randomUUID()).occurrence().expenseId();
        tx.execute(s->expenses.confirmCharge("admin@example.com",new ConfirmChargeCommand(october,0,"120.00",UUID.randomUUID())));
        assertThat(expense(november).amount()).isEqualByComparingTo("120.00");
        assertThat(expense(september).amount()).isEqualByComparingTo("100.00");

        tx.execute(s->expenses.correct("admin@example.com",new CorrectExpenseCommand(october,1,ExpenseStatus.PENDING,
                "Água","90.00",OCT,null,null,null,null,null,UUID.randomUUID())));

        assertThat(expense(november).amount()).isEqualByComparingTo("90.00");
        assertThat(expense(november).chargeConfirmed()).isFalse();
        assertThat(expense(september).amount()).isEqualByComparingTo("100.00");
        assertThat(expense(september).chargeConfirmed()).isFalse();
        assertThat(expense(october).chargeConfirmation().estimatedAmount()).isEqualByComparingTo("100.00");
    }

    @Test void laterGenerationStartsFromTheReferenceAndNeverOverwritesOrDuplicatesAConfirmedCharge() {
        var gas=variable("Gás","80.00",SEP);
        var september=generateCurrentMonth(NOW);
        tx.execute(s->expenses.confirmCharge("guest@example.com",new ConfirmChargeCommand(september,0,"64.30",UUID.randomUUID())));
        jdbc.update("update recurrence_generation_jobs set status='PENDING',completed_at=null,available_at=? where recurrence_id=?",
                Timestamp.from(NOW),gas);
        generateCurrentMonth(NOW);
        assertThat(expense(september).amount()).isEqualByComparingTo("64.30");
        assertThat(expense(september).chargeConfirmed()).isTrue();

        var october=generateCurrentMonth(Instant.parse("2026-10-01T12:00:00Z"));

        assertThat(expense(october).amount()).isEqualByComparingTo("64.30");
        assertThat(expense(october).chargeConfirmed()).isFalse();
        assertThat(count("select count(*) from expense_entries")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select amount from recurrence_definitions where id=?",BigDecimal.class,gas))
                .isEqualByComparingTo("80.00");
    }

    @Test void settlementRequiresConfirmationIndividuallyAndInBatchAndPaidAmountNeverBecomesTheReference() {
        var phone=variable("Telefone","150.00",SEP);
        var october=recurrences.anticipate("admin@example.com",phone,OCT,UUID.randomUUID()).occurrence().expenseId();
        var november=recurrences.anticipate("admin@example.com",phone,NOV,UUID.randomUUID()).occurrence().expenseId();
        var oneOff=tx.execute(s->expenses.create("admin@example.com",new CreateOneOffExpenseCommand("Mercado","50.00",
                ExpenseStatus.PENDING,OCT,null,null,UUID.randomUUID()))).expense().id();

        assertThatThrownBy(()->tx.execute(s->expenses.settle("guest@example.com",new SettleExpenseCommand(october,0,"150.00",
                LocalDate.of(2026,10,1),GUEST,null,UUID.randomUUID())))).isInstanceOf(ChargeConfirmationRequiredException.class);
        assertThatThrownBy(()->tx.execute(s->expenses.settleBatch("guest@example.com",new BatchSettlementCommand(List.of(
                new BatchSettlementItem(oneOff,0),new BatchSettlementItem(october,0)),LocalDate.of(2026,10,1),GUEST,true,
                UUID.randomUUID())))).isInstanceOfSatisfying(BatchSettlementConflictException.class,error->
                assertThat(error.problems()).singleElement().satisfies(problem->{
                    assertThat(problem.expenseId()).isEqualTo(october);assertThat(problem.code()).isEqualTo("AMOUNT_UNCONFIRMED");}));
        assertThat(expense(oneOff).status()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(count("select count(*) from expense_payment_events")).isZero();

        var key=UUID.randomUUID();
        var settle=new SettleExpenseCommand(october,0,"205.00",LocalDate.of(2026,10,1),GUEST,"Com juros",key,"200");
        var paid=tx.execute(s->expenses.settle("admin@example.com",settle)).expense();
        var replay=tx.execute(s->expenses.settle("admin@example.com",settle));

        assertThat(replay.replayed()).isTrue();
        assertThat(paid.status()).isEqualTo(ExpenseStatus.PAID);
        assertThat(paid.amount()).isEqualTo("200.00"); assertThat(paid.paidAmount()).isEqualTo("205.00");
        assertThat(paid.chargeConfirmed()).isTrue(); assertThat(paid.chargeConfirmation().estimatedAmount()).isEqualTo("150.00");
        assertThat(paid.paidByUserId()).isEqualTo(GUEST); assertThat(paid.version()).isEqualTo(2);
        assertThat(expense(november).amount()).isEqualByComparingTo("200.00");
        assertThat(count("select count(*) from expense_payment_events")).isEqualTo(1);
        assertThat(count("select count(*) from expense_charge_events where event_type='CHARGE_CONFIRMED'")).isEqualTo(1);
        assertThat(expenses.get("admin@example.com",october).history()).extracting(ExpenseHistoryEvent::type)
                .containsExactly("EXPENSE_CREATED","CHARGE_CONFIRMED","EXPENSE_PAID");

        assertThatThrownBy(()->tx.execute(s->expenses.settle("admin@example.com",new SettleExpenseCommand(oneOff,0,"50.00",
                LocalDate.of(2026,10,1),GUEST,null,UUID.randomUUID(),"50.00")))).isInstanceOf(ChargeAlreadyConfirmedException.class);
        assertThat(expense(oneOff).status()).isEqualTo(ExpenseStatus.PENDING);

        tx.execute(s->expenses.confirmCharge("guest@example.com",new ConfirmChargeCommand(november,1,"199.99",UUID.randomUUID())));
        var batch=tx.execute(s->expenses.settleBatch("guest@example.com",new BatchSettlementCommand(List.of(
                new BatchSettlementItem(oneOff,0),new BatchSettlementItem(november,2)),LocalDate.of(2026,10,2),ADMIN,true,
                UUID.randomUUID())));
        assertThat(batch.items()).extracting(BatchSettlementItemResult::paidAmount).containsExactlyInAnyOrder("50.00","199.99");
    }

    @Test void rejectsInvalidAmountsStatesVersionsRepeatedConfirmationsForeignSpacesAndInactiveMembers() {
        var internet=variable("Internet","99.90",SEP);
        var october=recurrences.anticipate("admin@example.com",internet,OCT,UUID.randomUUID()).occurrence().expenseId();
        for(var invalid:List.of("0","0.00","-5","1.001","100000000.00","dez"))
            assertThatThrownBy(()->tx.execute(s->expenses.confirmCharge("admin@example.com",
                    new ConfirmChargeCommand(october,0,invalid,UUID.randomUUID())))).isInstanceOf(ExpenseValidationException.class);
        assertThatThrownBy(()->tx.execute(s->expenses.confirmCharge("admin@example.com",
                new ConfirmChargeCommand(october,3,"99.00",UUID.randomUUID())))).isInstanceOf(ExpenseStateConflictException.class);
        assertThatThrownBy(()->tx.execute(s->expenses.confirmCharge("other@example.com",
                new ConfirmChargeCommand(october,0,"99.00",UUID.randomUUID())))).isInstanceOf(ExpenseNotFoundException.class);
        assertThatThrownBy(()->recurrences.confirmForecastCharge("other@example.com",internet,NOV,"99.00",UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        assertThatThrownBy(()->tx.execute(s->expenses.correct("admin@example.com",new CorrectExpenseCommand(october,0,
                ExpenseStatus.PENDING,"Internet","120.00",OCT,null,null,null,null,null,UUID.randomUUID()))))
                .isInstanceOfSatisfying(ExpenseValidationException.class,error->assertThat(error.field()).isEqualTo("amount"));
        var renamed=tx.execute(s->expenses.correct("admin@example.com",new CorrectExpenseCommand(october,0,
                ExpenseStatus.PENDING,"Internet fibra","99.90",LocalDate.of(2026,10,6),null,null,null,null,null,UUID.randomUUID())))
                .expense();
        assertThat(renamed.chargeConfirmed()).isFalse();

        var minimum=tx.execute(s->expenses.confirmCharge("guest@example.com",new ConfirmChargeCommand(october,1,"0.01",UUID.randomUUID()))).expense();
        assertThat(minimum.amount()).isEqualTo("0.01"); assertThat(minimum.dueDate()).isEqualTo(LocalDate.of(2026,10,6));
        assertThatThrownBy(()->tx.execute(s->expenses.confirmCharge("admin@example.com",
                new ConfirmChargeCommand(october,2,"10.00",UUID.randomUUID())))).isInstanceOf(ChargeAlreadyConfirmedException.class);
        var maximum=recurrences.confirmForecastCharge("admin@example.com",internet,NOV,"99999999.99",UUID.randomUUID());
        assertThat(maximum.occurrence().amount()).isEqualTo("99999999.99");

        var oneOff=tx.execute(s->expenses.create("admin@example.com",new CreateOneOffExpenseCommand("Avulsa","10.00",
                ExpenseStatus.PENDING,OCT,null,null,UUID.randomUUID()))).expense().id();
        assertThatThrownBy(()->tx.execute(s->expenses.confirmCharge("admin@example.com",
                new ConfirmChargeCommand(oneOff,0,"11.00",UUID.randomUUID())))).isInstanceOf(ChargeAlreadyConfirmedException.class);

        var december=recurrences.anticipate("admin@example.com",internet,DEC,UUID.randomUUID()).occurrence().expenseId();
        tx.execute(s->expenses.cancel("admin@example.com",new CancelExpenseCommand(december,0,"Isento no mês",UUID.randomUUID())));
        assertThatThrownBy(()->tx.execute(s->expenses.confirmCharge("admin@example.com",
                new ConfirmChargeCommand(december,1,"10.00",UUID.randomUUID())))).isInstanceOf(ExpenseStateConflictException.class);
        assertThatThrownBy(()->recurrences.confirmForecastCharge("admin@example.com",
                fixed("Aluguel","1000.00",SEP),OCT,"1000.00",UUID.randomUUID())).isInstanceOf(RecurrenceOccurrenceException.class);

        jdbc.update("update space_memberships set active=false,ended_at=?,ended_by_user_id=?,end_reason='ADMIN_REMOVAL' where user_id=?",
                Timestamp.from(NOW),ADMIN,GUEST);
        assertThatThrownBy(()->tx.execute(s->expenses.confirmCharge("guest@example.com",
                new ConfirmChargeCommand(december,1,"10.00",UUID.randomUUID())))).isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThat(count("select count(*) from expense_charge_events where event_type='CHARGE_CONFIRMED'")).isEqualTo(2);
    }

    @Test void concurrentConfirmationsOrConfirmationAgainstCorrectionOrSettlementKeepOnlyTheFirstCommit() throws Exception {
        var energy=variable("Energia","180.00",SEP);
        var october=recurrences.anticipate("admin@example.com",energy,OCT,UUID.randomUUID()).occurrence().expenseId();
        var outcomes=race(
                ()->tx.execute(s->expenses.confirmCharge("admin@example.com",new ConfirmChargeCommand(october,0,"190.00",UUID.randomUUID()))),
                ()->tx.execute(s->expenses.confirmCharge("guest@example.com",new ConfirmChargeCommand(october,0,"170.00",UUID.randomUUID()))));
        assertThat(outcomes).filteredOn(o->o instanceof ExpenseCreationResult).hasSize(1);
        assertThat(outcomes).filteredOn(o->o instanceof ExpenseStateConflictException||o instanceof ChargeAlreadyConfirmedException).hasSize(1);
        assertThat(count("select count(*) from expense_charge_events where event_type='CHARGE_CONFIRMED'")).isEqualTo(1);
        assertThat(expense(october).version()).isEqualTo(1);

        var november=recurrences.anticipate("admin@example.com",energy,NOV,UUID.randomUUID()).occurrence().expenseId();
        var version=expense(november).version();
        var mixed=race(
                ()->tx.execute(s->expenses.confirmCharge("admin@example.com",new ConfirmChargeCommand(november,version,"181.00",UUID.randomUUID()))),
                ()->tx.execute(s->expenses.correct("guest@example.com",new CorrectExpenseCommand(november,version,
                        ExpenseStatus.PENDING,"Energia corrigida",expense(november).amount().toPlainString(),NOV,null,null,null,null,
                        null,UUID.randomUUID()))));
        assertThat(mixed).filteredOn(o->o instanceof ExpenseCreationResult).hasSize(1);
        assertThat(mixed).filteredOn(o->o instanceof ExpenseStateConflictException).hasSize(1);
        assertThat(expense(november).version()).isEqualTo(version+1);

        var december=recurrences.anticipate("admin@example.com",energy,DEC,UUID.randomUUID()).occurrence().expenseId();
        var decemberVersion=expense(december).version();
        var settling=race(
                ()->tx.execute(s->expenses.confirmCharge("admin@example.com",new ConfirmChargeCommand(december,decemberVersion,"182.00",UUID.randomUUID()))),
                ()->tx.execute(s->expenses.settle("guest@example.com",new SettleExpenseCommand(december,decemberVersion,"183.00",
                        DEC,GUEST,null,UUID.randomUUID(),"183.00"))));
        assertThat(settling).filteredOn(o->o instanceof ExpenseCreationResult).hasSize(1);
        assertThat(settling).filteredOn(o->o instanceof ChargeAlreadyConfirmedException).hasSize(1);
        assertThat(count("select count(*) from expense_charge_events where event_type='CHARGE_CONFIRMED' and expense_id='"+december+"'"))
                .isEqualTo(1);
        var settled=expense(december);
        if(settled.status()==ExpenseStatus.PAID){
            assertThat(settled.amount()).isEqualByComparingTo("183.00"); assertThat(settled.version()).isEqualTo(decemberVersion+2);
        } else {
            assertThat(settled.amount()).isEqualByComparingTo("182.00"); assertThat(settled.version()).isEqualTo(decemberVersion+1);
            assertThat(settled.paidAmount()).isNull();
        }
    }

    @Test void auditFailureRollsBackConfirmationPropagationMaterializationAndIdempotencyThenRetrySucceeds() {
        var energy=variable("Energia","180.00",SEP);
        var november=recurrences.anticipate("admin@example.com",energy,NOV,UUID.randomUUID()).occurrence().expenseId();
        jdbc.execute("alter table expense_charge_events add constraint force_failure check(event_type<>'ESTIMATE_UPDATED')");
        var key=UUID.randomUUID();
        assertThatThrownBy(()->recurrences.confirmForecastCharge("guest@example.com",energy,OCT,"195.00",key))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("select count(*) from expense_entries")).isEqualTo(1);
        assertThat(count("select count(*) from recurrence_occurrences")).isEqualTo(1);
        assertThat(count("select count(*) from expense_charge_events")).isZero();
        assertThat(count("select count(*) from expense_idempotency_requests where operation='CONFIRM_CHARGE'")).isZero();
        assertThat(expense(november).amount()).isEqualByComparingTo("180.00");
        assertThat(expense(november).version()).isZero();

        jdbc.execute("alter table expense_charge_events drop constraint force_failure");
        var retry=recurrences.confirmForecastCharge("guest@example.com",energy,OCT,"195.00",key);
        assertThat(retry.replayed()).isFalse();
        assertThat(expense(november).amount()).isEqualByComparingTo("195.00");
    }

    @Test void listingAndDetailIdentifyEstimatesAndConfirmationsConsistently() {
        var energy=variable("Energia","180.00",SEP);
        var october=recurrences.anticipate("admin@example.com",energy,OCT,UUID.randomUUID()).occurrence().expenseId();
        var query=new ExpenseListQuery(0,20,ExpenseSort.REFERENCE_DATE,SortDirection.ASC,null,OCT,OCT,
                ExpenseDateBasis.DUE_DATE,null,false,null,false,null,ExpenseStatusFilter.ACTIVE,null);
        assertThat(expenses.list("guest@example.com",query).content()).singleElement().satisfies(item->{
            assertThat(item.chargeConfirmed()).isFalse(); assertThat(item.chargeConfirmation()).isNull();
            assertThat(item.amount()).isEqualTo("180.00");});
        tx.execute(s->expenses.confirmCharge("guest@example.com",new ConfirmChargeCommand(october,0,"180.00",UUID.randomUUID())));
        assertThat(expenses.list("admin@example.com",query).content()).singleElement().satisfies(item->{
            assertThat(item.chargeConfirmed()).isTrue(); assertThat(item.amount()).isEqualTo("180.00");
            assertThat(item.chargeConfirmation().estimatedAmount()).isEqualTo("180.00");
            assertThat(item.chargeConfirmation().confirmedByDisplayName()).isEqualTo("Convidado");});
        assertThat(item(recurrences.forecasts("admin@example.com").occurrences(),energy,OCT).chargeConfirmed()).isTrue();
        assertThatThrownBy(()->jdbc.update("update expense_entries set estimated_charge_amount=null where id=?",october))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("""
                insert into expense_charge_events(id,expense_id,space_id,event_type,actor_user_id,occurred_at,from_version,
                    to_version,previous_amount,new_amount) values(?,?,?,'CHARGE_CONFIRMED',?,?,5,6,1,2)
                """,UUID.randomUUID(),october,SPACE,ADMIN,Timestamp.from(NOW)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
    private UUID generateCurrentMonth(Instant at) {
        new JdbcRecurrenceGenerationJob(jdbc,tx,new JdbcRecurringExpenseMaterializer(jdbc,tx),Clock.fixed(at,ZoneOffset.UTC),Duration.ofMinutes(2),25).poll();
        var month=YearMonth.from(at.atZone(ZoneId.of("America/Sao_Paulo")));
        return jdbc.queryForObject("select expense_id from recurrence_occurrences where scheduled_due_date between ? and ?",
                UUID.class,month.atDay(1),month.atEndOfMonth());
    }
    private ForecastView item(List<ForecastView> forecast,UUID recurrence,LocalDate date) {
        return forecast.stream().filter(o->o.recurrenceId().equals(recurrence)&&o.scheduledDueDate().equals(date)).findFirst().orElseThrow();
    }
    private StoredExpense expense(UUID id) { return new JdbcExpenseRepository(jdbc).findById(SPACE,id); }
    private int count(String sql) { return jdbc.queryForObject(sql,Integer.class); }
    private UUID variable(String description,String amount,LocalDate first) {
        return recurrences.create("admin@example.com",new CreateRecurrenceCommand(description,amount,
                RecurrenceValueType.VARIABLE_ESTIMATE,RecurrenceFrequency.MONTHLY,first,null,null,null,UUID.randomUUID())).recurrence().id();
    }
    private UUID fixed(String description,String amount,LocalDate first) {
        return recurrences.create("admin@example.com",new CreateRecurrenceCommand(description,amount,
                RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,first,null,null,null,UUID.randomUUID())).recurrence().id();
    }
    private AuthenticatedUserContextRepository contextRepository() { return email -> jdbc.query("""
        select u.id,u.display_name,u.normalized_email,s.id,s.name,m.role,s.currency_code,s.locale,s.time_zone
          from identity_users u join space_memberships m on m.user_id=u.id and m.active=true join family_spaces s on s.id=m.space_id
         where u.normalized_email=?
        """,(rs,row)->new AuthenticatedUserContext(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),SpaceRole.valueOf(rs.getString(6)),rs.getString(7),rs.getString(8),rs.getString(9)),email).stream().findFirst(); }
    private void insertSpace(UUID id,String name){jdbc.update("insert into family_spaces(id,name,currency_code,locale,time_zone,created_at) values (?,?,'BRL','pt-BR','America/Sao_Paulo',?)",id,name,Timestamp.from(NOW));}
    private void insertUser(UUID id,String name,String email,UUID space,String role){jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,created_at) values (?,?,?,'{test}x',true,?)",id,name,email,Timestamp.from(NOW));jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at) values (?,?,?,?,true,?)",UUID.randomUUID(),id,space,role,Timestamp.from(NOW));}
    private static final class NoCategoryRepository implements com.malyah.accountmanager.expenses.application.port.CategoryRepository {
        public List<CategoryView> findAll(UUID s,boolean a){return List.of();}
        public CategoryView create(UUID a,UUID b,UUID c,com.malyah.accountmanager.expenses.domain.CategoryName d,Instant e){throw new UnsupportedOperationException();}
        public CategoryView rename(UUID a,UUID b,UUID c,long d,com.malyah.accountmanager.expenses.domain.CategoryName e,Instant f){throw new UnsupportedOperationException();}
        public CategoryView archive(UUID a,UUID b,UUID c,long d,Instant e){throw new UnsupportedOperationException();}
        public void requireSelectable(UUID space,UUID category){if(category!=null)throw new AssertionError("category not expected");}
    }
}
