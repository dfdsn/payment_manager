package com.malyah.accountmanager.recurrences.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.malyah.accountmanager.recurrences.application.RecurrenceIdempotencyConflictException;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValidationException;

@RestControllerAdvice(basePackages="com.malyah.accountmanager.recurrences.api")
class RecurrenceApiExceptionHandler {
    @ExceptionHandler(RecurrenceValidationException.class)
    ResponseEntity<?> validation(RecurrenceValidationException error) {
        return error(HttpStatus.BAD_REQUEST,"RECURRENCE_VALIDATION",error.getMessage(),error.field());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> beanValidation(MethodArgumentNotValidException error) {
        var field=error.getBindingResult().getFieldErrors().stream().findFirst();
        return error(HttpStatus.BAD_REQUEST,"RECURRENCE_VALIDATION",field.map(e->e.getDefaultMessage()).orElse("Dados inválidos."),field.map(e->e.getField()).orElse(null));
    }
    @ExceptionHandler(RecurrenceIdempotencyConflictException.class)
    ResponseEntity<?> conflict(RecurrenceIdempotencyConflictException error) {
        return error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT",error.getMessage(),"Idempotency-Key");
    }
    @ExceptionHandler(com.malyah.accountmanager.recurrences.application.RecurrenceOccurrenceException.class)
    ResponseEntity<?> occurrence(RuntimeException error) {
        return error(HttpStatus.CONFLICT,"RECURRENCE_OCCURRENCE_CONFLICT",error.getMessage(),"scheduledDueDate");
    }
    @ExceptionHandler(com.malyah.accountmanager.expenses.application.CategoryNotFoundException.class)
    ResponseEntity<?> category() { return error(HttpStatus.BAD_REQUEST,"CATEGORY_NOT_SELECTABLE","A categoria não está disponível no espaço.","categoryId"); }
    @ExceptionHandler(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class)
    ResponseEntity<?> access(RuntimeException error) { return error(HttpStatus.FORBIDDEN,"ACTIVE_SPACE_ACCESS_NOT_FOUND",error.getMessage(),""); }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<?> malformed(Exception error) { return error(HttpStatus.BAD_REQUEST,"RECURRENCE_VALIDATION","Revise o formato dos dados e a chave de repetição.",""); }
    private ResponseEntity<?> error(HttpStatus status,String code,String message,String field) {
        return ResponseEntity.status(status).body(Map.of("timestamp",Instant.now().toString(),"status",status.value(),
                "code",code,"message",message,"field",field==null?"":field));
    }
}
