package com.malyah.accountmanager.reporting.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.reporting.application.ClosingCategoryView;
import com.malyah.accountmanager.reporting.application.ClosingChangeView;
import com.malyah.accountmanager.reporting.application.ClosingIdempotencyConflictException;
import com.malyah.accountmanager.reporting.application.ClosingLineView;
import com.malyah.accountmanager.reporting.application.ClosingPendingConfirmationRequiredException;
import com.malyah.accountmanager.reporting.application.ClosingSnapshotView;
import com.malyah.accountmanager.reporting.application.ClosingVersionConflictException;
import com.malyah.accountmanager.reporting.application.ClosingVersionListView;
import com.malyah.accountmanager.reporting.application.ClosingVersionNotFoundException;
import com.malyah.accountmanager.reporting.application.ClosingVersionView;
import com.malyah.accountmanager.reporting.application.GenerateVersionCommand;
import com.malyah.accountmanager.reporting.application.MonthNotClosedException;
import com.malyah.accountmanager.reporting.application.CloseMonthCommand;
import com.malyah.accountmanager.reporting.application.CloseMonthResult;
import com.malyah.accountmanager.reporting.application.DueIndicatorsView;
import com.malyah.accountmanager.reporting.application.MonthAlreadyClosedException;
import com.malyah.accountmanager.reporting.application.MonthClosingListView;
import com.malyah.accountmanager.reporting.application.MonthClosingUseCase;
import com.malyah.accountmanager.reporting.application.MonthClosingView;
import com.malyah.accountmanager.reporting.application.ReportQueryValidationException;
import com.malyah.accountmanager.reporting.domain.ClosingMonthNotAllowedException;

