package com.malyah.accountmanager.recurrences.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.recurrences.application.port.RecurrenceRepository;
import com.malyah.accountmanager.recurrences.domain.*;

class RecurrenceServiceTest {
    private static final UUID SPACE=UUID.randomUUID(), ACTOR=UUID.randomUUID(), RESPONSIBLE=UUID.randomUUID(), ID=UUID.randomUUID();
    private static final Instant NOW=Instant.parse("2026-09-27T12:00:00Z");
    private RecurrenceRepository repository; private CategoryRepository categories; private FinancialMemberAccess members;
    private RecurrenceService service;

    @BeforeEach void setup() {
        repository=mock(RecurrenceRepository.class); categories=mock(CategoryRepository.class); members=mock(FinancialMemberAccess.class);
        AuthenticatedUserContextQuery context=email -> new AuthenticatedUserContext(ACTOR,"Ana",email,SPACE,"Casa",SpaceRole.GUEST,"BRL","pt-BR","America/Sao_Paulo");
        service=new RecurrenceService(repository,context,categories,members,Clock.fixed(NOW,ZoneOffset.UTC),()->ID,
                new RecurrenceCalendar(),mock(RecurringExpenseMaterializer.class));
        when(repository.createIdempotently(any(),any(),any(),anyString(),any())).thenAnswer(invocation -> {
            var definition=invocation.<RecurrenceDefinition>getArgument(0);
            return new StoredRecurrenceCreation(new StoredRecurrence(definition,"Moradia","Beto","Ana"),false);
        });
    }

    @Test void createsFixedAndVariableDefinitionsForEitherActiveRoleAndReturnsServerCalendar() {
        var category=UUID.randomUUID(); var key=UUID.randomUUID();
        var result=service.create("ana@example.com",new CreateRecurrenceCommand(" Condomínio ","500.00",
                RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,LocalDate.of(2027,1,31),null,category,RESPONSIBLE,key));
        assertThat(result.recurrence().description()).isEqualTo("Condomínio");
        assertThat(result.recurrence().previewDates()).startsWith(LocalDate.of(2027,1,31),LocalDate.of(2027,2,28),LocalDate.of(2027,3,31));
        verify(categories).requireSelectable(SPACE,category); verify(members).requireActiveParticipants(SPACE,ACTOR,RESPONSIBLE);
        var captured=ArgumentCaptor.forClass(RecurrenceDefinition.class); verify(repository).createIdempotently(captured.capture(),eq(ACTOR),eq(key),anyString(),eq(NOW));
        assertThat(captured.getValue().amount()).isEqualByComparingTo(new BigDecimal("500.00"));

        service.create("ana@example.com",new CreateRecurrenceCommand("Energia","120.50",RecurrenceValueType.VARIABLE_ESTIMATE,
                RecurrenceFrequency.BIMONTHLY,LocalDate.of(2027,1,30),LocalDate.of(2027,5,30),null,null,UUID.randomUUID()));
    }

    @Test void rejectsMalformedMoneyAndMissingKeyBeforePersistence() {
        assertThatThrownBy(() -> service.create("ana@example.com",new CreateRecurrenceCommand("Energia","abc",
                RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,LocalDate.now(),null,null,null,UUID.randomUUID())))
                .isInstanceOf(RecurrenceValidationException.class);
        assertThatThrownBy(() -> service.create("ana@example.com",new CreateRecurrenceCommand("Energia","10",
                RecurrenceValueType.FIXED,RecurrenceFrequency.MONTHLY,LocalDate.now(),null,null,null,null)))
                .isInstanceOf(RecurrenceValidationException.class);
        verify(repository,never()).createIdempotently(any(),any(),any(),anyString(),any());
    }

