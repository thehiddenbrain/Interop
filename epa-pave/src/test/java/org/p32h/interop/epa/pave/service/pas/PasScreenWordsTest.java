package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.model.pas.DecisionSource;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.PasResponseKind;

/**
 * The sentences a PAS screen shows that it used to word itself (PAS review R12 and the builder's
 * note on values given for one run): where a decision was stated, how the attachments travel, and
 * where a value of a subscription came from.
 */
class PasScreenWordsTest {

    private static final String RECEIVER = "https://pave.example.invalid/api/pas/notifications";

    @Test
    void everyDecisionSaysInWordsWhereItWasStated() {
        for (DecisionSource source : DecisionSource.values()) {
            PasDecision decision = new PasDecision("95810", "95810", 1, "item[0]", List.of(), source, PasOutcome.PENDED, List.of(), List.of());
            assertThat(decision.sourceLabel()).as(source.name()).isEqualTo(source.label());
            assertThat(decision.named("other").sourceLabel()).isEqualTo(source.label());
        }
        assertThat(DecisionSource.RESPONSE.label()).isEqualTo("Stated once for the response as a whole, not on this item");
        assertThat(JsonSafe.mapper().valueToTree(new PasDecision(null, null, null, null, List.of(), DecisionSource.NONE, PasOutcome.NO_DECISION,
                List.of(), List.of())).get("sourceLabel").asString()).isEqualTo("No review action applies to this item");
    }

    private static PasDetail detail(String attachmentMode, boolean builtByTool) {
        return new PasDetail(PasResponseKind.BUNDLE, null, null, null, null, null, null, null, "claim-test-0001", null, null, true)
                .withRequest("claim-test-0001", attachmentMode, List.of(), builtByTool);
    }

    @Test
    void theDetailSaysHowTheAttachmentsTravelOrThatThereAreNone() {
        assertThat(detail(null, true).attachmentNote()).isEqualTo("The request carries no attachment, because none was given.");
        assertThat(detail("fhir", true).attachmentNote()).isEqualTo("The request carries each attachment inside the bundle, as a "
                + "DocumentReference named in the claim's supporting information.");
        assertThat(detail("x12-275", true).attachmentNote()).startsWith("The request names each attachment by an attachment control number");
        assertThat(detail(null, false).attachmentNote()).isEqualTo("This tool did not build the body that was sent, so it does not say "
                + "whether or how it carries attachments.");
    }

    @Test
    void aSubscriptionValueGivenForOneRunOrByTheEnvironmentIsNotedWithWhereItCameFromAndASecretIsNeverQuoted() {
        Function<String, ResolvedText> variables = template -> switch (template) {
            case "{{subscriptionHeartbeatSeconds}}" -> new ResolvedText(template, "120", "120",
                    List.of(new VariableUse("subscriptionHeartbeatSeconds", VariableSource.RUN_VALUE, false, "120")), List.of());
            case "{{subscriptionReason}}" -> new ResolvedText(template, "Placeholder reason", "Placeholder reason",
                    List.of(new VariableUse("subscriptionReason", VariableSource.ENVIRONMENT, false, "Placeholder reason")), List.of());
            case "{{subscriptionTopic}}" -> new ResolvedText(template, Secrets.mask("subscriptionTopic"), "placeholder-secret-topic-7",
                    List.of(new VariableUse("subscriptionTopic", VariableSource.ENVIRONMENT, true, Secrets.mask("subscriptionTopic"))),
                    List.of());
            default -> template.contains("{{") ? new ResolvedText(template, null, null, List.of(), List.of("unset")) : ResolvedText.plain(template);
        };
        SubscriptionRequestBuilder.Built built = new SubscriptionRequestBuilder(RECEIVER).build(null, variables);
        assertThat(built.notes()).contains("The heartbeat period in seconds was set to 120, from the variable subscriptionHeartbeatSeconds, "
                        + "typed for this run.",
                "The reason was set to Placeholder reason, from the variable subscriptionReason, in the active environment.",
                "The subscription topic was set to the value of the secret variable subscriptionTopic, from the variable subscriptionTopic, "
                        + "in the active environment.");
        assertThat(String.join(" ", built.notes())).doesNotContain("placeholder-secret-topic-7");
        // A default is still noted as the tool's own.
        assertThat(built.notes()).anyMatch(note -> note.startsWith("The status was set to requested") && note.contains("this tool's default"));
    }
}
