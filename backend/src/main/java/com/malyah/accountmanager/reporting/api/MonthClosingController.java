package com.malyah.accountmanager.reporting.api;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.malyah.accountmanager.reporting.application.CloseMonthCommand;
import com.malyah.accountmanager.reporting.application.ClosingVersionListView;
import com.malyah.accountmanager.reporting.application.ClosingVersionView;
import com.malyah.accountmanager.reporting.application.GenerateVersionCommand;
import com.malyah.accountmanager.reporting.application.MonthClosingListView;
import com.malyah.accountmanager.reporting.application.MonthClosingUseCase;
import com.malyah.accountmanager.reporting.application.MonthClosingView;

/** E07: symbolic month closing by due date and its versions; both roles of the space may read, close and version. */
@RestController
@RequestMapping("/reports/closings")
@ConditionalOnProperty(name = "spring.datasource.url")
class MonthClosingController {
    private final MonthClosingUseCase useCase;

    MonthClosingController(MonthClosingUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    MonthClosingListView list(Principal principal, @RequestParam(required = false) String year) {
        return useCase.list(principal.getName(), year);
    }

    @GetMapping("/{month}")
    MonthClosingView view(Principal principal, @PathVariable String month) {
        return useCase.view(principal.getName(), month);
    }

    @PostMapping("/{month}")
    ResponseEntity<MonthClosingView> close(Principal principal, @PathVariable String month,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @RequestBody CloseMonthRequest request) {
        var result = useCase.close(principal.getName(),
                new CloseMonthCommand(month, request != null && Boolean.TRUE.equals(request.acknowledgePending()),
                        idempotencyKey));
        if (result.replayed()) return ResponseEntity.ok(result.closing());
        return ResponseEntity.created(URI.create("/api/v1/reports/closings/" + result.closing().month()))
                .body(result.closing());
    }

    @GetMapping("/{month}/versions")
    ClosingVersionListView versions(Principal principal, @PathVariable String month) {
        return useCase.versions(principal.getName(), month);
    }

    @GetMapping("/{month}/versions/{number}")
    ClosingVersionView version(Principal principal, @PathVariable String month, @PathVariable String number) {
        return useCase.version(principal.getName(), month, number);
    }

    @PostMapping("/{month}/versions")
    ResponseEntity<MonthClosingView> generateVersion(Principal principal, @PathVariable String month,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @RequestBody GenerateVersionRequest request) {
        var acknowledged = request != null && Boolean.TRUE.equals(request.acknowledgePending());
        var result = useCase.generateVersion(principal.getName(), new GenerateVersionCommand(month,
                request == null ? null : request.expectedVersion(), acknowledged, idempotencyKey));
        if (result.replayed()) return ResponseEntity.ok(result.closing());
        var closing = result.closing();
        return ResponseEntity.created(URI.create("/api/v1/reports/closings/" + closing.month() + "/versions/"
                + closing.saved().version())).body(closing);
    }

    record CloseMonthRequest(Boolean acknowledgePending) { }

    record GenerateVersionRequest(Integer expectedVersion, Boolean acknowledgePending) { }
}
