package com.malyah.accountmanager.installments.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.malyah.accountmanager.installments.application.InstallmentIdempotencyConflictException;
import com.malyah.accountmanager.installments.application.InstallmentImpactChangedException;
import com.malyah.accountmanager.installments.application.InstallmentPurchaseNotFoundException;
import com.malyah.accountmanager.installments.domain.InstallmentStateConflictException;
import com.malyah.accountmanager.installments.domain.InstallmentValidationException;

@RestControllerAdvice(basePackages = "com.malyah.accountmanager.installments.api")
class InstallmentApiExceptionHandler {
    @ExceptionHandler(InstallmentValidationException.class)
    ResponseEntity<?> validation(InstallmentValidationException error) {
        return error(HttpStatus.BAD_REQUEST, "INSTALLMENT_VALIDATION", error.getMessage(), error.field());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> beanValidation(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldErrors().stream().findFirst();
        return error(HttpStatus.BAD_REQUEST, "INSTALLMENT_VALIDATION",
                field.map(e -> e.getDefaultMessage()).orElse("Dados inválidos."), field.map(e -> e.getField()).orElse(""));
    }

    @ExceptionHandler(com.malyah.accountmanager.expenses.domain.ExpenseValidationException.class)
    ResponseEntity<?> expense(com.malyah.accountmanager.expenses.domain.ExpenseValidationException error) {
        return error(HttpStatus.BAD_REQUEST, "INSTALLMENT_VALIDATION", error.getMessage(), error.field());
    }

    @ExceptionHandler(InstallmentIdempotencyConflictException.class)
    ResponseEntity<?> idempotency(RuntimeException error) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", error.getMessage(), "Idempotency-Key");
    }

    @ExceptionHandler({InstallmentImpactChangedException.class,
            com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class})
    ResponseEntity<?> impactChanged() {
        return error(HttpStatus.CONFLICT, "INSTALLMENT_IMPACT_CHANGED", new InstallmentImpactChangedException().getMessage(),
                "impactToken");
    }

    @ExceptionHandler(InstallmentStateConflictException.class)
    ResponseEntity<?> notPending(RuntimeException error) {
        return error(HttpStatus.CONFLICT, "INSTALLMENT_NOT_PENDING", error.getMessage(), "");
    }

    @ExceptionHandler(InstallmentPurchaseNotFoundException.class)
    ResponseEntity<?> notFound(RuntimeException error) {
        return error(HttpStatus.NOT_FOUND, "INSTALLMENT_PURCHASE_NOT_FOUND", error.getMessage(), "");
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException error) {
        return error(HttpStatus.BAD_REQUEST, "INSTALLMENT_VALIDATION", "Revise o formato dos dados.", error.getName());
    }

    @ExceptionHandler({com.malyah.accountmanager.expenses.application.CategoryConflictException.class,
            com.malyah.accountmanager.expenses.application.CategoryNotFoundException.class})
    ResponseEntity<?> category() {
        return error(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_SELECTABLE",
                "A categoria não está disponível para novas associações.", "categoryId");
    }

    @ExceptionHandler(com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException.class)
    ResponseEntity<?> access(RuntimeException error) {
        return error(HttpStatus.FORBIDDEN, "ACTIVE_SPACE_ACCESS_NOT_FOUND", error.getMessage(), "");
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<?> malformed(Exception error) {
        return error(HttpStatus.BAD_REQUEST, "INSTALLMENT_VALIDATION",
                "Revise o formato dos dados e a chave de repetição.", "");
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message, String field) {
        return ResponseEntity.status(status).body(Map.of("timestamp", Instant.now().toString(), "status", status.value(),
                "code", code, "message", message, "field", field == null ? "" : field));
    }
}