    @Test void listsOnlyTheAuthenticatedSpace() {
        var definition=new RecurrenceDefinition(ID,SPACE,"Aluguel",new BigDecimal("1000"),RecurrenceValueType.FIXED,
                RecurrenceFrequency.MONTHLY,LocalDate.of(2027,1,10),null,null,null,ACTOR,NOW,0);
        when(repository.findAll(SPACE)).thenReturn(List.of(new StoredRecurrence(definition,null,null,"Ana")));
        assertThat(service.list("ana@example.com")).singleElement().satisfies(view -> assertThat(view.description()).isEqualTo("Aluguel"));
        verify(repository).findAll(SPACE);
    }

    @Test void confirmsAForecastByMaterializingTheSameOccurrenceAndConfirmingVersionZeroAtomically() {
        var materializer=mock(RecurringExpenseMaterializer.class);
        var confirmation=mock(com.malyah.accountmanager.expenses.application.ChargeConfirmationUseCase.class);
        var forecastService=new RecurrenceService(repository,email -> new AuthenticatedUserContext(ACTOR,"Ana",email,SPACE,
                "Casa",SpaceRole.GUEST,"BRL","pt-BR","America/Sao_Paulo"),categories,members,Clock.fixed(NOW,ZoneOffset.UTC),
                ()->ID,new RecurrenceCalendar(),materializer,confirmation);
        var variable=definition(RecurrenceValueType.VARIABLE_ESTIMATE);
        var expense=UUID.randomUUID(); var key=UUID.randomUUID(); var due=LocalDate.of(2026,11,5);
        when(repository.findById(SPACE,ID)).thenReturn(new StoredRecurrence(variable,null,null,"Ana"));
        when(repository.findAll(SPACE)).thenReturn(List.of(new StoredRecurrence(variable,null,null,"Ana")));
        when(repository.findOccurrences(eq(SPACE),any(),any())).thenReturn(List.of(new StoredOccurrence(ID,due,expense,
                due,"PENDING",new BigDecimal("210.00"),true)));
        when(materializer.materializeAnticipated(any())).thenReturn(expense);
        when(confirmation.confirmCharge(anyString(),any())).thenReturn(
                new com.malyah.accountmanager.expenses.application.ExpenseCreationResult(null,false));

        var result=forecastService.confirmForecastCharge("ana@example.com",ID,due,"210",key);

        var order=inOrder(repository,materializer,confirmation);
        order.verify(repository).lockForChargeConfirmation(SPACE,ID);
        order.verify(materializer).materializeAnticipated(argThat(command->command.recurrenceId().equals(ID)
                && command.scheduledDueDate().equals(due) && !command.chargeConfirmed()));
        order.verify(confirmation).confirmCharge("ana@example.com",
                new com.malyah.accountmanager.expenses.application.ConfirmChargeCommand(expense,0,"210",key));
        assertThat(result.replayed()).isFalse();
        assertThat(result.occurrence().state()).isEqualTo("MATERIALIZED");
        assertThat(result.occurrence().estimated()).isFalse();
        assertThat(result.occurrence().amount()).isEqualTo("210.00");
        verify(members).requireActiveParticipants(SPACE,ACTOR,null);
    }

