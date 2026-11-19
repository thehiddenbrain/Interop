package org.p32h.interop.epa.pave.client.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions.Kind;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions.Progress;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import tools.jackson.databind.JsonNode;

/**
 * The assumptions this tool makes about how the vendor behaves in DTR, each pinned: how a
 * questionnaire is told to be adaptive or static, what ends the adaptive loop, and how an unknown
 * member or provider is recognised. When the real behaviour is known, these are the tests that change.
 */
class OnyxDtrConventionsTest {

    private static final String ADAPTIVE = "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";
    private static final String ITEM = "\"item\":[{\"linkId\":\"q1\",\"type\":\"string\"}]";

    private static JsonNode json(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        assertThat(parsed.ok()).as(text).isTrue();
        return parsed.node();
    }

    private static Kind kind(String properties) {
        return OnyxDtrConventions.kind(json("{\"resourceType\":\"Questionnaire\"" + (properties.isEmpty() ? "" : "," + properties) + "}"));
    }

    private static String extension(String value) {
        return "\"extension\":[{\"url\":\"" + ADAPTIVE + "\"," + value + "}]";
    }

    private static String profile(String canonical) {
        return "\"meta\":{\"profile\":[\"" + canonical + "\"]}";
    }

    // ---- which kind of questionnaire it is ----

    @Test
    void aQuestionnaireIsAdaptiveWhenTheExtensionOrAProfileSaysSo() {
        assertThat(kind(extension("\"valueBoolean\":true")).kind()).isEqualTo(QuestionnaireKind.ADAPTIVE);
        assertThat(kind(extension("\"valueBoolean\":true")).basis()).isEqualTo("Adaptive, because it carries the questionnaireAdaptive "
                + "extension set to true.");
        assertThat(kind(extension("\"valueUrl\":\"https://example.org/p32h-placeholder/fhir\"")).basis())
                .isEqualTo("Adaptive, because it carries the questionnaireAdaptive extension set to a url.");
        assertThat(kind(extension("\"valueUri\":\"https://example.org/p32h-placeholder/fhir\"")).kind()).isEqualTo(QuestionnaireKind.ADAPTIVE);
        Kind byProfile = kind(profile("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt"));
        assertThat(byProfile.kind()).isEqualTo(QuestionnaireKind.ADAPTIVE);
        assertThat(byProfile.basis()).isEqualTo("Adaptive, because it declares the adaptive questionnaire profile "
                + "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt.");
        // Holding items does not make an adaptive questionnaire static: the marker decides.
        assertThat(kind(extension("\"valueBoolean\":true") + "," + ITEM).kind()).isEqualTo(QuestionnaireKind.ADAPTIVE);
        // Any spelling of the extension url is recognised by how it ends.
        assertThat(kind("\"extension\":[{\"url\":\"http://example.org/StructureDefinition/questionnaire-adaptive\",\"valueBoolean\":true}]").kind())
                .isEqualTo(QuestionnaireKind.ADAPTIVE);
    }

    @Test
    void aQuestionnaireIsStaticWhenAMarkerSaysSoOrItCarriesNoMarkerAndHoldsItems() {
        assertThat(kind(profile("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-std-questionnaire")).kind())
                .isEqualTo(QuestionnaireKind.STATIC);
        assertThat(kind(extension("\"valueBoolean\":false")).basis()).isEqualTo("Static, because its questionnaireAdaptive extension is set to "
                + "false.");
        Kind unmarked = kind(ITEM);
        assertThat(unmarked.kind()).isEqualTo(QuestionnaireKind.STATIC);
        assertThat(unmarked.basis()).isEqualTo("Static, because it carries no adaptive marker and holds 1 item of its own at the top level.");
        // A profile this tool does not know is not a marker, and another extension is not the adaptive one.
        assertThat(kind(profile("https://example.org/p32h-placeholder/StructureDefinition/payer-questionnaire") + "," + ITEM).kind())
                .isEqualTo(QuestionnaireKind.STATIC);
        assertThat(kind("\"extension\":[{\"url\":\"http://hl7.org/fhir/StructureDefinition/cqf-library\",\"valueCanonical\":\"x\"}]," + ITEM).kind())
                .isEqualTo(QuestionnaireKind.STATIC);
    }

    @Test
    void whenNothingSettlesTheKindNoneIsPicked() {
        Kind empty = kind("");
        assertThat(empty.kind()).isEqualTo(QuestionnaireKind.UNDETERMINED);
        assertThat(empty.basis()).isEqualTo("Undetermined, because it carries no adaptive marker and no items, so nothing says how it is "
                + "meant to be answered.");
        // Markers that contradict each other.
        Kind contradictory = kind(extension("\"valueBoolean\":true") + ","
                + profile("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-std-questionnaire"));
        assertThat(contradictory.kind()).isEqualTo(QuestionnaireKind.UNDETERMINED);
        assertThat(contradictory.basis()).startsWith("Undetermined, because its markers contradict each other:");
        // A marker whose value cannot be read is not taken for true and not for false, even when items are there.
        Kind unreadable = kind(extension("\"valueString\":\"yes\"") + "," + ITEM);
        assertThat(unreadable.kind()).isEqualTo(QuestionnaireKind.UNDETERMINED);
        assertThat(unreadable.basis()).contains("neither true, false nor a url");
        assertThat(kind(extension("\"valueBoolean\":\"true\"")).kind()).isEqualTo(QuestionnaireKind.UNDETERMINED);
        // The rule is stated in words for the result to show, and names all three answers.
        assertThat(OnyxDtrConventions.KIND_RULE).contains("adaptive when").contains("static when").contains("undetermined");
    }

