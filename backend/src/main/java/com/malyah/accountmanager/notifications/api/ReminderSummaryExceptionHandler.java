package com.malyah.accountmanager.notifications.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.notifications.application.ReminderQueryValidationException;
import com.malyah.accountmanager.notifications.application.ReminderSummaryNotFoundException;

@RestControllerAdvice(assignableTypes = {ReminderSummaryController.class})
class ReminderSummaryExceptionHandler {
    @ExceptionHandler(ReminderQueryValidationException.class)
    ResponseEntity<ApiError> invalid(ReminderQueryValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "REMINDER_QUERY_INVALID", exception.getMessage(),
                List.of(new FieldError(exception.field(), exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> malformed(MethodArgumentTypeMismatchException exception) {
        var message = "Identificador de resumo inválido.";
        return response(HttpStatus.BAD_REQUEST, "REMINDER_QUERY_INVALID", message,
                List.of(new FieldError(exception.getName(), message)));
    }

    @ExceptionHandler(ReminderSummaryNotFoundException.class)
    ResponseEntity<ApiError> notFound(ReminderSummaryNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "REMINDER_SUMMARY_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler(AuthenticatedUserContextNotFoundException.class)
    ResponseEntity<ApiError> accessDenied(AuthenticatedUserContextNotFoundException exception) {
        return response(HttpStatus.FORBIDDEN, "ACTIVE_SPACE_ACCESS_NOT_FOUND", exception.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
            List<FieldError> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(code, message, fieldErrors, UUID.randomUUID().toString()));
    }

    record ApiError(String code, String message, List<FieldError> fieldErrors, String operationId) { }

    record FieldError(String field, String message) { }
}
