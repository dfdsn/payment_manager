package com.malyah.accountmanager.installments.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
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

/** H05.1 HTTP contract: routes, idempotency header, 201/200 and error codes. */
class InstallmentPurchaseHttpTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String BODY = """
            {"description":"Sofá","totalAmount":"100.00","installmentCount":3,"firstDueDate":"2026-10-31",
             "categoryId":null,"responsibleUserId":null}""";
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
                "100.00", List.of(new InstallmentView(1, 3, "33.33", LocalDate.of(2026, 10, 31), ID, ExpenseStatus.PENDING)));
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

    private void expect(String code, int status, String field) throws Exception {
        mvc.perform(post("/installment-purchases").principal(() -> "ana@example.com").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.field").value(field));
    }
}
