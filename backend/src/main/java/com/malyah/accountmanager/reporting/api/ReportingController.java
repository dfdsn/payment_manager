package com.malyah.accountmanager.reporting.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.reporting.application.DueDashboardView;
import com.malyah.accountmanager.reporting.application.ReportFilters;
import com.malyah.accountmanager.reporting.application.ReportingUseCase;

@RestController
@RequestMapping("/reports")
@ConditionalOnProperty(name = "spring.datasource.url")
class ReportingController {
    private final ReportingUseCase useCase;

    ReportingController(ReportingUseCase useCase) {
        this.useCase = useCase;
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
}
