package com.thehiddenbrain.interop.epa.pave.model;

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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorView(ErrorKind kind, String title, String action, Integer httpStatus, List<FieldError> fieldErrors,
                        List<IssueView> issues, String reference) {

    public ErrorView {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ErrorView of(ErrorKind kind, String title, String action) {
        return new ErrorView(kind, title, action, null, List.of(), List.of(), null);
    }

    public static ErrorView validation(String title, List<FieldError> fieldErrors) {
        return new ErrorView(ErrorKind.VALIDATION, title, "Correct the marked fields and send again.", null, fieldErrors, List.of(), null);
    }

    public ErrorView withHttpStatus(Integer status) {
        return new ErrorView(kind, title, action, status, fieldErrors, issues, reference);
    }

    public ErrorView withIssues(List<IssueView> newIssues) {
        return new ErrorView(kind, title, action, httpStatus, fieldErrors, newIssues, reference);
    }

    public ErrorView withReference(String newReference) {
        return new ErrorView(kind, title, action, httpStatus, fieldErrors, issues, newReference);
    }
}
