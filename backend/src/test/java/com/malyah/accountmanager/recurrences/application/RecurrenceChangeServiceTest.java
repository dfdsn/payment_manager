package com.malyah.accountmanager.recurrences.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceAdjuster;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceAdjustment;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceSnapshot;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.recurrences.application.port.RecurrenceRepository;
import com.malyah.accountmanager.recurrences.domain.*;

class RecurrenceChangeServiceTest {
    private static final UUID SPACE=UUID.randomUUID(), ACTOR=UUID.randomUUID(), RESPONSIBLE=UUID.randomUUID(), ID=UUID.randomUUID(),
            CHANGE=UUID.randomUUID(), CATEGORY=UUID.randomUUID();
    private static final Instant NOW=Instant.parse("2026-09-27T12:00:00Z");
    private static final String EMAIL="ana@example.com";
    private RecurrenceRepository repository; private CategoryRepository categories; private FinancialMemberAccess members;
    private RecurringOccurrenceAdjuster adjuster; private RecurrenceService service;
    private final UUID oct=UUID.randomUUID(), nov=UUID.randomUUID(), dec=UUID.randomUUID();

    @BeforeEach void setup() {
        repository=mock(RecurrenceRepository.class); categories=mock(CategoryRepository.class); members=mock(FinancialMemberAccess.class);
        adjuster=mock(RecurringOccurrenceAdjuster.class);
        service=new RecurrenceService(repository,email->new AuthenticatedUserContext(ACTOR,"Ana",email,SPACE,"Casa",SpaceRole.GUEST,
                "BRL","pt-BR","America/Sao_Paulo"),categories,members,Clock.fixed(NOW,ZoneOffset.UTC),()->CHANGE,
                new RecurrenceCalendar(),mock(RecurringExpenseMaterializer.class),null,adjuster);
        when(repository.loadSchedule(eq(SPACE),eq(ID),any())).thenReturn(stored(RecurrenceValueType.FIXED,0,null));
        when(repository.claimChange(any(),any(),any(),anyString(),any(),any())).thenReturn(new ChangeClaim(false,null));
        when(repository.findChanges(SPACE)).thenReturn(Map.of());
        when(repository.findChange(SPACE,CHANGE)).thenReturn(new RecurrenceChangeView(CHANGE,"CHANGE",ACTOR,"Ana",NOW,1,
                LocalDate.of(2026,10,10),List.of("amount"),null,1,0,0,1));
        when(adjuster.occurrences(eq(SPACE),eq(ID),any())).thenReturn(List.of(
                snapshot(oct,LocalDate.of(2026,10,10),"PENDING",true,"1000.00",LocalDate.of(2026,10,10),false),
                snapshot(nov,LocalDate.of(2026,11,10),"PAID",true,"1000.00",LocalDate.of(2026,11,10),false),
                snapshot(dec,LocalDate.of(2026,12,10),"PENDING",true,"1000.00",LocalDate.of(2026,12,18),true)));
    }

