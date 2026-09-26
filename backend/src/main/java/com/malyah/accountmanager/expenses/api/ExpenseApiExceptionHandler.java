package com.malyah.accountmanager.expenses.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.malyah.accountmanager.expenses.application.ExpenseIdempotencyConflictException;
import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.expenses.domain.ExpenseValidationException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;

@RestControllerAdvice(assignableTypes = {ExpenseController.class, CategoryController.class})
class ExpenseApiExceptionHandler {
    @ExceptionHandler(com.malyah.accountmanager.expenses.application.CategoryConflictException.class)
    ResponseEntity<ApiError> categoryConflict(com.malyah.accountmanager.expenses.application.CategoryConflictException exception) {
        return response(HttpStatus.CONFLICT, "CATEGORY_CONFLICT", exception.getMessage(), List.of());
    }
    @ExceptionHandler(com.malyah.accountmanager.expenses.application.CategoryNotFoundException.class)
    ResponseEntity<ApiError> categoryNotFound() {
        return response(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Categoria não encontrada.", List.of());
    }
    @ExceptionHandler(com.malyah.accountmanager.expenses.application.BatchSettlementConflictException.class)
    ResponseEntity<ApiError> batchConflict(
            com.malyah.accountmanager.expenses.application.BatchSettlementConflictException exception) {
        var fields = exception.problems().stream()
                .map(problem -> new FieldError("items[" + problem.expenseId() + "]",
                        problem.code() + ": " + problem.message()))
                .toList();
        return response(HttpStatus.CONFLICT, "BATCH_SETTLEMENT_CONFLICT", exception.getMessage(), fields);
    }

    @ExceptionHandler(com.malyah.accountmanager.expenses.application.ExpenseStateConflictException.class)
    ResponseEntity<ApiError> stateConflict(RuntimeException exception) {
        return response(HttpStatus.CONFLICT, "EXPENSE_STATE_CONFLICT", exception.getMessage(), List.of());
    }

    @ExceptionHandler(com.malyah.accountmanager.expenses.application.ExpenseNotFoundException.class)
    ResponseEntity<ApiError> notFound(RuntimeException exception) {
        return response(HttpStatus.NOT_FOUND, "EXPENSE_NOT_FOUND", exception.getMessage(), List.of());
    }
    @ExceptionHandler(ExpenseValidationException.class)
    ResponseEntity<ApiError> domainValidation(ExpenseValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "EXPENSE_VALIDATION_FAILED", exception.getMessage(),
                List.of(new FieldError(exception.field(), exception.getMessage())));
    }

    @ExceptionHandler(ExpenseQueryValidationException.class)
    ResponseEntity<ApiError> queryValidation(ExpenseQueryValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "EXPENSE_QUERY_INVALID", exception.getMessage(),
                List.of(new FieldError(exception.field(), exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> requestValidation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList();
        return response(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED", "Revise os campos informados.", fields);
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MissingRequestHeaderException.class })
    ResponseEntity<ApiError> malformedRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED",
                "Revise o formato dos dados e informe a chave de repetição.", List.of());
    }

    @ExceptionHandler(ExpenseIdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(ExpenseIdempotencyConflictException exception) {
        return response(HttpStatus.CONFLICT, "EXPENSE_IDEMPOTENCY_CONFLICT", exception.getMessage(), List.of());
    }

    @ExceptionHandler(AuthenticatedUserContextNotFoundException.class)
    ResponseEntity<ApiError> accessDenied(AuthenticatedUserContextNotFoundException exception) {
        return response(HttpStatus.FORBIDDEN, "ACTIVE_SPACE_ACCESS_NOT_FOUND", exception.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(code, message, fieldErrors, UUID.randomUUID().toString()));
    }

    record ApiError(String code, String message, List<FieldError> fieldErrors, String operationId) {
    }

    record FieldError(String field, String message) {
    }
}
