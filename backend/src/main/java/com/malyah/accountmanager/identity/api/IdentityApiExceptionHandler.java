package com.malyah.accountmanager.identity.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.malyah.accountmanager.identity.application.InvalidSetupSecretException;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.application.SetupAlreadyCompletedException;
import com.malyah.accountmanager.identity.application.SetupSecretUnavailableException;
import com.malyah.accountmanager.identity.application.InvalidCredentialsException;
import com.malyah.accountmanager.identity.application.InvalidOrExpiredAccessTokenException;
import com.malyah.accountmanager.identity.domain.IdentityValidationException;

@RestControllerAdvice(assignableTypes = {
        InitialSetupController.class, AuthenticatedUserContextController.class, AuthenticationController.class
})
class IdentityApiExceptionHandler {

    @ExceptionHandler(IdentityValidationException.class)
    ResponseEntity<ApiError> domainValidation(IdentityValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "IDENTITY_VALIDATION_FAILED", exception.getMessage(),
                List.of(new FieldError(exception.field(), exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> requestValidation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED", "Revise os campos informados.", fields);
    }

    @ExceptionHandler(InvalidSetupSecretException.class)
    ResponseEntity<ApiError> invalidSecret(InvalidSetupSecretException exception) {
        return response(HttpStatus.FORBIDDEN, "SETUP_SECRET_INVALID", exception.getMessage(), List.of());
    }

    @ExceptionHandler(SetupAlreadyCompletedException.class)
    ResponseEntity<ApiError> completed(SetupAlreadyCompletedException exception) {
        return response(HttpStatus.CONFLICT, "SETUP_ALREADY_COMPLETED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(SetupSecretUnavailableException.class)
    ResponseEntity<ApiError> secretUnavailable(SetupSecretUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SETUP_SECRET_NOT_CONFIGURED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(AuthenticatedUserContextNotFoundException.class)
    ResponseEntity<ApiError> contextNotFound(AuthenticatedUserContextNotFoundException exception) {
        return response(HttpStatus.FORBIDDEN, "ACTIVE_SPACE_ACCESS_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException exception) {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidOrExpiredAccessTokenException.class)
    ResponseEntity<ApiError> invalidToken(InvalidOrExpiredAccessTokenException exception) {
        return response(HttpStatus.BAD_REQUEST, "ACCESS_TOKEN_INVALID", exception.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(
                code, message, fieldErrors, UUID.randomUUID().toString()));
    }

    record ApiError(String code, String message, List<FieldError> fieldErrors, String operationId) {
    }

    record FieldError(String field, String message) {
    }
}
