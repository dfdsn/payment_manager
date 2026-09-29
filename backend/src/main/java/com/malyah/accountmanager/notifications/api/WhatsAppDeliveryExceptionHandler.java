package com.malyah.accountmanager.notifications.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.notifications.application.ReminderSummaryNotFoundException;
import com.malyah.accountmanager.notifications.application.WhatsAppAdministratorRequiredException;
import com.malyah.accountmanager.notifications.application.WhatsAppTestUnavailableException;

@RestControllerAdvice(assignableTypes = {WhatsAppDeliveryController.class})
class WhatsAppDeliveryExceptionHandler {
    @ExceptionHandler(WhatsAppAdministratorRequiredException.class)
    ResponseEntity<ApiError> administrator(WhatsAppAdministratorRequiredException exception) {
        return response(HttpStatus.FORBIDDEN, "NOTIFICATION_ADMINISTRATOR_REQUIRED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(WhatsAppTestUnavailableException.class)
    ResponseEntity<ApiError> unavailable(WhatsAppTestUnavailableException exception) {
        return response(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(ReminderSummaryNotFoundException.class)
    ResponseEntity<ApiError> notFound(ReminderSummaryNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "REMINDER_SUMMARY_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> malformed(Exception exception) {
        var message = "Revise o identificador e a chave de repetição.";
        return response(HttpStatus.BAD_REQUEST, "WHATSAPP_REQUEST_INVALID", message,
                List.of(new FieldError("request", message)));
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
