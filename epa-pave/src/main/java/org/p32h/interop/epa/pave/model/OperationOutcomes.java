package org.p32h.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reads a FHIR OperationOutcome issue by issue. An OperationOutcome is never reduced to a status
 * code and a sentence: a fault response often carries the most useful detail of a whole session.
 */
public final class OperationOutcomes {

    private OperationOutcomes() {
    }

    public static boolean is(JsonNode node) {
        return "OperationOutcome".equals(JsonSafe.text(node, "resourceType"));
    }

    /** Every issue, with its severity, code, diagnostics, details and the elements it points at. */
    public static List<IssueView> issues(TrackedJson.Node outcome) {
        List<IssueView> issues = new ArrayList<>();
        for (TrackedJson.Node issue : outcome.children("issue")) {
            if (!issue.isObject()) {
                continue;
            }
            TrackedJson.Node details = issue.child("details");
            List<Shown> detailsCodes = new ArrayList<>();
            for (TrackedJson.Node coding : details.children("coding")) {
                detailsCodes.add(FhirViews.coding(coding));
            }
            List<Shown> expression = new ArrayList<>();
            for (TrackedJson.Node element : issue.children("expression")) {
                expression.add(element.shown());
            }
            List<Shown> location = new ArrayList<>();
            for (TrackedJson.Node element : issue.children("location")) {
                location.add(element.shown());
            }
            issues.add(new IssueView(issue.shown("severity"), issue.shown("code"), issue.shown("diagnostics"),
                    details.exists() ? details.shown("text") : Shown.absent(), detailsCodes, expression, location,
                    issue.child("expression").exists(), issue.child("location").exists()));
        }
        return issues;
    }
}
