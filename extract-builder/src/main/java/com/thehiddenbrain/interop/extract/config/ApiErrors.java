package com.thehiddenbrain.interop.extract.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** Every error the API returns has the same shape: {@code {"error": "...", "details": [...]}}. */
@RestControllerAdvice
public class ApiErrors {

    public static class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) { super(message); }
    }

    public static class BadRequest extends RuntimeException {
        private final List<String> details;
        public BadRequest(String message) { this(message, List.of()); }
        public BadRequest(String message, List<String> details) { super(message); this.details = details; }
        public List<String> details() { return details; }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFound e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), List.of());
    }

    @ExceptionHandler(Forbidden.class)
    public ResponseEntity<Map<String, Object>> forbidden(Forbidden e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage(), List.of());
    }

    @ExceptionHandler(BadRequest.class)
    public ResponseEntity<Map<String, Object>> badRequest(BadRequest e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), e.details());
    }

    @ExceptionHandler(Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(Conflict e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), List.of());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, List<String> details) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message, "details", details));
    }
}
