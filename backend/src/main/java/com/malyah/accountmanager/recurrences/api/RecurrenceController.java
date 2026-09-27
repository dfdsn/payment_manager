package com.malyah.accountmanager.recurrences.api;

import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.malyah.accountmanager.recurrences.application.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/recurrences")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="spring.datasource.url")
class RecurrenceController {
    private final RecurrenceUseCase useCase;
    RecurrenceController(RecurrenceUseCase useCase) { this.useCase=useCase; }

    @PostMapping ResponseEntity<RecurrenceView> create(Principal principal,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CreateRecurrenceRequest request) {
        var result=useCase.create(principal.getName(),new CreateRecurrenceCommand(request.description(),request.amount(),
                request.valueType(),request.frequency(),request.firstDueDate(),request.lastDueDate(),request.categoryId(),
                request.responsibleUserId(),key));
        if(result.replayed()) return ResponseEntity.ok(result.recurrence());
        return ResponseEntity.created(URI.create("/api/v1/recurrences/"+result.recurrence().id())).body(result.recurrence());
    }

    @GetMapping List<RecurrenceView> list(Principal principal) { return useCase.list(principal.getName()); }

    @PostMapping("/calendar-preview") List<LocalDate> preview(Principal principal,
            @Valid @RequestBody CalendarPreviewRequest request) {
        return useCase.preview(request.firstDueDate(),request.lastDueDate(),request.frequency());
    }
}
