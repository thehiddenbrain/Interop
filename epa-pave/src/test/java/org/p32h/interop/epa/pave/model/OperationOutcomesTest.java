package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An issue's expression and location say whether the service returned them, apart from what they hold. */
class OperationOutcomesTest {

    private static List<IssueView> issues(String outcome) {
        return OperationOutcomes.issues(TrackedJson.of(JsonSafe.parse(outcome).node()).root());
    }

    @Test
    void anElementNotReturnedAndAnElementReturnedEmptyAreToldApart() {
        List<IssueView> read = issues("{\"resourceType\": \"OperationOutcome\", \"issue\": ["
                + "{\"severity\": \"error\", \"code\": \"processing\"},"
                + "{\"severity\": \"error\", \"code\": \"processing\", \"expression\": [], \"location\": []},"
                + "{\"severity\": \"error\", \"code\": \"invalid\", \"expression\": [\"Claim.item[0]\"], \"location\": null}]}");
        assertThat(read).hasSize(3);

        // Not returned: both lists are empty and neither element was there.
        assertThat(read.get(0).expression()).isEmpty();
        assertThat(read.get(0).expressionPresent()).isFalse();
        assertThat(read.get(0).locationPresent()).isFalse();
        // Returned empty: the same empty lists, and both elements were there.
        assertThat(read.get(1).expression()).isEmpty();
        assertThat(read.get(1).expressionPresent()).isTrue();
        assertThat(read.get(1).locationPresent()).isTrue();
        // Returned with a value, and returned as null, which is held all the same.
        assertThat(read.get(2).expression()).extracting(Shown::value).containsExactly("Claim.item[0]");
        assertThat(read.get(2).expressionPresent()).isTrue();
        assertThat(read.get(2).locationPresent()).isTrue();
    }

    @Test
    void anEmptyListTheIssueShowsIsNotShownAgainAmongTheFieldsReturnedButNotInterpreted() {
        TrackedJson tracked = TrackedJson.of(JsonSafe.parse("{\"resourceType\": \"OperationOutcome\", \"issue\": ["
                + "{\"severity\": \"error\", \"code\": \"processing\", \"expression\": [], \"location\": []},"
                + "{\"severity\": \"error\", \"code\": \"invalid\", \"expression\": \"Claim.item\", \"location\": null}]}").node());
        tracked.root().text("resourceType");
        List<IssueView> read = OperationOutcomes.issues(tracked.root());
        assertThat(read.get(0).expressionPresent()).isTrue();
        assertThat(read.get(0).locationPresent()).isTrue();
        // The issues table says "returned empty" for both, so neither is listed again. An expression that is not a list and a
        // location sent as null are not what the issue view shows, so they stay where a tester can see them.
        assertThat(tracked.unconsumed()).extracting(AdditionalField::path)
                .containsExactlyInAnyOrder("issue[1].expression", "issue[1].location");
    }

    @Test
    void theFlagsAreAlwaysOnTheWire() {
        IssueView absent = issues("{\"issue\": [{\"severity\": \"error\"}]}").get(0);
        tools.jackson.databind.JsonNode json = JsonSafe.mapper().valueToTree(absent);
        assertThat(json.get("expressionPresent").asBoolean()).isFalse();
        assertThat(json.get("locationPresent").asBoolean()).isFalse();
        assertThat(json.get("expression").isArray()).isTrue();
    }
}
