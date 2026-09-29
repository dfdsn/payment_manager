package com.malyah.accountmanager.reporting.api;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseSort;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.reporting.application.CsvFile;
import com.malyah.accountmanager.reporting.application.ExpenseExportQuery;
import com.malyah.accountmanager.reporting.application.ExportUseCase;
import com.malyah.accountmanager.reporting.application.ForecastExportQuery;

/** H06.4 CSV downloads. The whole file is built before the response starts, so an error never leaves half a file. */
@RestController
@RequestMapping("/reports")
@ConditionalOnProperty(name = "spring.datasource.url")
class ReportExportController {
    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    static final String ROWS_HEADER = "X-Export-Rows";
    private final ExportUseCase exports;

    ReportExportController(ExportUseCase exports) {
        this.exports = exports;
    }

    @GetMapping("/expenses/export")
    ResponseEntity<byte[]> expenses(Principal principal,
            @RequestParam(defaultValue = "REFERENCE_DATE") ExpenseSort sort,
            @RequestParam(defaultValue = "ASC") SortDirection direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(defaultValue = "DUE_DATE") ExpenseDateBasis dateBasis,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean withoutCategory,
            @RequestParam(required = false) UUID responsibleUserId,
            @RequestParam(defaultValue = "false") boolean withoutResponsible,
            @RequestParam(required = false) UUID payerUserId,
            @RequestParam(defaultValue = "ACTIVE") ExpenseStatusFilter status) {
        return download(exports.expenses(principal.getName(), new ExpenseExportQuery(search, dateFrom, dateTo,
                dateBasis, categoryId, withoutCategory, responsibleUserId, withoutResponsible, payerUserId, status,
                sort, direction)));
    }

    @GetMapping("/planning/export")
    ResponseEntity<byte[]> forecasts(Principal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean withoutCategory,
            @RequestParam(required = false) UUID responsibleUserId,
            @RequestParam(defaultValue = "false") boolean withoutResponsible) {
        return download(exports.forecasts(principal.getName(), new ForecastExportQuery(search, categoryId,
                withoutCategory, responsibleUserId, withoutResponsible)));
    }

    private static ResponseEntity<byte[]> download(CsvFile file) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .contentLength(file.content().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName()).build().toString())
                .header(ROWS_HEADER, Long.toString(file.rows()))
                .body(file.content());
    }
}
