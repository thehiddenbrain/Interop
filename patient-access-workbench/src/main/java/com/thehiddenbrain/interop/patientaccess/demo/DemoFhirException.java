package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * An error the demo FHIR server reports the FHIR way: an HTTP status and an OperationOutcome whose issue code
 * says what went wrong ({@code not-found}, {@code invalid}, {@code not-supported}, {@code login}, {@code forbidden}).
 * The conformance suite inspects exactly these, so the codes follow the R4 issue-type value set.
 */
public class DemoFhirException extends RuntimeException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpStatus status;
    private final String issueCode;
    private final Map<String, String> headers;

    public DemoFhirException(HttpStatus status, String issueCode, String diagnostics) {
        this(status, issueCode, diagnostics, Map.of());
    }

    public DemoFhirException(HttpStatus status, String issueCode, String diagnostics, Map<String, String> headers) {
        super(diagnostics);
        this.status = status;
        this.issueCode = issueCode;
        this.headers = headers;
    }

    public static DemoFhirException notFound(String diagnostics) {
        return new DemoFhirException(HttpStatus.NOT_FOUND, "not-found", diagnostics);
    }

    public static DemoFhirException invalid(String diagnostics) {
        return new DemoFhirException(HttpStatus.BAD_REQUEST, "invalid", diagnostics);
    }

    public static DemoFhirException notSupported(String diagnostics) {
        return new DemoFhirException(HttpStatus.BAD_REQUEST, "not-supported", diagnostics);
    }

    /** 401 with the challenge a SMART client expects. */
    public static DemoFhirException login() {
        return new DemoFhirException(HttpStatus.UNAUTHORIZED, "login", "missing or invalid bearer token",
                Map.of("WWW-Authenticate", "Bearer realm=\"demo\""));
    }

    public static DemoFhirException forbidden(String diagnostics) {
        return new DemoFhirException(HttpStatus.FORBIDDEN, "forbidden", diagnostics);
    }

    public HttpStatus status() {
        return status;
    }

    public String issueCode() {
        return issueCode;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public ObjectNode outcome() {
        return operationOutcome("error", issueCode, getMessage());
    }

    public static ObjectNode operationOutcome(String severity, String code, String diagnostics) {
        ObjectNode outcome = MAPPER.createObjectNode();
        outcome.put("resourceType", "OperationOutcome");
        ObjectNode issue = outcome.putArray("issue").addObject();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("diagnostics", diagnostics);
        return outcome;
    }
}
