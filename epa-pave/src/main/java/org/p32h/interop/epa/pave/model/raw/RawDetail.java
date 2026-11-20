package org.p32h.interop.epa.pave.model.raw;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.IssueView;

/**
 * What the tool can say about the answer to a request it does not interpret.
 *
 * <p>There is no designed view for an unmapped request: the tool does not know what its fields
 * mean. Everything the response carried is therefore in the result's additional fields, and this
 * says only what kind of thing came back. The one thing that is interpreted is a FHIR
 * OperationOutcome, because an error is an error whatever the request was.
 *
 * @param requestName  the collection request that was sent
 * @param responseKind json, not-json, no-body or no-response
 * @param issues       the issues of an OperationOutcome, when that is what came back
 * @param requestNotes what a tester should know about how the request was sent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RawDetail(String requestName, String responseKind, List<IssueView> issues, List<String> requestNotes) {

    public static final String KIND_JSON = "json";
    public static final String KIND_NOT_JSON = "not-json";
    public static final String KIND_NO_BODY = "no-body";
    public static final String KIND_NO_RESPONSE = "no-response";

    public RawDetail {
        issues = issues == null ? List.of() : List.copyOf(issues);
        requestNotes = requestNotes == null ? List.of() : List.copyOf(requestNotes);
    }

    /** Prints the kind of response. The issues come from a response. */
    @Override
    public String toString() {
        return "RawDetail[" + responseKind + "]";
    }
}
