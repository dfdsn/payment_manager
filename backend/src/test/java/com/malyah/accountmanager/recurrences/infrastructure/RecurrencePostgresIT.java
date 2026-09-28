package com.malyah.accountmanager.recurrences.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
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
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.*;
import com.malyah.accountmanager.identity.application.port.AuthenticatedUserContextRepository;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.recurrences.application.*;
import com.malyah.accountmanager.recurrences.domain.*;

@Testcontainers
class RecurrencePostgresIT {
    private static final Instant NOW=Instant.parse("2026-09-27T15:00:00Z");
    private static final UUID SPACE=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER=UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN=UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID GUEST=UUID.fromString("10000000-0000-0000-0000-000000000003");
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("account_manager_recurrence_test").withUsername("account_manager").withPassword("test-only-password");
    private JdbcTemplate jdbc; private RecurrenceUseCase useCase;

    @BeforeEach void reset() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var flyway=Flyway.configure().dataSource(ds).cleanDisabled(false).load(); flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(17);
        jdbc=new JdbcTemplate(ds); insertSpace(SPACE,"Casa"); insertUser(ADMIN,"Admin","admin@example.com",SPACE,"ADMINISTRATOR");
        insertUser(GUEST,"Convidado","guest@example.com",SPACE,"GUEST"); insertSpace(OTHER,"Outra");
        var context=new AuthenticatedUserContextService(contextRepository());
        CategoryRepository categories=new NoCategoryRepository();
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var service=new RecurrenceService(new JdbcRecurrenceRepository(jdbc),context,categories,
                new com.malyah.accountmanager.identity.infrastructure.JdbcFinancialMemberAccess(jdbc),Clock.fixed(NOW,ZoneOffset.UTC),UUID::randomUUID,new RecurrenceCalendar(),
                new com.malyah.accountmanager.expenses.infrastructure.JdbcRecurringExpenseMaterializer(jdbc,transaction));
        useCase=new TransactionalRecurrenceUseCase(service,transaction);
    }

    @Test void persistsReplaysListsAndReproducesTheBaseDayCalendarWithoutExpenses() {
        var key=UUID.randomUUID(); var command=command(key,"500.00",RecurrenceValueType.FIXED,ADMIN);
        var created=useCase.create("guest@example.com",command);
        var replay=useCase.create("guest@example.com",command);
        assertThat(replay.replayed()).isTrue(); assertThat(replay.recurrence().id()).isEqualTo(created.recurrence().id());
        assertThat(created.recurrence().previewDates()).startsWith(LocalDate.of(2027,1,31),LocalDate.of(2027,2,28),LocalDate.of(2027,3,31));
        assertThat(useCase.list("admin@example.com")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from recurrence_definitions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from recurrence_audit_events",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from expense_entries",Integer.class)).isZero();
    }

    @Test void rejectsChangedReplayAndInactiveOrForeignResponsibleWithoutPartialPersistence() {
        var key=UUID.randomUUID(); useCase.create("admin@example.com",command(key,"10",RecurrenceValueType.VARIABLE_ESTIMATE,null));
        assertThatThrownBy(() -> useCase.create("admin@example.com",command(key,"11",RecurrenceValueType.VARIABLE_ESTIMATE,null)))
                .isInstanceOf(RecurrenceIdempotencyConflictException.class);
        jdbc.update("update space_memberships set active=false,ended_at=?,ended_by_user_id=?,end_reason='ADMIN_REMOVAL' where user_id=?",
                Timestamp.from(NOW),ADMIN,GUEST);
        assertThatThrownBy(() -> useCase.create("admin@example.com",command(UUID.randomUUID(),"12",RecurrenceValueType.FIXED,GUEST)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from recurrence_definitions",Integer.class)).isEqualTo(1);
    }

    @Test void forecastsThirteenMonthsWithoutWritingAndAnticipatesOnceWithStableIdentity() {
        var created=useCase.create("admin@example.com",new CreateRecurrenceCommand("Internet","149.90",
                RecurrenceValueType.VARIABLE_ESTIMATE,RecurrenceFrequency.MONTHLY,LocalDate.of(2026,10,3),null,
                null,null,UUID.randomUUID()));
        var before=jdbc.queryForObject("select count(*) from expense_entries",Integer.class);
        var forecast=useCase.forecasts("guest@example.com");
        assertThat(forecast.from()).isEqualTo(YearMonth.of(2026,9));
        assertThat(forecast.to()).isEqualTo(YearMonth.of(2027,9));
        assertThat(forecast.occurrences()).hasSize(12).allSatisfy(item->{
            assertThat(item.state()).isEqualTo("FORECAST"); assertThat(item.estimated()).isTrue();
        });
        assertThat(jdbc.queryForObject("select count(*) from expense_entries",Integer.class)).isEqualTo(before);

        var key=UUID.randomUUID();
        var anticipated=useCase.anticipate("guest@example.com",created.recurrence().id(),LocalDate.of(2026,10,3),key);
        var replay=useCase.anticipate("guest@example.com",created.recurrence().id(),LocalDate.of(2026,10,3),key);
        assertThat(anticipated.replayed()).isFalse();assertThat(replay.replayed()).isTrue();
        assertThat(replay.occurrence().expenseId()).isEqualTo(anticipated.occurrence().expenseId());
        assertThat(replay.occurrence().state()).isEqualTo("MATERIALIZED");
        assertThat(replay.occurrence().chargeConfirmed()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from expense_entries",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from recurrence_occurrence_events",Integer.class)).isEqualTo(1);
    }

    @Test void realExpenseWinsInForecastAndInvalidOrChangedIdempotentRequestsDoNotWrite() {
        var created=useCase.create("admin@example.com",new CreateRecurrenceCommand("Seguro","200.00",
                RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,LocalDate.of(2026,10,31),null,
                null,null,UUID.randomUUID()));
        var key=UUID.randomUUID();
        var result=useCase.anticipate("admin@example.com",created.recurrence().id(),LocalDate.of(2026,10,31),key);
        jdbc.update("""
                update expense_entries set due_date=?,reference_date=?,status='CANCELLED',cancelled_at=?,
                    cancelled_by_user_id=?,cancellation_reason='Teste de reconciliação',version=version+1 where id=?
                """,LocalDate.of(2026,10,30),LocalDate.of(2026,10,30),Timestamp.from(NOW),ADMIN,
                result.occurrence().expenseId());
        var actual=useCase.forecasts("admin@example.com").occurrences().stream()
                .filter(item->item.recurrenceId().equals(created.recurrence().id())).findFirst().orElseThrow();
        assertThat(actual.state()).isEqualTo("MATERIALIZED");
        assertThat(actual.actualDueDate()).isEqualTo(LocalDate.of(2026,10,30));
        assertThat(actual.expenseStatus()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->useCase.anticipate("admin@example.com",created.recurrence().id(),LocalDate.of(2026,11,30),key))
                .isInstanceOf(RecurrenceIdempotencyConflictException.class);
        assertThatThrownBy(()->useCase.anticipate("admin@example.com",created.recurrence().id(),LocalDate.of(2026,11,29),UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        assertThat(jdbc.queryForObject("select count(*) from expense_entries",Integer.class)).isEqualTo(1);
    }

    @Test void concurrentAnticipationsConvergeOnOneExpenseAndOneMaterializationEvent() throws Exception {
        var created=useCase.create("admin@example.com",new CreateRecurrenceCommand("IPTU","900.00",
                RecurrenceValueType.FIXED,RecurrenceFrequency.ANNUAL,LocalDate.of(2026,12,10),null,
                null,null,UUID.randomUUID()));
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->{start.await();return useCase.anticipate("admin@example.com",
                    created.recurrence().id(),LocalDate.of(2026,12,10),UUID.randomUUID());});
            var second=executor.submit(()->{start.await();return useCase.anticipate("guest@example.com",
                    created.recurrence().id(),LocalDate.of(2026,12,10),UUID.randomUUID());});
            start.countDown();
            assertThat(first.get().occurrence().expenseId()).isEqualTo(second.get().occurrence().expenseId());
        }
        assertThat(jdbc.queryForObject("select count(*) from expense_entries",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from recurrence_occurrence_events",Integer.class)).isEqualTo(1);
    }

    private CreateRecurrenceCommand command(UUID key,String amount,RecurrenceValueType type,UUID responsible) {
        return new CreateRecurrenceCommand("Condomínio",amount,type,RecurrenceFrequency.MONTHLY,
                LocalDate.of(2027,1,31),LocalDate.of(2027,4,30),null,responsible,key);
    }
    private AuthenticatedUserContextRepository contextRepository() { return email -> jdbc.query("""
        select u.id,u.display_name,u.normalized_email,s.id,s.name,m.role,s.currency_code,s.locale,s.time_zone
          from identity_users u join space_memberships m on m.user_id=u.id and m.active=true join family_spaces s on s.id=m.space_id
         where u.normalized_email=?
        """,(rs,row)->new AuthenticatedUserContext(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),SpaceRole.valueOf(rs.getString(6)),rs.getString(7),rs.getString(8),rs.getString(9)),email).stream().findFirst(); }
    private void insertSpace(UUID id,String name){jdbc.update("insert into family_spaces(id,name,currency_code,locale,time_zone,created_at) values (?,?,'BRL','pt-BR','America/Sao_Paulo',?)",id,name,Timestamp.from(NOW));}
    private void insertUser(UUID id,String name,String email,UUID space,String role){jdbc.update("insert into identity_users(id,display_name,normalized_email,password_hash,email_confirmed,created_at) values (?,?,?,'{test}x',true,?)",id,name,email,Timestamp.from(NOW));jdbc.update("insert into space_memberships(id,user_id,space_id,role,active,created_at) values (?,?,?,?,true,?)",UUID.randomUUID(),id,space,role,Timestamp.from(NOW));}
    private static final class NoCategoryRepository implements CategoryRepository {
        public java.util.List<com.malyah.accountmanager.expenses.application.CategoryView> findAll(UUID s,boolean a){return java.util.List.of();}
        public com.malyah.accountmanager.expenses.application.CategoryView create(UUID a,UUID b,UUID c,com.malyah.accountmanager.expenses.domain.CategoryName d,Instant e){throw new UnsupportedOperationException();}
        public com.malyah.accountmanager.expenses.application.CategoryView rename(UUID a,UUID b,UUID c,long d,com.malyah.accountmanager.expenses.domain.CategoryName e,Instant f){throw new UnsupportedOperationException();}
        public com.malyah.accountmanager.expenses.application.CategoryView archive(UUID a,UUID b,UUID c,long d,Instant e){throw new UnsupportedOperationException();}
        public void requireSelectable(UUID space,UUID category){if(category!=null)throw new AssertionError("category not expected");}
    }
}
