package org.p32h.interop.epa.pave.model;

import java.util.List;

/**
 * One issue from a FHIR OperationOutcome, shown as the service sent it. An OperationOutcome is
 * rendered issue by issue and never reduced to a status code and a sentence.
 *
 * <p>{@code expression} and {@code location} are lists, and a list is empty both when the issue
 * did not hold the element and when it held an empty array. A screen that says "not returned"
 * for one and "returned empty" for the other reads the two presence flags beside them.
 *
 * @param expressionPresent whether the issue held an {@code expression} element at all, whatever its value
 * @param locationPresent   whether the issue held a {@code location} element at all, whatever its value
 */
public record IssueView(Shown severity, Shown code, Shown diagnostics, Shown detailsText, List<Shown> detailsCodes,
                        List<Shown> expression, List<Shown> location, boolean expressionPresent, boolean locationPresent) {

    public IssueView {
        detailsCodes = detailsCodes == null ? List.of() : List.copyOf(detailsCodes);
        expression = expression == null ? List.of() : List.copyOf(expression);
        location = location == null ? List.of() : List.copyOf(location);
    }

    /**
     * An issue built by this tool rather than read from a response, where an element is held
     * exactly when it has entries.
     */
    public IssueView(Shown severity, Shown code, Shown diagnostics, Shown detailsText, List<Shown> detailsCodes,
                     List<Shown> expression, List<Shown> location) {
        this(severity, code, diagnostics, detailsText, detailsCodes, expression, location,
                expression != null && !expression.isEmpty(), location != null && !location.isEmpty());
    }
}
