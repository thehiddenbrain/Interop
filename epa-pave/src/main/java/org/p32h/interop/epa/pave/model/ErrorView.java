package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one error shape this tool returns, both as the body of a failed request to the tool and
 * inside a run result when the vendor call failed.
 *
 * @param kind        what kind of thing went wrong
 * @param title       what happened, in one plain sentence
 * @param action      what the tester should do about it
 * @param httpStatus  the vendor's HTTP status when there was one, otherwise null
 * @param fieldErrors per-field problems for a validation failure, otherwise empty
 * @param issues      OperationOutcome issues when the vendor sent one, otherwise empty
 * @param reference   an id that also appears in the server log, for a defect in this tool
 * @param heading     what kind of thing went wrong, in words, set above the title. Given for every
 *                    kind, so that the page words nothing of its own: by default the kind's own
 *                    heading ({@link #headingOf}), and a heading of its own where the kind alone
 *                    would say something untrue, such as a timeout a mock scenario played
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorView(ErrorKind kind, String title, String action, Integer httpStatus, List<FieldError> fieldErrors,
                        List<IssueView> issues, String reference, String heading) {

    public ErrorView {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        issues = issues == null ? List.of() : List.copyOf(issues);
        heading = Texts.present(heading) ? heading : headingOf(kind);
    }

    /** An error headed as its kind is. */
    public ErrorView(ErrorKind kind, String title, String action, Integer httpStatus, List<FieldError> fieldErrors,
                     List<IssueView> issues, String reference) {
        this(kind, title, action, httpStatus, fieldErrors, issues, reference, null);
    }

    /** The heading of an error of this kind. Null for no kind, which the page then names by its absence. */
    public static String headingOf(ErrorKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case VALIDATION -> "A problem with what was submitted";
            case CONFIGURATION -> "About the configuration of this tool, not about the request";
            case BLOCKED -> "Refused by the production guardrail";
            case VENDOR_REJECTED -> "The service rejected the request";
            case VENDOR_ERROR -> "A fault in the environment: the service failed";
            case TIMEOUT -> "A fault in the environment: no answer in time";
            case UNREACHABLE -> "A fault in the environment: the service could not be reached";
            case MALFORMED_RESPONSE -> "A fault in the environment: the answer could not be read";
            case NOT_FOUND -> "Not found in this tool";
            case INTERNAL -> "A failure of this tool, not of the service";
        };
    }

    public static ErrorView of(ErrorKind kind, String title, String action) {
        return new ErrorView(kind, title, action, null, List.of(), List.of(), null);
    }

    public static ErrorView validation(String title, List<FieldError> fieldErrors) {
        return new ErrorView(ErrorKind.VALIDATION, title, "Correct the marked fields and send again.", null, fieldErrors, List.of(), null);
    }

    public ErrorView withHttpStatus(Integer status) {
        return new ErrorView(kind, title, action, status, fieldErrors, issues, reference, heading);
    }

    public ErrorView withIssues(List<IssueView> newIssues) {
        return new ErrorView(kind, title, action, httpStatus, fieldErrors, newIssues, reference, heading);
    }

    public ErrorView withReference(String newReference) {
        return new ErrorView(kind, title, action, httpStatus, fieldErrors, issues, newReference, heading);
    }

    public ErrorView withHeading(String newHeading) {
        return new ErrorView(kind, title, action, httpStatus, fieldErrors, issues, reference, newHeading);
    }

    /** Prints the kind and the status. The issues are a response's text, and a field error can quote what was typed. */
    @Override
    public String toString() {
        return "ErrorView[" + kind + ", " + httpStatus + "]";
    }
}
