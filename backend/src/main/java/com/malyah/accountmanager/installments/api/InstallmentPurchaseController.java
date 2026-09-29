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
import com.malyah.accountmanager.installments.application.InstallmentAdjustmentUseCase;
import com.malyah.accountmanager.installments.application.InstallmentCancellationCommand;
import com.malyah.accountmanager.installments.application.InstallmentChangeCommand;
import com.malyah.accountmanager.installments.application.InstallmentChangeResult;
import com.malyah.accountmanager.installments.application.InstallmentImpactView;
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
    private final InstallmentAdjustmentUseCase adjustments;

    InstallmentPurchaseController(InstallmentPurchaseUseCase useCase, InstallmentAdjustmentUseCase adjustments) {
        this.useCase = useCase;
        this.adjustments = adjustments;
    }

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

    @PostMapping("/{id}/changes/preview")
    InstallmentImpactView previewChange(Principal principal, @PathVariable UUID id,
            @Valid @RequestBody InstallmentChangeRequest request) {
        return adjustments.previewChange(principal.getName(), change(id, request, null));
    }

    @PostMapping("/{id}/changes")
    ResponseEntity<InstallmentChangeResult> applyChange(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody InstallmentChangeRequest request) {
        return ResponseEntity.ok(adjustments.applyChange(principal.getName(), change(id, request, key)));
    }

    @PostMapping("/{id}/cancellation/preview")
    InstallmentImpactView previewCancellation(Principal principal, @PathVariable UUID id,
            @Valid @RequestBody InstallmentCancellationRequest request) {
        return adjustments.previewCancellation(principal.getName(), cancellation(id, request, null));
    }

    @PostMapping("/{id}/cancellation")
    ResponseEntity<InstallmentChangeResult> applyCancellation(Principal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody InstallmentCancellationRequest request) {
        return ResponseEntity.ok(adjustments.applyCancellation(principal.getName(), cancellation(id, request, key)));
    }

    private static InstallmentChangeCommand change(UUID id, InstallmentChangeRequest request, UUID key) {
        return new InstallmentChangeCommand(id, request.fromNumber(), request.scope(), request.changedFields(),
                request.description(), request.categoryId(), request.responsibleUserId(), request.dueDate(),
                request.impactToken(), key);
    }

    private static InstallmentCancellationCommand cancellation(UUID id, InstallmentCancellationRequest request,
            UUID key) {
        return new InstallmentCancellationCommand(id, request.installmentNumbers(), request.reason(),
                request.replacement() == null ? null : command(request.replacement(), null), request.impactToken(), key);
    }

    private static InstallmentPurchaseCommand command(InstallmentPurchaseRequest request, UUID key) {
        return new InstallmentPurchaseCommand(request.description(), request.totalAmount(), request.installmentCount(),
                request.firstDueDate(), request.categoryId(), request.responsibleUserId(), key);
    }
}
