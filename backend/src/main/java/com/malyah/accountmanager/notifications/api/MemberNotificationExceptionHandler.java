package com.malyah.accountmanager.notifications.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.notifications.application.MemberNotificationNotFoundException;
import com.malyah.accountmanager.notifications.application.NotificationQueryValidationException;

@RestControllerAdvice(assignableTypes = {MemberNotificationController.class})
class MemberNotificationExceptionHandler {
    @ExceptionHandler(NotificationQueryValidationException.class)
    ResponseEntity<ApiError> invalid(NotificationQueryValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "NOTIFICATION_QUERY_INVALID", exception.getMessage(),
                List.of(new FieldError(exception.field(), exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> malformed(MethodArgumentTypeMismatchException exception) {
        var message = "id".equals(exception.getName()) ? "Identificador de aviso inválido."
                : "Informe um número inteiro.";
        return response(HttpStatus.BAD_REQUEST, "NOTIFICATION_QUERY_INVALID", message,
                List.of(new FieldError(exception.getName(), message)));
    }

    @ExceptionHandler(MemberNotificationNotFoundException.class)
    ResponseEntity<ApiError> notFound(MemberNotificationNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", exception.getMessage(), List.of());
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
