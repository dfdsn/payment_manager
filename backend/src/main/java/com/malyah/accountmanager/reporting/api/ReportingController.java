package com.malyah.accountmanager.reporting.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.reporting.application.DueDashboardView;
import com.malyah.accountmanager.reporting.application.PaymentReportQuery;
import com.malyah.accountmanager.reporting.application.PlanningQuery;
import com.malyah.accountmanager.reporting.application.PlanningUseCase;
import com.malyah.accountmanager.reporting.application.PlanningView;
import com.malyah.accountmanager.reporting.application.PaymentReportView;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

@RestController
@RequestMapping("/reports")
@ConditionalOnProperty(name = "spring.datasource.url")
class ReportingController {
    private final ReportingUseCase useCase;
    private final PlanningUseCase planning;

    ReportingController(ReportingUseCase useCase, PlanningUseCase planning) {
        this.useCase = useCase;
        this.planning = planning;
    }

    @GetMapping("/due-dashboard")
    DueDashboardView dueDashboard(Principal principal,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean withoutCategory,
            @RequestParam(required = false) UUID responsibleUserId,
            @RequestParam(defaultValue = "false") boolean withoutResponsible,
            @RequestParam(required = false) UUID payerUserId,
            @RequestParam(defaultValue = "ACTIVE") ExpenseStatusFilter status) {
        return useCase.dueDashboard(principal.getName(), new ReportFilters(month, search, categoryId,
                withoutCategory, responsibleUserId, withoutResponsible, payerUserId, status));
    }

    @GetMapping("/payments")
    PaymentReportView payments(Principal principal,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean withoutCategory,
            @RequestParam(required = false) UUID responsibleUserId,
            @RequestParam(defaultValue = "false") boolean withoutResponsible,
            @RequestParam(required = false) UUID payerUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "PAYMENT_DATE") PaymentSort sort,
            @RequestParam(defaultValue = "ASC") SortDirection direction) {
        return useCase.payments(principal.getName(), new PaymentReportQuery(new ReportFilters(month, search,
                categoryId, withoutCategory, responsibleUserId, withoutResponsible, payerUserId, null), page, size,
                sort, direction));
    }

    /** H06.3: current month plus 12 by due date, materialized expenses and forecasts without double counting. */
    @GetMapping("/planning")
    PlanningView planning(Principal principal,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean withoutCategory,
            @RequestParam(required = false) UUID responsibleUserId,
            @RequestParam(defaultValue = "false") boolean withoutResponsible,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return planning.planning(principal.getName(), new PlanningQuery(month, search, categoryId, withoutCategory,
                responsibleUserId, withoutResponsible, page, size));
    }
}
