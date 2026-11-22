package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.raw.RawOutcome;
import tools.jackson.databind.JsonNode;

/**
 * The short words of every outcome (plan 7.2): a chip, a step of the journey and a table cell show
 * them, so each must exist, fit, stay ASCII and tell its outcome apart from the others of its enum.
 */
class OutcomeShortLabelsTest {

    private static final List<OutcomeCode[]> ENUMS = List.of(CrdOutcome.values(), DtrOutcome.values(), PasOutcome.values(),
            SubscriptionOutcome.values(), NotificationOutcome.values(), RawOutcome.values(), RunOutcome.values());

    @Test
    void everyOutcomeHasShortWordsThatFitAndAreAsciiWithNoFullStop() {
        for (OutcomeCode[] values : ENUMS) {
            for (OutcomeCode outcome : values) {
                String name = outcome.getClass().getSimpleName() + "." + outcome.name();
                assertThat(outcome.shortLabel()).as(name).isNotBlank();
                assertThat(outcome.shortLabel().length()).as(name).isLessThanOrEqualTo(24);
                assertThat(outcome.shortLabel().chars().allMatch(c -> c >= 0x20 && c < 0x7f)).as(name).isTrue();
                assertThat(outcome.shortLabel()).as(name).doesNotContain(".");
                assertThat(outcome.shortLabel()).as(name).isEqualTo(outcome.shortLabel().strip());
            }
        }
    }

    @Test
    void noTwoOutcomesOfOneEnumShareTheirShortWords() {
        for (OutcomeCode[] values : ENUMS) {
            Set<String> seen = new HashSet<>();
            for (OutcomeCode outcome : values) {
                assertThat(seen.add(outcome.shortLabel())).as(outcome.getClass().getSimpleName() + "." + outcome.name()).isTrue();
            }
        }
    }

    @Test
    void theWordsAreThePlansWordForWord() {
        Map<OutcomeCode, String> settled = Map.ofEntries(
                Map.entry(CrdOutcome.AUTH_REQUIRED, "PA required"),
                Map.entry(CrdOutcome.NO_AUTH_REQUIRED, "PA not required"),
                Map.entry(CrdOutcome.PERFORMER_AUTH, "Performer must get PA"),
                Map.entry(CrdOutcome.MIXED, "Mixed answers"),
                Map.entry(CrdOutcome.MALFORMED_RESPONSE, "Unreadable answer"),
                Map.entry(DtrOutcome.NOTHING_REQUIRED, "Nothing asked"),
                Map.entry(DtrOutcome.TIMEOUT, "Timed out"),
                Map.entry(PasOutcome.PARTIALLY_APPROVED, "Partly approved"),
                Map.entry(PasOutcome.MIXED, "Mixed decisions"),
                Map.entry(PasOutcome.ATTACHMENT_REQUIRED, "Documents required"),
                Map.entry(PasOutcome.AUTHORIZATION_NOT_FOUND, "Request not found"),
                Map.entry(SubscriptionOutcome.UNRECOGNISED_RESPONSE, "Not a subscription"),
                Map.entry(SubscriptionOutcome.MALFORMED_RESPONSE, "Unreadable answer"),
                Map.entry(NotificationOutcome.STATUS_RECEIVED, "Status report"),
                Map.entry(RawOutcome.MALFORMED_RESPONSE, "Cut off"),
                Map.entry(RunOutcome.BLOCKED, "Blocked"),
                Map.entry(RunOutcome.NOT_SIGNED_IN, "Not signed in"));
        settled.forEach((outcome, words) -> assertThat(outcome.shortLabel()).as(outcome.name()).isEqualTo(words));
    }

    @Test
    void anOptionServesTheShortWordsAsShortAndAnOptionBuiltWithoutThemLeavesTheFieldOut() {
        JsonNode option = JsonSafe.mapper().valueToTree(OutcomeOption.of(CrdOutcome.NO_AUTH_REQUIRED));
        assertThat(option.get("short").asString()).isEqualTo("PA not required");
        assertThat(option.get("label").asString()).isEqualTo("Prior authorization is not required");
        assertThat(option.has("shortLabel")).isFalse();
        JsonNode older = JsonSafe.mapper().valueToTree(new OutcomeOption("X", "Words", Tone.NEUTRAL, false));
        assertThat(older.has("short")).isFalse();
    }
}
