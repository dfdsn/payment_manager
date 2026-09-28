package com.malyah.accountmanager.installments.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
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
import com.malyah.accountmanager.expenses.application.CategoryConflictException;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.expenses.domain.ExpenseValidationException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.installments.application.*;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;

/** H05.1/H05.2 HTTP contract: routes, idempotency header, 201/200 and error codes. */
class InstallmentPurchaseHttpTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String BODY = """
            {"description":"Sofá","totalAmount":"100.00","installmentCount":3,"firstDueDate":"2026-10-31",
             "categoryId":null,"responsibleUserId":null}""";
    private static final InstallmentProgress PROGRESS = new InstallmentProgress(3, 1, 2, 1, 0, "33.33", "66.67",
            "33.33", "0.00", LocalDate.of(2026, 9, 30));
    private InstallmentPurchaseUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        useCase = mock(InstallmentPurchaseUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new InstallmentPurchaseController(useCase))
                .setControllerAdvice(new InstallmentApiExceptionHandler()).build();
    }

    @Test
    void previewMapsTheRequestAndReturnsTheCalculation() throws Exception {
        when(useCase.preview(eq("ana@example.com"), any())).thenReturn(new InstallmentPreviewView("Sofá", "100.00", 3,
                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 12, 31), "33.33", "33.34", "0.01", "100.00",
                List.of(new InstallmentView(3, 3, "33.34", LocalDate.of(2026, 12, 31), null, null))));
        mvc.perform(post("/installment-purchases/preview").principal(() -> "ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastInstallmentAdjustment").value("0.01"))
                .andExpect(jsonPath("$.installments[0].amount").value("33.34"));
        verify(useCase).preview("ana@example.com", new InstallmentPurchaseCommand("Sofá", "100.00", 3,
                LocalDate.of(2026, 10, 31), null, null, null));
    }

    @Test
    void createReturns201ThenReplayReturns200() throws Exception {
        var view = new InstallmentPurchaseView(ID, "Sofá", "100.00", 3, LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 12, 31), null, null, null, null, ID, "Ana", Instant.parse("2026-09-28T12:00:00Z"),
                "100.00", PROGRESS,
                List.of(new InstallmentView(1, 3, "33.33", LocalDate.of(2026, 10, 31), ID, ExpenseStatus.PENDING)));
        when(useCase.create(eq("ana@example.com"), any())).thenReturn(new InstallmentPurchaseCreationResult(view, false),
                new InstallmentPurchaseCreationResult(view, true));
        mvc.perform(post("/installment-purchases").principal(() -> "ana@example.com").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/installment-purchases/" + ID))
                .andExpect(jsonPath("$.installments[0].status").value("PENDING"));
        mvc.perform(post("/installment-purchases").principal(() -> "ana@example.com").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ID.toString()));
        verify(useCase, times(2)).create("ana@example.com", new InstallmentPurchaseCommand("Sofá", "100.00", 3,
                LocalDate.of(2026, 10, 31), null, null, KEY));
    }

    @Test
    void mapsErrorsToStableCodes() throws Exception {
        when(useCase.create(any(), any())).thenThrow(new InstallmentValidationException("installmentCount", "Informe de 2 a 360 parcelas."),
                new InstallmentIdempotencyConflictException(), new CategoryConflictException("x"),
                new AuthenticatedUserContextNotFoundException(), new ExpenseValidationException("amount", "Valor inválido."));
        expect("INSTALLMENT_VALIDATION", 400, "installmentCount");
        expect("IDEMPOTENCY_CONFLICT", 409, "Idempotency-Key");
        expect("CATEGORY_NOT_SELECTABLE", 400, "categoryId");
        expect("ACTIVE_SPACE_ACCESS_NOT_FOUND", 403, "");
        expect("INSTALLMENT_VALIDATION", 400, "amount");
    }

    @Test
    void rejectsMissingFieldsKeyOrMalformedBodyBeforeCallingTheUseCase() throws Exception {
        mvc.perform(post("/installment-purchases/preview").principal(() -> "ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Sofá\",\"totalAmount\":\"10\",\"firstDueDate\":\"2026-10-31\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("installmentCount"));
        mvc.perform(post("/installment-purchases").principal(() -> "ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INSTALLMENT_VALIDATION"));
        mvc.perform(post("/installment-purchases/preview").principal(() -> "ana@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"installmentCount\":\"muitas\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    @Test
    void listAndDetailExposeProgressAndInstallmentSituations() throws Exception {
        when(useCase.list("ana@example.com", 1, 5)).thenReturn(new InstallmentPurchasePage(List.of(
                new InstallmentPurchaseSummary(ID, "Sofá", "100.00", 3, LocalDate.of(2026, 8, 31),
                        LocalDate.of(2026, 10, 31), "Casa", "Beto", Instant.parse("2026-08-01T12:00:00Z"), PROGRESS)),
                1, 5, 6));
        mvc.perform(get("/installment-purchases").principal(() -> "ana@example.com").param("page", "1").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(6))
                .andExpect(jsonPath("$.items[0].progress.paidCount").value(1))
                .andExpect(jsonPath("$.items[0].progress.pendingAmount").value("66.67"))
                .andExpect(jsonPath("$.items[0].progress.nextDueDate").value("2026-09-30"));
        when(useCase.list("ana@example.com", 0, 20)).thenReturn(new InstallmentPurchasePage(List.of(), 0, 20, 0));
        mvc.perform(get("/installment-purchases").principal(() -> "ana@example.com")).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));

        var paid = new InstallmentView(1, 3, "33.33", LocalDate.of(2026, 8, 31), ID, ExpenseStatus.PAID, 2L, false,
                "Sofá", null, null, null, null, LocalDate.of(2026, 8, 30), "33.33");
        when(useCase.get("ana@example.com", ID)).thenReturn(new InstallmentPurchaseView(ID, "Sofá", "100.00", 3,
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 10, 31), null, null, null, null, ID, "Ana",
                Instant.parse("2026-08-01T12:00:00Z"), "100.00", PROGRESS, List.of(paid)));
        mvc.perform(get("/installment-purchases/" + ID).principal(() -> "ana@example.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.installments[0].paymentDate").value("2026-08-30"))
                .andExpect(jsonPath("$.installments[0].version").value(2))
                .andExpect(jsonPath("$.progress.overdueCount").value(1));
    }

    @Test
    void detailAndListErrorsUseStableCodes() throws Exception {
        when(useCase.get(any(), any())).thenThrow(new InstallmentPurchaseNotFoundException());
        mvc.perform(get("/installment-purchases/" + ID).principal(() -> "ana@example.com"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INSTALLMENT_PURCHASE_NOT_FOUND"));
        mvc.perform(get("/installment-purchases/nao-e-uuid").principal(() -> "ana@example.com"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        when(useCase.list(any(), eq(0), eq(500))).thenThrow(new InstallmentValidationException("size", "Informe de 1 a 100."));
        mvc.perform(get("/installment-purchases").principal(() -> "ana@example.com").param("size", "500"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("size"));
    }

    private void expect(String code, int status, String field) throws Exception {
        mvc.perform(post("/installment-purchases").principal(() -> "ana@example.com").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.field").value(field));
    }
}
