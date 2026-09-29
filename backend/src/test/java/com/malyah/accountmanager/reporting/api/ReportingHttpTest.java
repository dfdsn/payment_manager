package com.malyah.accountmanager.reporting.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.reporting.application.DueDashboardView;
import com.malyah.accountmanager.reporting.application.DueIndicatorsView;
import com.malyah.accountmanager.reporting.application.PreviousPendingView;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportQueryValidationException;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

/** H06.1 HTTP contract: filters mapped to the use case, money as strings and error codes. */
class ReportingHttpTest {
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private ReportingUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        useCase = mock(ReportingUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReportingController(useCase))
                .setControllerAdvice(new ReportingApiExceptionHandler()).build();
    }

    @Test
    void dueDashboardMapsFiltersAndReturnsMoneyAsStrings() throws Exception {
        when(useCase.dueDashboard(eq("ana@example.com"), any())).thenReturn(new DueDashboardView("2026-10",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "DUE_DATE", LocalDate.of(2026, 10, 15),
                "America/Sao_Paulo", new DueIndicatorsView(8, "2792.33", "180.00", 5, "1022.33", 3, "1780.00",
                        "180.00", 1, "1500.00", "0.00", "20.00", "10.00", "10.00"),
                new PreviousPendingView(LocalDate.of(2026, 10, 1), 1, "800.00", "0.00", 1, "800.00")));

        mvc.perform(get("/reports/due-dashboard").principal(() -> "ana@example.com").param("month", "2026-10")
                        .param("search", "out").param("categoryId", CATEGORY.toString()).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateBasis").value("DUE_DATE"))
                .andExpect(jsonPath("$.periodStart").value("2026-10-01"))
                .andExpect(jsonPath("$.indicators.plannedTotal").value("2792.33"))
                .andExpect(jsonPath("$.indicators.pendingTotal").value("1780.00"))
                .andExpect(jsonPath("$.indicators.adjustmentNet").value("10.00"))
                .andExpect(jsonPath("$.previousPending.total").value("800.00"));
        verify(useCase).dueDashboard("ana@example.com", new ReportFilters("2026-10", "out", CATEGORY, false, null,
                false, null, ExpenseStatusFilter.PENDING));
    }

    @Test
    void defaultsToActiveEntriesOfTheCurrentMonth() throws Exception {
        mvc.perform(get("/reports/due-dashboard").principal(() -> "ana@example.com")).andExpect(status().isOk());
        verify(useCase).dueDashboard("ana@example.com", new ReportFilters(null, null, null, false, null, false, null,
                ExpenseStatusFilter.ACTIVE));
    }

    @Test
    void mapsValidationAndAccessErrors() throws Exception {
        when(useCase.dueDashboard(eq("ana@example.com"), any()))
                .thenThrow(new ReportQueryValidationException("month", "Informe o mês no formato AAAA-MM."))
                .thenThrow(new ExpenseQueryValidationException("category", "Escolha uma categoria ou Sem categoria."))
                .thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(get("/reports/due-dashboard").principal(() -> "ana@example.com").param("month", "2026-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_QUERY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("month"))
                .andExpect(jsonPath("$.operationId").isNotEmpty());
        mvc.perform(get("/reports/due-dashboard").principal(() -> "ana@example.com"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("category"));
        mvc.perform(get("/reports/due-dashboard").principal(() -> "ana@example.com"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }

    @Test
    void rejectsMalformedFiltersWithoutCallingTheUseCase() throws Exception {
        mvc.perform(get("/reports/due-dashboard").principal(() -> "ana@example.com").param("status", "LATE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
        mvc.perform(get("/reports/due-dashboard").principal(() -> "ana@example.com").param("categoryId", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_QUERY_INVALID"));
        verifyNoInteractions(useCase);
    }
}
