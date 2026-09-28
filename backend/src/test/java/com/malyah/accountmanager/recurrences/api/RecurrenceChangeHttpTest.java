package com.malyah.accountmanager.recurrences.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.malyah.accountmanager.recurrences.application.*;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValidationException;

/** H04.5 HTTP contract: routes, idempotency header, request mapping and error codes of change and closure. */
class RecurrenceChangeHttpTest {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID KEY=UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private RecurrenceUseCase useCase; private MockMvc mvc;

    @BeforeEach void setup() {
        useCase=mock(RecurrenceUseCase.class);
        mvc=MockMvcBuilders.standaloneSetup(new RecurrenceController(useCase))
                .setControllerAdvice(new RecurrenceApiExceptionHandler()).build();
    }

    @Test void previewAndApplyAChangeMappingTheWholeRequest() throws Exception {
        when(useCase.previewChange(eq("ana@example.com"),any())).thenReturn(new RecurrenceImpactView(ID,"CHANGE",3,
                LocalDate.of(2026,10,5),List.of("amount"),"a".repeat(64),List.of(),List.of(),1,0,0,0));
        mvc.perform(post("/recurrences/{id}/changes/preview",ID).principal(()->"ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"version":3,"effectiveDueDate":"2026-10-05","description":"Luz","amount":"10.50","frequency":"MONTHLY",
                         "dueDay":7,"categoryId":null,"responsibleUserId":null}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.impactToken").value("a".repeat(64)))
                .andExpect(jsonPath("$.changedFields[0]").value("amount"));
        var command=ArgumentCaptor.forClass(ChangeRecurrenceCommand.class);
        verify(useCase).previewChange(eq("ana@example.com"),command.capture());
        assertThat(command.getValue()).isEqualTo(new ChangeRecurrenceCommand(ID,3,LocalDate.of(2026,10,5),"Luz","10.50",
                RecurrenceFrequency.MONTHLY,7,null,null,null,null));

        when(useCase.change(eq("ana@example.com"),any())).thenReturn(new RecurrenceChangeResult(null,
                new RecurrenceChangeView(KEY,"CHANGE",ID,"Ana",Instant.parse("2026-09-27T12:00:00Z"),4,LocalDate.of(2026,10,5),
                        List.of("amount"),null,1,0,0,0),false));
        mvc.perform(post("/recurrences/{id}/changes",ID).principal(()->"ana@example.com").header("Idempotency-Key",KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"version":3,"effectiveDueDate":"2026-10-05","description":"Luz","amount":"10.50","frequency":"MONTHLY",
                         "dueDay":7,"impactToken":"tok"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.change.version").value(4));
        verify(useCase).change("ana@example.com",new ChangeRecurrenceCommand(ID,3,LocalDate.of(2026,10,5),"Luz","10.50",
                RecurrenceFrequency.MONTHLY,7,null,null,"tok",KEY));
    }

    @Test void closureRoutesAndConflictCodes() throws Exception {
        when(useCase.previewClosure(any(),any())).thenThrow(new RecurrenceNotFoundException());
        mvc.perform(post("/recurrences/{id}/closure/preview",ID).principal(()->"ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"lastDueDate\":\"2026-10-05\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECURRENCE_NOT_FOUND"));

        when(useCase.close(any(),any())).thenThrow(new RecurrenceVersionConflictException());
        mvc.perform(post("/recurrences/{id}/closure",ID).principal(()->"ana@example.com").header("Idempotency-Key",KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"lastDueDate\":\"2026-10-05\",\"reason\":\"Fim\",\"impactToken\":\"t\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECURRENCE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.field").value("version"));
        verify(useCase).close("ana@example.com",new CloseRecurrenceCommand(ID,0,LocalDate.of(2026,10,5),"Fim","t",KEY));

        when(useCase.change(any(),any())).thenThrow(new RecurrenceImpactChangedException());
        mvc.perform(post("/recurrences/{id}/changes",ID).principal(()->"ana@example.com").header("Idempotency-Key",KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"effectiveDueDate\":\"2026-10-05\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECURRENCE_IMPACT_CHANGED"));

        when(useCase.previewChange(any(),any())).thenThrow(new RecurrenceValidationException("dueDay","Dia inválido."));
        mvc.perform(post("/recurrences/{id}/changes/preview",ID).principal(()->"ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"effectiveDueDate\":\"2026-10-05\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("dueDay"));
    }

    @Test void rejectsMissingVersionDateOrKeyBeforeCallingTheUseCase() throws Exception {
        mvc.perform(post("/recurrences/{id}/changes/preview",ID).principal(()->"ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveDueDate\":\"2026-10-05\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECURRENCE_VALIDATION"));
        mvc.perform(post("/recurrences/{id}/closure/preview",ID).principal(()->"ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("lastDueDate"));
        mvc.perform(post("/recurrences/{id}/closure",ID).principal(()->"ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"lastDueDate\":\"2026-10-05\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }
}