    // ---- what ends the loop ----

    @Test
    void onlyTheTwoStatusesThisToolActsOnAreUnderstoodAndTheyAreMatchedExactly() {
        assertThat(OnyxDtrConventions.progress("completed")).isEqualTo(Progress.COMPLETED);
        assertThat(OnyxDtrConventions.progress("in-progress")).isEqualTo(Progress.IN_PROGRESS);
        for (String other : new String[] {null, "", "Completed", "COMPLETED", "complete", "amended", "stopped", "entered-in-error", " completed"}) {
            assertThat(OnyxDtrConventions.progress(other)).as("[" + other + "]").isEqualTo(Progress.NOT_UNDERSTOOD);
        }
        assertThat(OnyxDtrConventions.STATUS_COMPLETED).isEqualTo("completed");
        assertThat(OnyxDtrConventions.STATUS_IN_PROGRESS).isEqualTo("in-progress");
    }

    @Test
    void aResponseThatCarriesItsQuestionnaireInsideItselfIsOneFromTheLoop() {
        JsonNode fromTheLoop = json("{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\"}],"
                + "\"questionnaire\":\"#q\"}");
        assertThat(OnyxDtrConventions.fromTheLoop(fromTheLoop)).isTrue();
        assertThat(OnyxDtrConventions.fromTheLoop(json("{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://example.org/q\"}")))
                .isFalse();
        // It points at a questionnaire inside itself that is not there.
        assertThat(OnyxDtrConventions.fromTheLoop(json("{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":"
                + "\"Questionnaire\",\"id\":\"other\"}],\"questionnaire\":\"#q\"}"))).isFalse();
        assertThat(OnyxDtrConventions.fromTheLoop(null)).isFalse();
        // The body that is sent is the response itself, which is the assumption the conventions state.
        assertThat(OnyxDtrConventions.nextQuestionBody(fromTheLoop)).isSameAs(fromTheLoop);
        assertThat(OnyxDtrConventions.FHIR_JSON).isEqualTo("application/fhir+json");
    }

    // ---- not found ----

    private static VendorExchange outcome(int status, String issue) {
        return TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, status, "{\"resourceType\":\"OperationOutcome\",\"issue\":[" + issue + "]}",
                true);
    }

    @Test
    void notFoundIsRecognisedOnlyFromACodedIssueThatSaysWhichThingWasNotFound() {
        assertThat(OnyxDtrConventions.signals(outcome(404, "{\"code\":\"not-found\",\"expression\":[\"Coverage.beneficiary\"]}")))
                .containsExactly(VendorSignal.MEMBER_NOT_FOUND);
        assertThat(OnyxDtrConventions.signals(outcome(404, "{\"code\":\"not-found\",\"location\":[\"Parameters.parameter[0].resource.subscriberId\"]}")))
                .containsExactly(VendorSignal.MEMBER_NOT_FOUND);
        assertThat(OnyxDtrConventions.signals(outcome(404, "{\"code\":\"not-found\",\"details\":{\"coding\":[{\"code\":\"practitioner-unknown\"}]}}")))
                .containsExactly(VendorSignal.PROVIDER_NOT_FOUND);
        // The status alone settles nothing, and the wording of an issue is never read.
        assertThat(OnyxDtrConventions.signals(outcome(404, "{\"code\":\"not-found\"}"))).isEmpty();
        assertThat(OnyxDtrConventions.signals(outcome(404, "{\"code\":\"not-found\",\"diagnostics\":\"The member was not found.\"}"))).isEmpty();
        assertThat(OnyxDtrConventions.signals(outcome(404, "{\"code\":\"invalid\",\"expression\":[\"Coverage.beneficiary\"]}"))).isEmpty();
        // An issue that names both a member element and a provider element is ambiguous, so it is left unclassified.
        assertThat(OnyxDtrConventions.signals(outcome(404, "{\"code\":\"not-found\",\"expression\":[\"Coverage.beneficiary\","
                + "\"PractitionerRole.practitioner\"]}"))).isEmpty();
        // Anything that is not an OperationOutcome carries no signal.
        assertThat(OnyxDtrConventions.signals(TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 404, "<html>Not Found</html>", true))).isEmpty();
        assertThat(OnyxDtrConventions.signals(TestExchanges.timeout(EpaOperation.DTR_NEXT_QUESTION))).isEmpty();
        assertThat(OnyxDtrConventions.signals(null)).isEmpty();
    }
}
