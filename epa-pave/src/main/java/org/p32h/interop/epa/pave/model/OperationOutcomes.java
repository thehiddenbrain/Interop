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
            // An empty list is shown by the issue as "returned empty" through its presence, so
            // it is consumed: what the designed view shows does not appear again among the fields
            // returned but not interpreted. Anything else that is not a list stays there.
            consumeIfEmptyList(issue.child("expression"));
            consumeIfEmptyList(issue.child("location"));
            // Details sent as {} are shown as "empty" by the issue, as an empty list is above.
            if (details.isObject() && details.raw().isEmpty()) {
                details.consume();
            }
            issues.add(new IssueView(issue.shown("severity"), issue.shown("code"), issue.shown("diagnostics"),
                    details.exists() ? details.shown("text") : Shown.absent(), detailsCodes, expression, location,
                    issue.child("expression").exists(), issue.child("location").exists(),
                    issue.child("expression").presence(), issue.child("location").presence(), details.presence()));
        }
        return issues;
    }

    private static void consumeIfEmptyList(TrackedJson.Node node) {
        if (node.isArray() && node.elements().isEmpty()) {
            node.consume();
        }
    }
}
