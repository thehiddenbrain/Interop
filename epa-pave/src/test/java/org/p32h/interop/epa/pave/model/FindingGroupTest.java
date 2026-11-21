package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Every finding kind is shown under a heading the server gives, with the finding and in the vocabulary. */
class FindingGroupTest {

    @Test
    void eachKindIsPlacedUnderTheHeadingTheScreensUsed() {
        // The grouping the page kept for itself (FINDING_GROUPS in static/js/marks.js), now the server's.
        Map<FindingGroup, List<FindingKind>> expected = new LinkedHashMap<>();
        expected.put(FindingGroup.REQUEST, List.of(FindingKind.REQUEST_INCOMPLETE, FindingKind.NO_CALLBACK_URL,
                FindingKind.PRE_REQUEST_SCRIPT_NOT_RUN));
        expected.put(FindingGroup.GUIDE, List.of(FindingKind.REQUIRED_ELEMENT_MISSING, FindingKind.CODE_OUTSIDE_VALUE_SET,
                FindingKind.GUIDE_RULE_BROKEN));
        expected.put(FindingGroup.INFORMATION, List.of(FindingKind.OTHER_VERSION_CONTENT, FindingKind.NOT_STATED));
        expected.put(FindingGroup.CODE_LIST, List.of(FindingKind.CODE_LIST_DISAGREES_ON_AUTH, FindingKind.CODE_LIST_DISAGREES_ON_VENDOR,
                FindingKind.CODE_LIST_NOT_COMPARED));
        expected.put(FindingGroup.NOTIFICATION, List.of(FindingKind.UNMATCHED_NOTIFICATION, FindingKind.UNCHECKED_NOTIFICATION));
        expected.put(FindingGroup.TIMING, List.of(FindingKind.SLOW_RESPONSE));
        expected.forEach((group, kinds) -> kinds.forEach(kind -> assertThat(FindingGroup.of(kind)).as(kind.name()).isEqualTo(group)));
        assertThat(expected.values().stream().mapToInt(List::size).sum()).isEqualTo(FindingKind.values().length);

        assertThat(FindingGroup.REQUEST.heading()).isEqualTo("About the request this tool sent, not the service");
        assertThat(FindingGroup.GUIDE.heading()).isEqualTo("About the response, held to the guide");
        assertThat(FindingGroup.INFORMATION.heading()).isEqualTo("Information about the response");
        assertThat(FindingGroup.CODE_LIST.heading()).isEqualTo("The code list beside the service");
        assertThat(FindingGroup.NOTIFICATION.heading()).isEqualTo("About a notification that arrived");
        assertThat(FindingGroup.TIMING.heading()).isEqualTo("About the time the call took");
    }

    @Test
    void aFindingAlwaysCarriesItsGroupAndHeadingFromItsKind() {
        Finding finding = new Finding(FindingKind.SLOW_RESPONSE, "The response was slow", "It took 20000 ms.", null, null, null);
        assertThat(finding.group()).isEqualTo(FindingGroup.TIMING);
        assertThat(finding.groupLabel()).isEqualTo("About the time the call took");
        JsonNode json = JsonSafe.mapper().valueToTree(finding);
        assertThat(json.get("group").asString()).isEqualTo("TIMING");
        assertThat(json.get("groupLabel").asString()).isEqualTo("About the time the call took");
        // A heading passed in that the kind does not have is not kept.
        assertThat(new Finding(FindingKind.NOT_STATED, "t", null, null, null, null, FindingGroup.GUIDE, "Something else").group())
                .isEqualTo(FindingGroup.INFORMATION);
        assertThat(finding).isEqualTo(new Finding(FindingKind.SLOW_RESPONSE, "The response was slow", "It took 20000 ms.", null, null, null));
    }

    @Test
    void theVocabularyServesEveryKindWithItsGroupOnBothProfiles() {
        JsonNode list = JsonSafe.mapper().valueToTree(new ScenarioList(false, "live", null, null, null, null));
        JsonNode kinds = list.get("findingKinds");
        assertThat(kinds.size()).isEqualTo(FindingKind.values().length);
        assertThat(Arrays.stream(FindingKind.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(JsonSafe.elements(list, "findingKinds").stream().map(kind -> kind.get("code").asString()).toList());
        JsonNode first = kinds.get(0);
        assertThat(first.get("code").asString()).isEqualTo("CODE_OUTSIDE_VALUE_SET");
        assertThat(first.get("group").asString()).isEqualTo("GUIDE");
        assertThat(first.get("groupLabel").asString()).isEqualTo("About the response, held to the guide");
        assertThat(first.get("groupMeaning").asString()).startsWith("The response does not meet the version of the guide");
    }
}
