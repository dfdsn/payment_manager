package com.malyah.accountmanager.reporting.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.malyah.accountmanager.expenses.application.ExpenseQueryValidationException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.reporting.application.ExportLimitExceededException;
import com.malyah.accountmanager.reporting.application.ReportQueryValidationException;

@RestControllerAdvice(assignableTypes = {ReportingController.class, ReportExportController.class})
class ReportingApiExceptionHandler {
    @ExceptionHandler(ReportQueryValidationException.class)
    ResponseEntity<ApiError> reportValidation(ReportQueryValidationException exception) {
        return invalid(exception.field(), exception.getMessage());
    }

    @ExceptionHandler(ExpenseQueryValidationException.class)
    ResponseEntity<ApiError> filterValidation(ExpenseQueryValidationException exception) {
        return invalid(exception.field(), exception.getMessage());
    }

    @ExceptionHandler(ExportLimitExceededException.class)
    ResponseEntity<ApiError> exportLimit(ExportLimitExceededException exception) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, "EXPORT_LIMIT_EXCEEDED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return invalid(exception.getName(), "Revise o formato dos filtros informados.");
    }

    @ExceptionHandler(AuthenticatedUserContextNotFoundException.class)
    ResponseEntity<ApiError> accessDenied(AuthenticatedUserContextNotFoundException exception) {
        return response(HttpStatus.FORBIDDEN, "ACTIVE_SPACE_ACCESS_NOT_FOUND", exception.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> invalid(String field, String message) {
        return response(HttpStatus.BAD_REQUEST, "REPORT_QUERY_INVALID", message, List.of(new FieldError(field, message)));
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
            List<FieldError> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(code, message, fieldErrors, UUID.randomUUID().toString()));
    }

    record ApiError(String code, String message, List<FieldError> fieldErrors, String operationId) { }

    record FieldError(String field, String message) { }
}