    @Test void rejectsForecastConfirmationOfFixedValuesOrDatesOutsideTheCalendarWithoutWriting() {
        var materializer=mock(RecurringExpenseMaterializer.class);
        var confirmation=mock(com.malyah.accountmanager.expenses.application.ChargeConfirmationUseCase.class);
        var forecastService=new RecurrenceService(repository,email -> new AuthenticatedUserContext(ACTOR,"Ana",email,SPACE,
                "Casa",SpaceRole.GUEST,"BRL","pt-BR","America/Sao_Paulo"),categories,members,Clock.fixed(NOW,ZoneOffset.UTC),
                ()->ID,new RecurrenceCalendar(),materializer,confirmation);
        when(repository.findById(SPACE,ID)).thenReturn(new StoredRecurrence(definition(RecurrenceValueType.FIXED),null,null,"Ana"));
        assertThatThrownBy(()->forecastService.confirmForecastCharge("ana@example.com",ID,LocalDate.of(2026,11,5),"10",UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class).hasMessageContaining("valor estimado");
        when(repository.findById(SPACE,ID)).thenReturn(new StoredRecurrence(definition(RecurrenceValueType.VARIABLE_ESTIMATE),null,null,"Ana"));
        assertThatThrownBy(()->forecastService.confirmForecastCharge("ana@example.com",ID,LocalDate.of(2026,11,6),"10",UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        assertThatThrownBy(()->forecastService.confirmForecastCharge("ana@example.com",ID,LocalDate.of(2027,10,5),"10",UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        assertThatThrownBy(()->forecastService.confirmForecastCharge("ana@example.com",ID,LocalDate.of(2026,8,5),"10",UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        assertThatThrownBy(()->forecastService.confirmForecastCharge("ana@example.com",ID,LocalDate.of(2026,11,5),"10",null))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        assertThatThrownBy(()->forecastService.confirmForecastCharge("ana@example.com",null,LocalDate.of(2026,11,5),"10",UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        assertThatThrownBy(()->forecastService.confirmForecastCharge("ana@example.com",ID,null,"10",UUID.randomUUID()))
                .isInstanceOf(RecurrenceOccurrenceException.class);
        verify(repository,never()).lockForChargeConfirmation(any(),any());
        verifyNoInteractions(materializer,confirmation);
    }

    @Test void forecastsOfVariableRecurrencesUseTheLatestPriorConfirmationAndFixedOnesKeepTheirValue() {
        var variable=definition(RecurrenceValueType.VARIABLE_ESTIMATE);
        var fixedId=UUID.randomUUID();
        var fixed=new RecurrenceDefinition(fixedId,SPACE,"Aluguel",new BigDecimal("1000"),RecurrenceValueType.FIXED,
                RecurrenceFrequency.MONTHLY,LocalDate.of(2026,9,5),null,null,null,ACTOR,NOW,0);
        when(repository.findAll(SPACE)).thenReturn(List.of(new StoredRecurrence(variable,null,null,"Ana"),
                new StoredRecurrence(fixed,null,null,"Ana")));
        when(repository.findOccurrences(eq(SPACE),any(),any())).thenReturn(List.of(
                new StoredOccurrence(ID,LocalDate.of(2026,11,5),UUID.randomUUID(),LocalDate.of(2026,11,5),"PENDING",
                        new BigDecimal("195.00"),true)));
        when(repository.findConfirmedCharges(SPACE)).thenReturn(List.of(
                new StoredOccurrence(ID,LocalDate.of(2026,11,5),UUID.randomUUID(),LocalDate.of(2026,11,5),"PENDING",
                        new BigDecimal("195.00"),true),
                new StoredOccurrence(fixedId,LocalDate.of(2026,10,5),UUID.randomUUID(),LocalDate.of(2026,10,5),"PENDING",
                        new BigDecimal("900.00"),true)));

        var occurrences=service.forecasts("ana@example.com").occurrences();

        assertThat(occurrences).filteredOn(o->o.recurrenceId().equals(ID)&&o.scheduledDueDate().equals(LocalDate.of(2026,10,5)))
                .singleElement().satisfies(o->{assertThat(o.amount()).isEqualTo("180.00");assertThat(o.estimated()).isTrue();});
        assertThat(occurrences).filteredOn(o->o.recurrenceId().equals(ID)&&o.scheduledDueDate().equals(LocalDate.of(2026,12,5)))
                .singleElement().satisfies(o->{assertThat(o.amount()).isEqualTo("195.00");assertThat(o.state()).isEqualTo("FORECAST");});
        assertThat(occurrences).filteredOn(o->o.recurrenceId().equals(fixedId)&&o.state().equals("FORECAST"))
                .allSatisfy(o->{assertThat(o.amount()).isEqualTo("1000.00");assertThat(o.estimated()).isFalse();});
    }

    private RecurrenceDefinition definition(RecurrenceValueType type) {
        return new RecurrenceDefinition(ID,SPACE,"Energia",new BigDecimal("180.00"),type,RecurrenceFrequency.MONTHLY,
                LocalDate.of(2026,9,5),null,null,null,ACTOR,NOW,0);
    }
}
