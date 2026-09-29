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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.PaymentRecord;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.reporting.application.DueDashboardView;
import com.malyah.accountmanager.reporting.application.DueIndicatorsView;
import com.malyah.accountmanager.reporting.application.PaymentIndicatorsView;
import com.malyah.accountmanager.reporting.application.PaymentReportQuery;
import com.malyah.accountmanager.reporting.application.PaymentReportView;
import com.malyah.accountmanager.reporting.application.PaymentRowView;
import com.malyah.accountmanager.reporting.application.PlanningItemView;
import com.malyah.accountmanager.reporting.application.PlanningMonthView;
import com.malyah.accountmanager.reporting.application.PlanningQuery;
import com.malyah.accountmanager.reporting.application.PlanningTotalsView;
import com.malyah.accountmanager.reporting.application.PlanningUseCase;
import com.malyah.accountmanager.reporting.application.PlanningView;
import com.malyah.accountmanager.reporting.application.PreviousPendingView;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportQueryValidationException;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

/** H06.1/H06.2 HTTP contract: filters mapped to the use case, money as strings and error codes. */
class ReportingHttpTest {
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private ReportingUseCase useCase;
    private PlanningUseCase planning;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        useCase = mock(ReportingUseCase.class);
        planning = mock(PlanningUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReportingController(useCase, planning))
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

    @Test
    void paymentsMapFiltersPageAndOrderingAndReturnTheBasisPayerAndCorrectionAuthor() throws Exception {
        var user = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        var correction = new PaymentRecord.PaymentCorrection(user, "Ana", Instant.parse("2026-10-02T12:00:00Z"),
                List.of("paymentDate", "paidByUserId"));
        when(useCase.payments(eq("ana@example.com"), any())).thenReturn(new PaymentReportView("2026-10",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "PAYMENT_DATE", "America/Sao_Paulo",
                new PaymentIndicatorsView(6, "1177.33", "1162.33", "25.00", "10.00", "15.00"),
                List.of(new PaymentRowView(UUID.randomUUID(), "Academia", "ONE_OFF", null, LocalDate.of(2026, 10, 1),
                        "99.00", true, "99.00", "0.00", LocalDate.of(2026, 10, 1), user, "Bia", user, "Ana",
                        Instant.parse("2026-09-30T12:00:00Z"), false, null, null, 1, correction)),
                1, 5, 6, 2, "PAID_AMOUNT", "DESC"));

        mvc.perform(get("/reports/payments").principal(() -> "ana@example.com").param("month", "2026-10")
                        .param("payerUserId", user.toString()).param("withoutCategory", "true").param("page", "1")
                        .param("size", "5").param("sort", "PAID_AMOUNT").param("direction", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateBasis").value("PAYMENT_DATE"))
                .andExpect(jsonPath("$.indicators.paidTotal").value("1177.33"))
                .andExpect(jsonPath("$.indicators.adjustmentNet").value("15.00"))
                .andExpect(jsonPath("$.content[0].payerDisplayName").value("Bia"))
                .andExpect(jsonPath("$.content[0].recordedByDisplayName").value("Ana"))
                .andExpect(jsonPath("$.content[0].paidAmount").value("99.00"))
                .andExpect(jsonPath("$.content[0].lastCorrection.actorDisplayName").value("Ana"))
                .andExpect(jsonPath("$.content[0].lastCorrection.changedFields[1]").value("paidByUserId"))
                .andExpect(jsonPath("$.totalPages").value(2));
        verify(useCase).payments("ana@example.com", new PaymentReportQuery(new ReportFilters("2026-10", null, null,
                true, null, false, user, null), 1, 5, PaymentSort.PAID_AMOUNT, SortDirection.DESC));
    }

    @Test
    void paymentsDefaultToTheFirstPageByPaymentDateAndRejectMalformedParameters() throws Exception {
        mvc.perform(get("/reports/payments").principal(() -> "ana@example.com")).andExpect(status().isOk());
        verify(useCase).payments("ana@example.com", new PaymentReportQuery(ReportFilters.currentMonth(), 0, 20,
                PaymentSort.PAYMENT_DATE, SortDirection.ASC));
        mvc.perform(get("/reports/payments").principal(() -> "ana@example.com").param("sort", "AMOUNT"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_QUERY_INVALID"));
        mvc.perform(get("/reports/payments").principal(() -> "ana@example.com").param("page", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        when(useCase.payments(eq("ana@example.com"), any()))
                .thenThrow(new ReportQueryValidationException("size", "O tamanho da página deve ficar entre 1 e 100."))
                .thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(get("/reports/payments").principal(() -> "ana@example.com").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        mvc.perform(get("/reports/payments").principal(() -> "ana@example.com"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }

    @Test
    void planningMapsFiltersAndPageAndIdentifiesForecastsAndEstimates() throws Exception {
        var totals = new PlanningTotalsView(5, "1583.33", "1373.33", "210.00", 2, "1233.33", 3, "350.00", 1,
                "880.00", 4, "683.33", "900.00", "333.33", "350.00");
        var recurrence = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        when(planning.planning(eq("ana@example.com"), any())).thenReturn(new PlanningView("2026-10", "2027-10",
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 31), "DUE_DATE", LocalDate.of(2026, 10, 15),
                "America/Sao_Paulo", totals, List.of(new PlanningMonthView("2027-01", totals)), "2027-01", totals,
                List.of(new PlanningItemView("FORECAST", null, recurrence, "RECURRENCE", null, "Luz",
                        LocalDate.of(2027, 1, 20), LocalDate.of(2027, 1, 20), "210.00", true, "FORECAST", false,
                        null, null, null, null)), 1, 2, 5, 3));

        mvc.perform(get("/reports/planning").principal(() -> "ana@example.com").param("month", "2027-01")
                        .param("search", "luz").param("withoutCategory", "true").param("responsibleUserId",
                                CATEGORY.toString()).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizonStart").value("2026-10"))
                .andExpect(jsonPath("$.horizonEnd").value("2027-10"))
                .andExpect(jsonPath("$.dateBasis").value("DUE_DATE"))
                .andExpect(jsonPath("$.totals.plannedTotal").value("1583.33"))
                .andExpect(jsonPath("$.totals.estimatedTotal").value("210.00"))
                .andExpect(jsonPath("$.months[0].totals.forecastTotal").value("350.00"))
                .andExpect(jsonPath("$.content[0].kind").value("FORECAST"))
                .andExpect(jsonPath("$.content[0].estimated").value(true))
                .andExpect(jsonPath("$.content[0].amount").value("210.00"))
                .andExpect(jsonPath("$.totalPages").value(3));
        verify(planning).planning("ana@example.com", new PlanningQuery("2027-01", "luz", null, true, CATEGORY,
                false, 1, 2));
    }

    @Test
    void planningDefaultsToTheCurrentMonthAndMapsErrors() throws Exception {
        mvc.perform(get("/reports/planning").principal(() -> "ana@example.com")).andExpect(status().isOk());
        verify(planning).planning("ana@example.com", PlanningQuery.currentMonth());
        mvc.perform(get("/reports/planning").principal(() -> "ana@example.com").param("size", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        when(planning.planning(eq("ana@example.com"), any()))
                .thenThrow(new ReportQueryValidationException("month", "Escolha um mês do horizonte."))
                .thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(get("/reports/planning").principal(() -> "ana@example.com").param("month", "2030-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("month"));
        mvc.perform(get("/reports/planning").principal(() -> "ana@example.com"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }
}