    @Test void previewComputesEffectsAndForecastsWithoutWriting() {
        var impact=service.previewChange(EMAIL,change(0,"1100.00",RecurrenceFrequency.MONTHLY,20,null,null,null));

        assertThat(impact.operation()).isEqualTo("CHANGE");
        assertThat(impact.effectiveDueDate()).isEqualTo(LocalDate.of(2026,10,10));
        assertThat(impact.changedFields()).containsExactly("amount","dueDay");
        assertThat(impact.impactToken()).hasSize(64);
        assertThat(impact.occurrences()).extracting(ImpactOccurrenceView::expenseId,ImpactOccurrenceView::action)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(oct,"UPDATE"),org.assertj.core.groups.Tuple.tuple(nov,"PRESERVE"),
                        org.assertj.core.groups.Tuple.tuple(dec,"UPDATE"));
        assertThat(impact.occurrences().getFirst().changes()).extracting(ImpactFieldChange::field,ImpactFieldChange::previousValue,
                ImpactFieldChange::newValue).containsExactly(org.assertj.core.groups.Tuple.tuple("amount","1000.00","1100.00"),
                org.assertj.core.groups.Tuple.tuple("dueDate","2026-10-10","2026-10-20"));
        assertThat(impact.occurrences().get(2).preservedFields()).containsExactly("dueDate");
        assertThat(impact.occurrences().get(1).reason()).isEqualTo("PAID");
        assertThat(impact.occurrences().getFirst().chargeConfirmed()).isFalse();
        assertThat(impact.forecasts()).hasSize(9).first().satisfies(f->{
            assertThat(f.month()).isEqualTo(LocalDate.of(2027,1,1)); assertThat(f.action()).isEqualTo("CHANGED");
            assertThat(f.previousDueDate()).isEqualTo(LocalDate.of(2027,1,10)); assertThat(f.newDueDate()).isEqualTo(LocalDate.of(2027,1,20));
            assertThat(f.previousAmount()).isEqualTo("1000.00"); assertThat(f.newAmount()).isEqualTo("1100.00");
            assertThat(f.previousDescription()).isEqualTo("Aluguel"); assertThat(f.newDescription()).isEqualTo("Aluguel");});
        assertThat(impact.updatedCount()).isEqualTo(2); assertThat(impact.preservedCount()).isOne();
        assertThat(impact.removedCount()).isZero(); assertThat(impact.reviewCount()).isZero();
        assertThat(service.previewChange(EMAIL,change(0,"1100.00",RecurrenceFrequency.MONTHLY,20,null,null,null)).impactToken())
                .isEqualTo(impact.impactToken());
        assertThat(service.previewChange(EMAIL,change(0,"1100.00",RecurrenceFrequency.MONTHLY,21,null,null,null)).impactToken())
                .isNotEqualTo(impact.impactToken());
        verify(repository,atLeastOnce()).loadSchedule(SPACE,ID,ScheduleLock.NONE);
        verify(adjuster,atLeastOnce()).occurrences(SPACE,ID,null);
        verify(members,atLeastOnce()).requireActiveParticipants(SPACE,ACTOR,null);
        verify(repository,never()).saveChange(any()); verify(adjuster,never()).apply(any(),any(),any(),any(),any());
        verifyNoInteractions(categories);
    }

    @Test void applyingLocksRecomputesAndPersistsTheChangeTheAdjustmentsAndTheIdempotencyRecordInOrder() {
        var command=change(0,"1100.00",RecurrenceFrequency.MONTHLY,20,CATEGORY,RESPONSIBLE,null);
        var token=service.previewChange(EMAIL,command).impactToken();
        var key=UUID.randomUUID();

        var result=service.change(EMAIL,withToken(command,token,key));

        assertThat(result.replayed()).isFalse(); assertThat(result.change().id()).isEqualTo(CHANGE);
        var order=inOrder(members,repository,adjuster);
        order.verify(members).requireActiveParticipants(SPACE,ACTOR,null);
        order.verify(repository).loadSchedule(SPACE,ID,ScheduleLock.UPDATE);
        order.verify(repository).claimChange(eq(SPACE),eq(ACTOR),eq(key),anyString(),eq(ID),eq(NOW));
        order.verify(adjuster).occurrences(SPACE,ID,YearMonth.of(2026,10));
        order.verify(repository).saveChange(any());
        order.verify(adjuster).apply(eq(SPACE),eq(ACTOR),eq(CHANGE),eq(NOW),any());
        order.verify(repository).completeChange(SPACE,ACTOR,key,CHANGE,NOW);
        verify(categories,atLeastOnce()).requireSelectable(SPACE,CATEGORY);
        verify(members,atLeastOnce()).requireActiveParticipants(SPACE,ACTOR,RESPONSIBLE);

        var record=ArgumentCaptor.forClass(RecurrenceChangeRecord.class); verify(repository).saveChange(record.capture());
        assertThat(record.getValue()).satisfies(r->{
            assertThat(r.type()).isEqualTo("CHANGE"); assertThat(r.fromVersion()).isZero(); assertThat(r.actorId()).isEqualTo(ACTOR);
            assertThat(r.changedFields()).containsExactly("amount","dueDay","categoryId","responsibleUserId");
            assertThat(r.impactHash()).isEqualTo(token); assertThat(r.reason()).isNull();
            assertThat(r.segments()).hasSize(2); assertThat(r.currentConfiguration().amount()).isEqualByComparingTo("1000.00");
            assertThat(r.lastDueDate()).isNull(); assertThat(r.reviews()).isEmpty();
            assertThat(r.updatedCount()).isEqualTo(2); assertThat(r.preservedCount()).isOne();
            assertThat(r.previousConfiguration()).contains("segment=2026-08|Aluguel|1000.00|MONTHLY|10");
            assertThat(r.newConfiguration()).contains("segment=2026-10|Aluguel|1100.00|MONTHLY|20");});
        @SuppressWarnings("unchecked") ArgumentCaptor<List<RecurringOccurrenceAdjustment>> adjustments=ArgumentCaptor.forClass(List.class);
        verify(adjuster).apply(eq(SPACE),eq(ACTOR),eq(CHANGE),eq(NOW),adjustments.capture());
        assertThat(adjustments.getValue()).satisfiesExactly(a->{
            assertThat(a.expenseId()).isEqualTo(oct); assertThat(a.expectedVersion()).isEqualTo(3); assertThat(a.remove()).isFalse();
            assertThat(a.amount()).isEqualByComparingTo("1100.00"); assertThat(a.dueDate()).isEqualTo(LocalDate.of(2026,10,20));
            assertThat(a.categoryId()).isEqualTo(CATEGORY); assertThat(a.responsibleUserId()).isEqualTo(RESPONSIBLE);
            assertThat(a.changedFields()).containsExactly("categoryId","responsibleUserId","amount","dueDate");},
            a->{assertThat(a.expenseId()).isEqualTo(dec); assertThat(a.dueDate()).isEqualTo(LocalDate.of(2026,12,18));});
    }

    @Test void staleVersionsChangedImpactsAndReplaysNeverApplyASecondSetOfChanges() {
        var command=change(0,"1100.00",RecurrenceFrequency.MONTHLY,10,null,null,null);
        var token=service.previewChange(EMAIL,command).impactToken();
        clearInvocations(adjuster);
        assertThatThrownBy(()->service.change(EMAIL,withToken(change(1,"1100.00",RecurrenceFrequency.MONTHLY,10,null,null,null),
                token,UUID.randomUUID()))).isInstanceOf(RecurrenceVersionConflictException.class);
        verifyNoInteractions(adjuster);
        assertThatThrownBy(()->service.change(EMAIL,withToken(command,"0".repeat(64),UUID.randomUUID())))
                .isInstanceOf(RecurrenceImpactChangedException.class);
        verify(repository,never()).saveChange(any());

        when(repository.claimChange(any(),any(),any(),anyString(),any(),any())).thenReturn(new ChangeClaim(true,CHANGE));
        clearInvocations(adjuster);
        var replay=service.change(EMAIL,withToken(command,token,UUID.randomUUID()));
        assertThat(replay.replayed()).isTrue(); assertThat(replay.change().id()).isEqualTo(CHANGE);
        verifyNoInteractions(adjuster); verify(repository,never()).saveChange(any());
    }

    @Test void validatesTheRequestBeforeAnyWrite() {
        assertThatThrownBy(()->service.change(EMAIL,withToken(change(0,"1",RecurrenceFrequency.MONTHLY,10,null,null,null),"t",null)))
                .isInstanceOf(RecurrenceValidationException.class).hasMessageContaining("chave");
        assertThatThrownBy(()->service.change(EMAIL,withToken(change(0,"1",RecurrenceFrequency.MONTHLY,10,null,null,null)," ",UUID.randomUUID())))
                .isInstanceOf(RecurrenceValidationException.class).hasMessageContaining("impacto");
        assertThatThrownBy(()->service.change(EMAIL,null)).isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->service.previewChange(EMAIL,new ChangeRecurrenceCommand(null,0,LocalDate.of(2026,10,10),"x","1",
                RecurrenceFrequency.MONTHLY,1,null,null,null,null))).isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->service.previewChange(EMAIL,new ChangeRecurrenceCommand(ID,0,null,"x","1",
                RecurrenceFrequency.MONTHLY,1,null,null,null,null))).hasMessageContaining("vencimento");
        assertThatThrownBy(()->service.previewChange(EMAIL,changeFrom(LocalDate.of(2026,8,10)))).hasMessageContaining("12 meses");
        assertThatThrownBy(()->service.previewChange(EMAIL,changeFrom(LocalDate.of(2027,10,10)))).hasMessageContaining("12 meses");
        assertThat(service.previewChange(EMAIL,changeFrom(LocalDate.of(2027,9,10))).effectiveDueDate()).isEqualTo(LocalDate.of(2027,9,10));
        assertThat(service.previewChange(EMAIL,changeFrom(LocalDate.of(2026,9,1))).effectiveDueDate()).isEqualTo(LocalDate.of(2026,9,10));
        assertThatThrownBy(()->service.previewChange(EMAIL,change(0,"abc",RecurrenceFrequency.MONTHLY,10,null,null,null)))
                .hasMessageContaining("decimal");
        assertThatThrownBy(()->service.previewChange(EMAIL,change(0,"1000",RecurrenceFrequency.MONTHLY,null,null,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(()->service.previewClosure(EMAIL,new CloseRecurrenceCommand(ID,0,null,null,null,null)))
                .hasMessageContaining("último vencimento");
        assertThatThrownBy(()->service.close(EMAIL,new CloseRecurrenceCommand(ID,0,LocalDate.of(2026,10,10),"x".repeat(2001),"t",UUID.randomUUID())))
                .hasMessageContaining("motivo");
        assertThatThrownBy(()->service.close(EMAIL,new CloseRecurrenceCommand(ID,0,LocalDate.of(2026,10,10),null,"t",UUID.randomUUID())))
                .hasMessageContaining("motivo");
        assertThatThrownBy(()->service.close(EMAIL,null)).isInstanceOf(RecurrenceValidationException.class);
        verify(repository,never()).claimChange(any(),any(),any(),anyString(),any(),any());
        verify(repository,never()).saveChange(any());
    }

    @Test void closureRemovesEstimatedFixedLaunchesFlagsPaidOrCorrectedOnesAndKeepsTheReason() {
        var jan=UUID.randomUUID();
        when(adjuster.occurrences(eq(SPACE),eq(ID),any())).thenReturn(List.of(
                snapshot(oct,LocalDate.of(2026,10,10),"PENDING",true,"1000.00",LocalDate.of(2026,10,10),false),
                snapshot(nov,LocalDate.of(2026,11,10),"PAID",true,"1000.00",LocalDate.of(2026,11,10),false),
                snapshot(dec,LocalDate.of(2026,12,10),"PENDING",true,"1000.00",LocalDate.of(2026,12,18),true),
                snapshot(jan,LocalDate.of(2027,1,10),"PENDING",true,"1000.00",LocalDate.of(2027,1,10),false)));
        var reason="x".repeat(2000);
        var command=new CloseRecurrenceCommand(ID,0,LocalDate.of(2026,10,31),null,null,null);
        var impact=service.previewClosure(EMAIL,command);
        assertThat(impact.operation()).isEqualTo("CLOSURE"); assertThat(impact.changedFields()).containsExactly("lastDueDate");
        assertThat(impact.effectiveDueDate()).isEqualTo(LocalDate.of(2026,10,10));
        assertThat(impact.forecasts()).allSatisfy(f->{assertThat(f.action()).isEqualTo("REMOVED");assertThat(f.newDueDate()).isNull();})
                .hasSize(8);

        service.close(EMAIL,new CloseRecurrenceCommand(ID,0,LocalDate.of(2026,10,31)," "+reason+" ",impact.impactToken(),UUID.randomUUID()));

        verify(adjuster).occurrences(SPACE,ID,YearMonth.of(2026,11));
        var record=ArgumentCaptor.forClass(RecurrenceChangeRecord.class); verify(repository).saveChange(record.capture());
        assertThat(record.getValue()).satisfies(r->{assertThat(r.type()).isEqualTo("CLOSURE"); assertThat(r.reason()).isEqualTo(reason);
            assertThat(r.lastDueDate()).isEqualTo(LocalDate.of(2026,10,10)); assertThat(r.reviews()).isEqualTo(Map.of(nov,"AFTER_END",dec,"AFTER_END"));
            assertThat(r.removedCount()).isOne(); assertThat(r.reviewCount()).isEqualTo(2);});
        @SuppressWarnings("unchecked") ArgumentCaptor<List<RecurringOccurrenceAdjustment>> adjustments=ArgumentCaptor.forClass(List.class);
        verify(adjuster).apply(eq(SPACE),eq(ACTOR),eq(CHANGE),eq(NOW),adjustments.capture());
        assertThat(adjustments.getValue()).singleElement().satisfies(a->{assertThat(a.expenseId()).isEqualTo(jan);
            assertThat(a.remove()).isTrue(); assertThat(a.removalReason()).hasSize(2000).startsWith("Recorrência encerrada: xxx");});
    }

    @Test void changesMovingOutOfTheCalendarCancelWithAnExplanationAndVariableConfirmationsAreKept() {
        when(repository.loadSchedule(eq(SPACE),eq(ID),any())).thenReturn(stored(RecurrenceValueType.VARIABLE_ESTIMATE,0,null));
        var command=change(0,"1000.00",RecurrenceFrequency.BIMONTHLY,10,null,null,null);
        var impact=service.previewChange(EMAIL,command);
        assertThat(impact.occurrences()).extracting(ImpactOccurrenceView::action).containsExactly("PRESERVE","REVIEW","PRESERVE");
        when(adjuster.occurrences(eq(SPACE),eq(ID),any())).thenReturn(List.of(
                snapshot(nov,LocalDate.of(2026,11,10),"PENDING",false,"1000.00",LocalDate.of(2026,11,10),false)));
        var fresh=service.previewChange(EMAIL,command);
        service.change(EMAIL,withToken(command,fresh.impactToken(),UUID.randomUUID()));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<RecurringOccurrenceAdjustment>> adjustments=ArgumentCaptor.forClass(List.class);
        verify(adjuster).apply(eq(SPACE),eq(ACTOR),eq(CHANGE),eq(NOW),adjustments.capture());
        assertThat(adjustments.getValue()).singleElement().satisfies(a->assertThat(a.removalReason())
                .isEqualTo("Fora da nova programação da recorrência."));
    }

    @Test void listShowsTheConfigurationInForceClosureSegmentsHistoryAndUpcomingDates() {
        var closedAt=Instant.parse("2026-09-20T10:00:00Z");
        var definition=definition(RecurrenceValueType.FIXED,2,LocalDate.of(2026,12,15));
        var configuration=new RecurrenceConfiguration("Aluguel novo",new BigDecimal("1200"),RecurrenceFrequency.MONTHLY,15,CATEGORY,null);
        var stored=new StoredSchedule(new StoredRecurrence(definition,null,null,"Ana"),List.of(
                new RecurrenceSegment(YearMonth.of(2026,8),config(),true),new RecurrenceSegment(YearMonth.of(2026,9),configuration,true)),
                List.of(new RecurrenceSegmentView(LocalDate.of(2026,8,1),"Aluguel","1000.00",RecurrenceFrequency.MONTHLY,10,null,null,null,null),
                        new RecurrenceSegmentView(LocalDate.of(2026,9,1),"Aluguel novo","1200.00",RecurrenceFrequency.MONTHLY,15,CATEGORY,
                                "Casa",null,null)),closedAt,"Mudança","Ana");
        var history=new RecurrenceChangeView(CHANGE,"CLOSURE",ACTOR,"Ana",closedAt,2,LocalDate.of(2026,12,15),List.of("lastDueDate"),
                "Mudança",0,0,0,0);
        when(repository.findSchedules(SPACE)).thenReturn(List.of(stored));
        when(repository.findChanges(SPACE)).thenReturn(Map.of(ID,List.of(history)));

        assertThat(service.list(EMAIL)).singleElement().satisfies(v->{
            assertThat(v.description()).isEqualTo("Aluguel novo"); assertThat(v.amount()).isEqualTo("1200.00");
            assertThat(v.baseDay()).isEqualTo(15); assertThat(v.categoryName()).isEqualTo("Casa");
            assertThat(v.firstDueDate()).isEqualTo(LocalDate.of(2026,8,10)); assertThat(v.lastDueDate()).isEqualTo(LocalDate.of(2026,12,15));
            assertThat(v.upcomingDates()).containsExactly(LocalDate.of(2026,9,15),LocalDate.of(2026,10,15),LocalDate.of(2026,11,15),
                    LocalDate.of(2026,12,15));
            assertThat(v.previewDates()).startsWith(LocalDate.of(2026,8,10),LocalDate.of(2026,9,15));
            assertThat(v.closedAt()).isEqualTo(closedAt); assertThat(v.closureReason()).isEqualTo("Mudança");
            assertThat(v.closedByDisplayName()).isEqualTo("Ana"); assertThat(v.segments()).hasSize(2);
            assertThat(v.changes()).containsExactly(history); assertThat(v.version()).isEqualTo(2);});
    }

    @Test void forecastsShowMaterializedOccurrencesOutsideTheCalendarUnlessCancelled() {
        var stored=new StoredSchedule(new StoredRecurrence(definition(RecurrenceValueType.FIXED,1,null),null,null,"Ana"),List.of(
                new RecurrenceSegment(YearMonth.of(2026,8),config(),true),
                new RecurrenceSegment(YearMonth.of(2026,10),new RecurrenceConfiguration("Aluguel",new BigDecimal("1000"),
                        RecurrenceFrequency.QUARTERLY,10,null,null),false)),List.of(),null,null,null);
        when(repository.findSchedules(SPACE)).thenReturn(List.of(stored));
        when(repository.findOccurrences(eq(SPACE),any(),any())).thenReturn(List.of(
                new StoredOccurrence(ID,LocalDate.of(2026,11,10),nov,LocalDate.of(2026,11,10),"PAID",new BigDecimal("1000"),true,"OUTSIDE_SCHEDULE"),
                new StoredOccurrence(ID,LocalDate.of(2026,12,10),dec,LocalDate.of(2026,12,10),"CANCELLED",new BigDecimal("1000"),true),
                new StoredOccurrence(ID,LocalDate.of(2027,1,10),oct,LocalDate.of(2027,1,12),"PENDING",new BigDecimal("1000"),true)));

        var forecast=service.forecasts(EMAIL).occurrences();

        assertThat(forecast).extracting(ForecastView::scheduledDueDate).containsExactly(LocalDate.of(2026,9,10),
                LocalDate.of(2026,10,10),LocalDate.of(2026,11,10),LocalDate.of(2027,1,10),LocalDate.of(2027,4,10),LocalDate.of(2027,7,10));
        assertThat(forecast.get(2)).satisfies(f->{assertThat(f.reviewReason()).isEqualTo("OUTSIDE_SCHEDULE");
            assertThat(f.state()).isEqualTo("MATERIALIZED"); assertThat(f.estimated()).isFalse();});
        assertThat(forecast.get(3).actualDueDate()).isEqualTo(LocalDate.of(2027,1,12));
    }

    private static RecurringOccurrenceSnapshot snapshot(UUID id,LocalDate scheduled,String status,boolean confirmed,String amount,
            LocalDate due,boolean corrected) {
        return new RecurringOccurrenceSnapshot(id,scheduled,3,status,confirmed,new BigDecimal(amount),due,"Aluguel",null,null,corrected);
    }
    private static ChangeRecurrenceCommand change(long version,String amount,RecurrenceFrequency frequency,Integer day,UUID category,
            UUID responsible,String token) {
        return new ChangeRecurrenceCommand(ID,version,LocalDate.of(2026,10,10),"Aluguel",amount,frequency,day,category,responsible,token,null);
    }
    private static ChangeRecurrenceCommand changeFrom(LocalDate from) {
        return new ChangeRecurrenceCommand(ID,0,from,"Aluguel","1001",RecurrenceFrequency.MONTHLY,10,null,null,null,null);
    }
    private static ChangeRecurrenceCommand withToken(ChangeRecurrenceCommand c,String token,UUID key) {
        return new ChangeRecurrenceCommand(c.recurrenceId(),c.version(),c.effectiveDueDate(),c.description(),c.amount(),c.frequency(),
                c.dueDay(),c.categoryId(),c.responsibleUserId(),token,key);
    }
    private static RecurrenceConfiguration config() {
        return new RecurrenceConfiguration("Aluguel",new BigDecimal("1000.00"),RecurrenceFrequency.MONTHLY,10,null,null);
    }
    private static RecurrenceDefinition definition(RecurrenceValueType type,long version,LocalDate last) {
        return new RecurrenceDefinition(ID,SPACE,"Aluguel",new BigDecimal("1000.00"),type,RecurrenceFrequency.MONTHLY,
                LocalDate.of(2026,8,10),last,null,null,ACTOR,NOW,version);
    }
    private static StoredSchedule stored(RecurrenceValueType type,long version,LocalDate last) {
        return new StoredSchedule(new StoredRecurrence(definition(type,version,last),null,null,"Ana"),
                List.of(new RecurrenceSegment(YearMonth.of(2026,8),config(),true)),List.of(),null,null,null);
    }
}
