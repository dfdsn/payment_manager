package com.malyah.accountmanager.reporting.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.reporting.application.CsvFile;
import com.malyah.accountmanager.reporting.application.ExpenseExportQuery;
import com.malyah.accountmanager.reporting.application.ExportLimitExceededException;
import com.malyah.accountmanager.reporting.application.ExportUseCase;
import com.malyah.accountmanager.reporting.application.ForecastExportQuery;

/** H06.4 HTTP contract of the CSV downloads: parameters, headers and error codes. */
class ReportExportHttpTest {
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private ExportUseCase exports;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        exports = mock(ExportUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReportExportController(exports))
                .setControllerAdvice(new ReportingApiExceptionHandler()).build();
    }

    @Test
    void expensesAreDownloadedAsAnAttachmentWithTheListFilters() throws Exception {
        var csv = "﻿\"Descrição\"\r\n".getBytes(StandardCharsets.UTF_8);
        when(exports.expenses(eq("ana@example.com"), any()))
                .thenReturn(new CsvFile("despesas_pagamento_2026-10-01_a_2026-10-31.csv", csv, 0));

        mvc.perform(get("/reports/expenses/export").principal(() -> "ana@example.com").param("search", "luz")
                        .param("dateFrom", "2026-10-01").param("dateTo", "2026-10-31").param("dateBasis", "PAYMENT_DATE")
                        .param("categoryId", CATEGORY.toString()).param("payerUserId", PAYER.toString())
                        .param("status", "ALL").param("sort", "AMOUNT").param("direction", "DESC"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"despesas_pagamento_2026-10-01_a_2026-10-31.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Export-Rows", "0"))
                .andExpect(header().longValue("Content-Length", csv.length))
                .andExpect(content().bytes(csv));
        verify(exports).expenses("ana@example.com", new ExpenseExportQuery("luz", LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), ExpenseDateBasis.PAYMENT_DATE, CATEGORY, false, null, false, PAYER,
                ExpenseStatusFilter.ALL, ExpenseSort.AMOUNT, SortDirection.DESC));
    }

    @Test
    void defaultsMatchTheExpenseList() throws Exception {
        when(exports.expenses(eq("ana@example.com"), any())).thenReturn(new CsvFile("a.csv", new byte[] {1}, 1));
        mvc.perform(get("/reports/expenses/export").principal(() -> "ana@example.com"))
                .andExpect(status().isOk()).andExpect(header().string("X-Export-Rows", "1"));
        verify(exports).expenses("ana@example.com", new ExpenseExportQuery(null, null, null,
                ExpenseDateBasis.DUE_DATE, null, false, null, false, null, ExpenseStatusFilter.ACTIVE,
                ExpenseSort.REFERENCE_DATE, SortDirection.ASC));
    }

    @Test
    void forecastsHaveTheirOwnDownload() throws Exception {
        when(exports.forecasts(eq("ana@example.com"), any()))
                .thenReturn(new CsvFile("previsoes_2026-10_a_2027-10.csv", new byte[] {1, 2}, 2));
        mvc.perform(get("/reports/planning/export").principal(() -> "ana@example.com").param("search", "luz")
                        .param("withoutCategory", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"previsoes_2026-10_a_2027-10.csv\""))
                .andExpect(header().string("X-Export-Rows", "2"));
        verify(exports).forecasts("ana@example.com", new ForecastExportQuery("luz", null, true, null, false));
    }

    @Test
    void aSelectionAboveTheLimitIsRefusedWithAClearMessageAndNoFile() throws Exception {
        when(exports.expenses(eq("ana@example.com"), any())).thenThrow(new ExportLimitExceededException(10_001, 10_000));
        mvc.perform(get("/reports/expenses/export").principal(() -> "ana@example.com").param("status", "ALL"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.code").value("EXPORT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                        "A seleção tem 10.001 registros e a exportação aceita até 10.000.")));
    }

    @Test
    void invalidFiltersAndMissingMembershipUseTheReportErrors() throws Exception {
        when(exports.expenses(eq("ana@example.com"), any()))
                .thenThrow(new ExpenseQueryValidationException("category", "Escolha uma categoria ou Sem categoria."));
        mvc.perform(get("/reports/expenses/export").principal(() -> "ana@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_QUERY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("category"));
        when(exports.forecasts(eq("ex@example.com"), any())).thenThrow(new AuthenticatedUserContextNotFoundException());
        mvc.perform(get("/reports/planning/export").principal(() -> "ex@example.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_SPACE_ACCESS_NOT_FOUND"));
    }

    @Test
    void malformedParametersNeverReachTheUseCase() throws Exception {
        var mocked = mock(ExportUseCase.class);
        var strict = MockMvcBuilders.standaloneSetup(new ReportExportController(mocked))
                .setControllerAdvice(new ReportingApiExceptionHandler()).build();
        strict.perform(get("/reports/expenses/export").principal(() -> "ana@example.com").param("status", "SOME"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
        strict.perform(get("/reports/expenses/export").principal(() -> "ana@example.com").param("dateFrom", "31/10"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mocked);
    }
}
