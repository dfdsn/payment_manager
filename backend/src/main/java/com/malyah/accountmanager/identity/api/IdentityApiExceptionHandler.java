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
import com.malyah.accountmanager.identity.application.InvalidInvitationTokenException;
import com.malyah.accountmanager.identity.application.InvitationAdministratorRequiredException;
import com.malyah.accountmanager.identity.application.InvitationAlreadyPendingException;
import com.malyah.accountmanager.identity.application.InvitationEmailDeliveryException;
import com.malyah.accountmanager.identity.application.InvitationIdentityMismatchException;
import com.malyah.accountmanager.identity.application.InvitationLoginRequiredException;
import com.malyah.accountmanager.identity.application.InvitationTargetUnavailableException;
import com.malyah.accountmanager.identity.application.NoPendingInvitationException;
import com.malyah.accountmanager.identity.application.SpaceMemberLimitReachedException;
import com.malyah.accountmanager.identity.application.ManagedMemberNotFoundException;
import com.malyah.accountmanager.identity.application.MembershipAdministratorRequiredException;
import com.malyah.accountmanager.identity.application.MembershipConflictException;

@RestControllerAdvice(assignableTypes = {
        InitialSetupController.class, AuthenticatedUserContextController.class, AuthenticationController.class,
        InvitationController.class, MembershipController.class
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

    @ExceptionHandler(InvitationAdministratorRequiredException.class)
    ResponseEntity<ApiError> invitationAdministratorRequired(InvitationAdministratorRequiredException exception) {
        return response(HttpStatus.FORBIDDEN, "INVITATION_ADMIN_REQUIRED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(SpaceMemberLimitReachedException.class)
    ResponseEntity<ApiError> memberLimit(SpaceMemberLimitReachedException exception) {
        return response(HttpStatus.CONFLICT, "SPACE_MEMBER_LIMIT_REACHED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvitationAlreadyPendingException.class)
    ResponseEntity<ApiError> pendingInvitation(InvitationAlreadyPendingException exception) {
        return response(HttpStatus.CONFLICT, "INVITATION_ALREADY_PENDING", exception.getMessage(), List.of());
    }

    @ExceptionHandler(NoPendingInvitationException.class)
    ResponseEntity<ApiError> noPendingInvitation(NoPendingInvitationException exception) {
        return response(HttpStatus.NOT_FOUND, "INVITATION_NOT_PENDING", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvitationTargetUnavailableException.class)
    ResponseEntity<ApiError> unavailableTarget(InvitationTargetUnavailableException exception) {
        return response(HttpStatus.CONFLICT, "INVITATION_TARGET_UNAVAILABLE", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidInvitationTokenException.class)
    ResponseEntity<ApiError> invalidInvitation(InvalidInvitationTokenException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVITATION_INVALID", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvitationIdentityMismatchException.class)
    ResponseEntity<ApiError> invitationIdentityMismatch(InvitationIdentityMismatchException exception) {
        return response(HttpStatus.FORBIDDEN, "INVITATION_IDENTITY_MISMATCH", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvitationLoginRequiredException.class)
    ResponseEntity<ApiError> invitationLoginRequired(InvitationLoginRequiredException exception) {
        return response(HttpStatus.UNAUTHORIZED, "INVITATION_LOGIN_REQUIRED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(InvitationEmailDeliveryException.class)
    ResponseEntity<ApiError> invitationEmailDelivery(InvitationEmailDeliveryException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "INVITATION_EMAIL_DELIVERY_FAILED",
                exception.getMessage(), List.of());
    }

    @ExceptionHandler(MembershipAdministratorRequiredException.class)
    ResponseEntity<ApiError> membershipAdministratorRequired(MembershipAdministratorRequiredException exception) {
        return response(HttpStatus.FORBIDDEN, "MEMBERSHIP_ADMIN_REQUIRED", exception.getMessage(), List.of());
    }

    @ExceptionHandler(ManagedMemberNotFoundException.class)
    ResponseEntity<ApiError> memberNotFound(ManagedMemberNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ACTIVE_MEMBER_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler(MembershipConflictException.class)
    ResponseEntity<ApiError> membershipConflict(MembershipConflictException exception) {
        return response(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT", exception.getMessage(), List.of());
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