/** H07.1 HTTP contract: money as strings, idempotency header, confirmation body and error codes. */
class MonthClosingHttpTest {
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID EXPENSE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private MonthClosingUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        useCase = mock(MonthClosingUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new MonthClosingController(useCase))
                .setControllerAdvice(new ReportingApiExceptionHandler()).build();
    }

    static MonthClosingView closed() {
        var indicators = new DueIndicatorsView(1, "180.00", "180.00", 0, "0.00", 1, "180.00", "180.00", 0, "0.00",
                "0.00", "0.00", "0.00", "0.00");
        var line = new ClosingLineView(EXPENSE, "Luz", "RECURRENCE", null, null, LocalDate.of(2026, 10, 20), true,
                "PENDING", "180.00", true, null, null, false, null, null);
        var category = new ClosingCategoryView(null, null, 1, "180.00", "180.00", "0.00", 1, "180.00");
        var saved = new ClosingSnapshotView(1, USER, "Ana", Instant.parse("2026-10-15T15:00:00Z"),
                LocalDate.of(2026, 10, 15), "America/Sao_Paulo", true, "a".repeat(64), indicators, List.of(category),
                List.of(line));
        var current = new ClosingSnapshotView(null, null, null, null, LocalDate.of(2026, 10, 15),
                "America/Sao_Paulo", false, "a".repeat(64), indicators, List.of(category), List.of(line));
        return new MonthClosingView("2026-10", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "DUE_DATE",
                LocalDate.of(2026, 10, 15), "America/Sao_Paulo", true, "OUTDATED", List.of(new ClosingChangeView("CHANGED",
                EXPENSE, List.of("SITUATION", "PAID_AMOUNT"), line, line)), saved, current);
    }

    @Test
    void readsTheSavedSnapshotAndTheCurrentData() throws Exception {
        when(useCase.view("ana@example.com", "2026-10")).thenReturn(closed());
        mvc.perform(get("/reports/closings/2026-10").principal(() -> "ana@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateBasis").value("DUE_DATE"))
                .andExpect(jsonPath("$.closable").value(true))
                .andExpect(jsonPath("$.saved.version").value(1))
                .andExpect(jsonPath("$.saved.authorDisplayName").value("Ana"))
                .andExpect(jsonPath("$.saved.closedAt").value("2026-10-15T15:00:00Z"))
                .andExpect(jsonPath("$.saved.indicators.pendingTotal").value("180.00"))
                .andExpect(jsonPath("$.saved.categories[0].categoryName").doesNotExist())
                .andExpect(jsonPath("$.saved.lines[0].estimated").value(true))
                .andExpect(jsonPath("$.current.version").doesNotExist())
                .andExpect(jsonPath("$.status").value("OUTDATED"))
                .andExpect(jsonPath("$.changes[0].kind").value("CHANGED"))
                .andExpect(jsonPath("$.changes[0].fields[1]").value("PAID_AMOUNT"))
                .andExpect(jsonPath("$.changes[0].saved.chargeAmount").value("180.00"));
    }

    @Test
    void generatesAVersionWithTheExpectedVersionAndReadsEveryVersion() throws Exception {
        when(useCase.generateVersion(eq("ana@example.com"), any())).thenReturn(new CloseMonthResult(closed(), false))
                .thenReturn(new CloseMonthResult(closed(), true));
        mvc.perform(post("/reports/closings/2026-10/versions").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", KEY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"acknowledgePending\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/reports/closings/2026-10/versions/1"));
        verify(useCase).generateVersion("ana@example.com", new GenerateVersionCommand("2026-10", 1, true, KEY));
        mvc.perform(post("/reports/closings/2026-10/versions").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", KEY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"acknowledgePending\":true}"))
                .andExpect(status().isOk());

        var indicators = closed().saved().indicators();
        when(useCase.versions("ana@example.com", "2026-10")).thenReturn(new ClosingVersionListView("2026-10", 2,
                List.of(new ClosingVersionListView.Item(1, USER, "Ana", Instant.parse("2026-10-15T15:00:00Z"),
                        LocalDate.of(2026, 10, 15), true, indicators, false))));
        mvc.perform(get("/reports/closings/2026-10/versions").principal(() -> "ana@example.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.versions[0].current").value(false))
                .andExpect(jsonPath("$.versions[0].indicators.pendingTotal").value("180.00"));
        when(useCase.version("ana@example.com", "2026-10", "1"))
                .thenReturn(new ClosingVersionView("2026-10", 2, false, closed().saved()));
        mvc.perform(get("/reports/closings/2026-10/versions/1").principal(() -> "ana@example.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current").value(false))
                .andExpect(jsonPath("$.snapshot.version").value(1));
    }

    @Test
    void mapsEveryVersionRefusal() throws Exception {
        when(useCase.generateVersion(eq("ana@example.com"), any()))
                .thenThrow(new ClosingVersionConflictException(2))
                .thenThrow(new MonthNotClosedException())
                .thenThrow(new ClosingPendingConfirmationRequiredException(3));
        expectVersion(409, "CLOSING_VERSION_CONFLICT", "expectedVersion");
        expectVersion(409, "MONTH_NOT_CLOSED", null);
        expectVersion(422, "CLOSING_PENDING_CONFIRMATION_REQUIRED", "acknowledgePending");
        when(useCase.version("ana@example.com", "2026-10", "5")).thenThrow(new ClosingVersionNotFoundException());
        mvc.perform(get("/reports/closings/2026-10/versions/5").principal(() -> "ana@example.com"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLOSING_VERSION_NOT_FOUND"));
        mvc.perform(post("/reports/closings/2026-10/versions").principal(() -> "ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
    }

    private void expectVersion(int status, String code, String field) throws Exception {
        var result = mvc.perform(post("/reports/closings/2026-10/versions").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", KEY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
        if (field != null) result.andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }

    @Test
    void listsTheClosedMonthsOfAYear() throws Exception {
        when(useCase.list("ana@example.com", "2026")).thenReturn(new MonthClosingListView(2026, List.of(
                new MonthClosingListView.Item("2026-10", 1, "Ana", Instant.parse("2026-10-15T15:00:00Z"),
                        "UP_TO_DATE"))));
        mvc.perform(get("/reports/closings").param("year", "2026").principal(() -> "ana@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.closings[0].month").value("2026-10"))
                .andExpect(jsonPath("$.closings[0].status").value("UP_TO_DATE"))
                .andExpect(jsonPath("$.closings[0].closedAt").value("2026-10-15T15:00:00Z"));
        when(useCase.list("ana@example.com", "26"))
                .thenThrow(new ReportQueryValidationException("year", "Informe o ano no formato AAAA."));
        mvc.perform(get("/reports/closings").param("year", "26").principal(() -> "ana@example.com"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("year"));
    }

    @Test
    void closesWithTheKeyAndTheConfirmationAndAnswersReplaysWithOk() throws Exception {
        when(useCase.close(eq("ana@example.com"), any())).thenReturn(new CloseMonthResult(closed(), false))
                .thenReturn(new CloseMonthResult(closed(), true));
        mvc.perform(post("/reports/closings/2026-10").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", KEY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgePending\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/reports/closings/2026-10"))
                .andExpect(jsonPath("$.saved.version").value(1));
        verify(useCase).close("ana@example.com", new CloseMonthCommand("2026-10", true, KEY));
        mvc.perform(post("/reports/closings/2026-10").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", KEY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgePending\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void mapsEveryRefusal() throws Exception {
        when(useCase.close(eq("ana@example.com"), any()))
                .thenThrow(new ClosingPendingConfirmationRequiredException(4))
                .thenThrow(new ClosingMonthNotAllowedException())
                .thenThrow(new MonthAlreadyClosedException())
                .thenThrow(new ClosingIdempotencyConflictException())
                .thenThrow(new ReportQueryValidationException("month", "Informe o mês no formato AAAA-MM."))
                .thenThrow(new AuthenticatedUserContextNotFoundException());
        expect(422, "CLOSING_PENDING_CONFIRMATION_REQUIRED", "acknowledgePending");
        expect(422, "CLOSING_MONTH_NOT_ALLOWED", "month");
        expect(409, "MONTH_ALREADY_CLOSED", null);
        expect(409, "IDEMPOTENCY_CONFLICT", "Idempotency-Key");
        expect(400, "REPORT_QUERY_INVALID", "month");
        expect(403, "ACTIVE_SPACE_ACCESS_NOT_FOUND", null);
    }

    @Test
    void refusesMissingKeyOrMalformedBodyWithoutCallingTheUseCase() throws Exception {
        mvc.perform(post("/reports/closings/2026-10").principal(() -> "ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgePending\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_QUERY_INVALID"));
        mvc.perform(post("/reports/closings/2026-10").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", KEY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgePending\":"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/reports/closings/2026-10").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", "not-a-uuid").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    private void expect(int status, String code, String field) throws Exception {
        var result = mvc.perform(post("/reports/closings/2026-10").principal(() -> "ana@example.com")
                        .header("Idempotency-Key", KEY.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgePending\":false}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.operationId").isNotEmpty());
        if (field != null) result.andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }
}
