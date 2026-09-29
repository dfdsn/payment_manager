package com.malyah.accountmanager.notifications.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.notifications.application.NotificationAdministratorRequiredException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsIdempotencyConflictException;
import com.malyah.accountmanager.notifications.application.ReminderSettingsVersionConflictException;
import com.malyah.accountmanager.notifications.application.WhatsAppActivationRequiredException;
import com.malyah.accountmanager.notifications.application.WhatsAppConsentNotActiveException;
import com.malyah.accountmanager.notifications.application.WhatsAppRecipientMismatchException;
import com.malyah.accountmanager.notifications.domain.NotificationValidationException;
import com.malyah.accountmanager.notifications.domain.ReminderSchedule;

@RestControllerAdvice(assignableTypes = {ReminderSettingsController.class})
class NotificationsApiExceptionHandler {
    @ExceptionHandler(NotificationValidationException.class)
    ResponseEntity<ApiError> validation(NotificationValidationException exception) {
        var status = ReminderSchedule.INVALID.equals(exception.code()) ? HttpStatus.UNPROCESSABLE_CONTENT
                : HttpStatus.BAD_REQUEST;
        return response(status, exception.code(), exception.getMessage(),
                List.of(new FieldError(exception.field(), exception.getMessage())));
    }

    @ExceptionHandler(WhatsAppActivationRequiredException.class)
    ResponseEntity<ApiError> activation(WhatsAppActivationRequiredException exception) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, exception.code(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(NotificationAdministratorRequiredException.class)
    ResponseEntity<ApiError> administrator(NotificationAdministratorRequiredException exception) {
        return response(HttpStatus.FORBIDDEN, "NOTIFICATION_ADMINISTRATOR_REQUIRED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(ReminderSettingsVersionConflictException.class)
    ResponseEntity<ApiError> version(ReminderSettingsVersionConflictException exception) {
        return response(HttpStatus.CONFLICT, "NOTIFICATION_SETTINGS_VERSION_CONFLICT", exception.getMessage(),
                List.of(new FieldError("expectedVersion", exception.getMessage())));
    }

    @ExceptionHandler(ReminderSettingsIdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotency(ReminderSettingsIdempotencyConflictException exception) {
        return response(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage(),
                List.of(new FieldError("Idempotency-Key", exception.getMessage())));
    }

    @ExceptionHandler(WhatsAppRecipientMismatchException.class)
    ResponseEntity<ApiError> mismatch(WhatsAppRecipientMismatchException exception) {
        return response(HttpStatus.CONFLICT, "WHATSAPP_RECIPIENT_MISMATCH", exception.getMessage(),
                List.of(new FieldError("phone", exception.getMessage())));
    }

    @ExceptionHandler(WhatsAppConsentNotActiveException.class)
    ResponseEntity<ApiError> notActive(WhatsAppConsentNotActiveException exception) {
        return response(HttpStatus.CONFLICT, "WHATSAPP_CONSENT_NOT_ACTIVE", exception.getMessage(), List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception exception) {
        var message = "Revise o formato dos dados e a chave de repetição.";
        return response(HttpStatus.BAD_REQUEST, "REMINDER_SETTINGS_INVALID", message,
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
