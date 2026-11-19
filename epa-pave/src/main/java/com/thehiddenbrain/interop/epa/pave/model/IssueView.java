package com.thehiddenbrain.interop.epa.pave.model;

import java.util.List;

/**
 * One issue from a FHIR OperationOutcome, shown as the service sent it. An OperationOutcome is
 * rendered issue by issue and never reduced to a status code and a sentence.
 */
public record IssueView(Shown severity, Shown code, Shown diagnostics, Shown detailsText, List<Shown> detailsCodes,
                        List<Shown> expression, List<Shown> location) {

    public IssueView {
        detailsCodes = detailsCodes == null ? List.of() : List.copyOf(detailsCodes);
        expression = expression == null ? List.of() : List.copyOf(expression);
        location = location == null ? List.of() : List.copyOf(location);
    }
}
