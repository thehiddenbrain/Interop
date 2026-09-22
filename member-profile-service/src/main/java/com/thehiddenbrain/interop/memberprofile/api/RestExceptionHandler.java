package com.thehiddenbrain.interop.memberprofile.api;

import com.thehiddenbrain.interop.memberprofile.memberdomain.MemberDomainException;
import com.thehiddenbrain.interop.memberprofile.permission.RelationshipResolver;
import com.thehiddenbrain.interop.memberprofile.service.MemberNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    public record ErrorResponse(String error, String message, Instant timestamp) {
    }

    @ExceptionHandler(MemberNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(MemberNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> badRequest(ConstraintViolationException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(MemberDomainException.class)
    ResponseEntity<ErrorResponse> upstream(MemberDomainException e) {
        log.error("MemberDomain failure: {}", e.getMessage(), e);
        return error(HttpStatus.BAD_GATEWAY, "MEMBER_DOMAIN_UNAVAILABLE", "Member data is temporarily unavailable");
    }

    @ExceptionHandler({RelationshipResolver.UnknownRelationshipException.class, IllegalStateException.class})
    ResponseEntity<ErrorResponse> memberData(RuntimeException e) {
        log.error("Member data cannot be evaluated: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "MEMBER_DATA_INCOMPLETE", e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, Instant.now()));
    }
}
