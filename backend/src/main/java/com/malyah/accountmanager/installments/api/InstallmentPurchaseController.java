package com.malyah.accountmanager.installments.api;

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
import com.malyah.accountmanager.installments.application.InstallmentPreviewView;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseCommand;
import com.malyah.accountmanager.installments.application.InstallmentPurchasePage;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseUseCase;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseView;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/installment-purchases")
@ConditionalOnProperty(name = "spring.datasource.url")
class InstallmentPurchaseController {
    private final InstallmentPurchaseUseCase useCase;

    InstallmentPurchaseController(InstallmentPurchaseUseCase useCase) { this.useCase = useCase; }

    @PostMapping("/preview")
    InstallmentPreviewView preview(Principal principal, @Valid @RequestBody InstallmentPurchaseRequest request) {
        return useCase.preview(principal.getName(), command(request, null));
    }

    @PostMapping
    ResponseEntity<InstallmentPurchaseView> create(Principal principal, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody InstallmentPurchaseRequest request) {
        var result = useCase.create(principal.getName(), command(request, key));
        if (result.replayed()) return ResponseEntity.ok(result.purchase());
        return ResponseEntity.created(URI.create("/api/v1/installment-purchases/" + result.purchase().id()))
                .body(result.purchase());
    }

    @GetMapping
    InstallmentPurchasePage list(Principal principal, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return useCase.list(principal.getName(), page, size);
    }

    @GetMapping("/{id}")
    InstallmentPurchaseView get(Principal principal, @PathVariable UUID id) {
        return useCase.get(principal.getName(), id);
    }

    private static InstallmentPurchaseCommand command(InstallmentPurchaseRequest request, UUID key) {
        return new InstallmentPurchaseCommand(request.description(), request.totalAmount(), request.installmentCount(),
                request.firstDueDate(), request.categoryId(), request.responsibleUserId(), key);
    }
}
