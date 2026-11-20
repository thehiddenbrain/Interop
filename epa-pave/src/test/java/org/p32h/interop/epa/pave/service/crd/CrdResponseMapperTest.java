package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.crd.ActionView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.CrdResponseKind;
import org.p32h.interop.epa.pave.model.crd.SubExtensionView;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrdResponseMapperTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
    private final CrdResponseMapper mapper = new CrdResponseMapper();

    /** A CDS Hooks response with one system action carrying one coverage information extension built from the given sub-extensions. */
    private static String systemAction(String subExtensions) {
        return "{\"cards\":[],\"systemActions\":[{\"type\":\"update\",\"description\":\"d\",\"resource\":{\"resourceType\":\"ServiceRequest\","
                + "\"id\":\"service-request-1\",\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[" + subExtensions + "]}]}}]}";
    }

    private static String code(String url, String value) {
        return "{\"url\":\"" + url + "\",\"valueCode\":\"" + value + "\"}";
    }

    private CrdReading read(String body) {
        return mapper.read(TestExchanges.crd(200, body));
    }

    private CoverageInformationView only(CrdReading reading) {
        assertThat(reading.detail().coverageInformation()).hasSize(1);
        return reading.detail().coverageInformation().get(0);
    }

    private static List<String> paths(CrdReading reading) {
        return reading.additionalFields().stream().map(AdditionalField::path).toList();
    }

    /** The codes of the one answer in a reading, or none when it holds no answer. */
    private static Map<String, List<String>> codes(CrdReading reading) {
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        assertThat(coverage.size()).isLessThanOrEqualTo(1);
        return coverage.isEmpty() ? Map.of() : CrdReading.codes(coverage.get(0));
    }

    /** One system action per entry: the id of the order it answers and the pa-needed code it gives. */
    private static String answers(String... idThenCode) {
        StringBuilder actions = new StringBuilder();
        for (int i = 0; i < idThenCode.length; i += 2) {
            actions.append(i == 0 ? "" : ",").append("{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"")
                    .append(idThenCode[i]).append("\",\"extension\":[{\"url\":\"").append(EXT).append("\",\"extension\":[")
                    .append(code("pa-needed", idThenCode[i + 1])).append("]}]}}");
        }
        return "{\"systemActions\":[" + actions + "]}";
    }

    private static final List<SentOrder> TWO_ORDERS = List.of(new SentOrder("ServiceRequest/service-request-1", "95810"),
            new SentOrder("ServiceRequest/service-request-2", "95811"));

    // ---- the distinction this tool exists for ----

    @Test
    void anAbsentNullOrEmptyAuthorizationElementIsNeverReadAsNoAuthorization() {
        CrdReading absent = read(systemAction(code("covered", "covered")));
        CrdReading explicitNull = read(systemAction(code("covered", "covered") + ",{\"url\":\"pa-needed\",\"valueCode\":null}"));
        CrdReading empty = read(systemAction(code("covered", "covered") + ",{\"url\":\"pa-needed\",\"valueCode\":\"\"}"));
        CrdReading noValue = read(systemAction(code("covered", "covered") + ",{\"url\":\"pa-needed\"}"));

        for (CrdReading reading : List.of(absent, explicitNull, empty, noValue)) {
            assertThat(reading.outcome()).isEqualTo(CrdOutcome.AUTH_NOT_STATED);
            assertThat(reading.outcome()).isNotEqualTo(CrdOutcome.NO_AUTH_REQUIRED);
            assertThat(codes(reading)).doesNotContainKey("pa-needed");
        }
        // And the screen can still tell the four apart.
        assertThat(only(absent).paNeeded().presence()).isEqualTo(Presence.ABSENT);
        assertThat(only(explicitNull).paNeeded().presence()).isEqualTo(Presence.NULL);
        assertThat(only(empty).paNeeded().presence()).isEqualTo(Presence.EMPTY);
        assertThat(only(noValue).paNeeded().presence()).isEqualTo(Presence.EMPTY);
    }

    @Test
    void aCoverageInformationWithNeitherCoveredNorPaNeededSaysNothing() {
        CrdReading reading = read(systemAction("{\"url\":\"coverage\",\"valueReference\":{\"reference\":\"Coverage/c1\"}}"));
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(only(reading).covered()).isEqualTo(CodedValue.notReturned());
        assertThat(only(reading).coverage().value()).isEqualTo("Coverage/c1");
    }

    @Test
    void anExtensionWithNoSubExtensionsAtAllSaysNothing() {
        CrdReading reading = read(systemAction(""));
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(codes(reading)).isEmpty();
    }

    @Test
    void aResponseWithNoCoverageInformationIsNoDeterminationWhateverItsCardsSay() {
        CrdReading reading = read("{\"cards\":[{\"summary\":\"No prior authorization is required\",\"indicator\":\"info\",\"source\":{\"label\":\"P\"}}]}");
        // The card wording says "no prior authorization". The tool does not read wording.
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(reading.detail().cards()).hasSize(1);
        assertThat(reading.detail().cards().get(0).summary().value()).isEqualTo("No prior authorization is required");
    }

    @Test
    void anEmptyObjectAndAnEmptyCardsArrayAreBothNoDeterminationAndShowWhatWasAndWasNotSent() {
        CrdReading emptyObject = read("{}");
        assertThat(emptyObject.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(emptyObject.detail().cardsPresence()).isEqualTo(Presence.ABSENT);
        CrdReading emptyCards = read("{\"cards\":[]}");
        assertThat(emptyCards.detail().cardsPresence()).isEqualTo(Presence.EMPTY);
        CrdReading nullCards = read("{\"cards\":null}");
        assertThat(nullCards.detail().cardsPresence()).isEqualTo(Presence.NULL);
        assertThat(nullCards.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        // The view states for itself that cards was null or empty, so neither is listed a second time.
        assertThat(paths(nullCards)).isEmpty();
        assertThat(paths(emptyCards)).isEmpty();
        // Something else in that place is not what the view states, so it stays listed.
        CrdReading textCards = read("{\"cards\":\"none\",\"systemActions\":{}}");
        assertThat(textCards.detail().cardsPresence()).isEqualTo(Presence.VALUE);
        assertThat(textCards.additionalFields()).containsExactly(new AdditionalField("cards", Presence.VALUE, "\"none\""),
                new AdditionalField("systemActions", Presence.EMPTY, "{}"));
    }

    // ---- which code an answer is for ----

    @Test
    void anAnswerIsTiedToTheCodeOfTheOrderItSitsOn() {
        CrdReading reading = mapper.read(TestExchanges.crd(200, answers("service-request-1", "auth-needed", "service-request-2", "no-auth")),
                TWO_ORDERS);
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.MIXED);
        assertThat(reading.requestedCodes()).containsExactly("95810", "95811");
        assertThat(reading.unanswered()).isEmpty();
        assertThat(reading.detail().coverageInformation()).extracting(CoverageInformationView::subject).containsExactly("95810", "95811");
        assertThat(reading.detail().coverageInformation().get(1).procedureCodes()).containsExactly("95811");
        // Each answer keeps its own codes, named by its code, in the ledger and for an expectation.
        assertThat(reading.ledgerCodes()).containsExactly("95810: pa-needed=auth-needed", "95811: pa-needed=no-auth");
        assertThat(reading.answers()).containsExactly(Expectations.Answer.of("95810", Map.of("pa-needed", List.of("auth-needed"))),
                Expectations.Answer.of("95811", Map.of("pa-needed", List.of("no-auth"))));

        // The two codes swapping answers is a different ledger line, so a replay can say what changed.
        CrdReading swapped = mapper.read(TestExchanges.crd(200, answers("service-request-1", "no-auth", "service-request-2", "auth-needed")),
                TWO_ORDERS);
        assertThat(swapped.outcome()).isEqualTo(CrdOutcome.MIXED);
        assertThat(swapped.ledgerCodes()).containsExactly("95810: pa-needed=no-auth", "95811: pa-needed=auth-needed");
    }

    @Test
    void aCodeThatReceivedNoAnswerIsNeverGivenTheAnswerOfAnother() {
        // Two codes were asked about and the service answered only the first, with no-auth. Reporting
        // "not required" for the order would turn the silence about the second code into an answer.
        CrdReading reading = mapper.read(TestExchanges.crd(200, answers("service-request-1", "no-auth")), TWO_ORDERS);
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.MIXED);
        assertThat(reading.outcome()).isNotEqualTo(CrdOutcome.NO_AUTH_REQUIRED);
        assertThat(reading.unanswered()).containsExactly("95811");
        assertThat(reading.several()).isTrue();
        assertThat(reading.ledgerCodes()).containsExactly("95810: pa-needed=no-auth", "95811: (no answer)");
        // The answer list holds what was answered. What was asked is given beside it, and the
        // comparison works out for itself that 95811 went unanswered.
        assertThat(reading.answers()).containsExactly(Expectations.Answer.of("95810", Map.of("pa-needed", List.of("no-auth"))));
        assertThat(reading.asked()).containsExactly("95810", "95811");
        assertThat(Expectations.assess("pa-needed=no-auth", reading.outcome(), reading.asked(), reading.answers(), CrdOutcome.values(),
                CrdCodes.EXPECTATION_ELEMENTS).explanation()).isEqualTo("Expected pa-needed=no-auth but no answer was returned for 95811.");

        // An answer that says nothing for one code, and no answer for the other, are alike: nothing was determined.
        CrdReading nothing = mapper.read(TestExchanges.crd(200,
                "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\","
                        + "\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[{\"url\":\"date\",\"valueDate\":\"2026-01-15\"}]}]}}]}"), TWO_ORDERS);
        assertThat(nothing.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(nothing.unanswered()).containsExactly("95811");
    }

    @Test
    void aServiceThatUsesItsOwnIdsIsStillTiedThroughTheCodeItEchoes() {
        String body = "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"vendor-77\","
                + "\"code\":{\"coding\":[{\"system\":\"s\",\"code\":\"95811\"}]},\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":["
                + code("pa-needed", "auth-needed") + "]}]}}]}";
        CrdReading reading = mapper.read(TestExchanges.crd(200, body), TWO_ORDERS);
        CoverageInformationView view = only(reading);
        assertThat(view.procedureCodes()).containsExactly("95811");
        assertThat(view.subject()).isEqualTo("95811");
        assertThat(view.appliesTo().value()).isEqualTo("ServiceRequest/vendor-77");
        assertThat(reading.unanswered()).containsExactly("95810");
        // The echoed code is not read into a slot, so it is shown with the rest of the resource.
        assertThat(reading.detail().systemActions().get(0).resource()).extracting(AdditionalField::path)
                .contains("systemActions[0].resource.code.coding[0].code");
    }

    @Test
    void anAnswerForAnOrderThatWasNotSentIsShownAndTiedToNoRequestedCode() {
        // The answer names another order and echoes no code. It is not assumed to be about the one code asked.
        List<SentOrder> one = List.of(new SentOrder("ServiceRequest/service-request-1", "95810"));
        CrdReading reading = mapper.read(TestExchanges.crd(200, answers("some-other-order", "no-auth")), one);
        CoverageInformationView view = only(reading);
        assertThat(view.procedureCodes()).isEmpty();
        assertThat(view.subject()).isEqualTo("ServiceRequest/some-other-order");
        assertThat(reading.unanswered()).containsExactly("95810");
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.MIXED);

        // The same for a resource that echoes a code nobody asked about.
        String otherCode = "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\","
                + "\"code\":{\"coding\":[{\"code\":\"99999\"}]},\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":["
                + code("pa-needed", "no-auth") + "]}]}}]}";
        assertThat(mapper.read(TestExchanges.crd(200, otherCode), one).unanswered()).containsExactly("95810");
    }

    @Test
    void anAnswerOnNoResourceIsForTheOnlyCodeAskedAndForNoneWhenSeveralWereAsked() {
        String onCard = "{\"cards\":[{\"summary\":\"s\",\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[" + code("pa-needed", "no-auth")
                + "]}]}]}";
        CrdReading single = mapper.read(TestExchanges.crd(200, onCard), List.of(new SentOrder("ServiceRequest/service-request-1", "95810")));
        assertThat(single.outcome()).isEqualTo(CrdOutcome.NO_AUTH_REQUIRED);
        assertThat(only(single).procedureCodes()).containsExactly("95810");
        assertThat(single.several()).isFalse();
        assertThat(single.ledgerCodes()).containsExactly("pa-needed=no-auth");

        CrdReading several = mapper.read(TestExchanges.crd(200, onCard), TWO_ORDERS);
        assertThat(several.outcome()).isEqualTo(CrdOutcome.MIXED);
        assertThat(only(several).procedureCodes()).isEmpty();
        assertThat(only(several).subject()).isEqualTo("cards[0].extension[0]");
        assertThat(several.unanswered()).containsExactly("95810", "95811");
    }

    @Test
    void whenTheRequestIsNotKnownAnswersAreNamedByWhatTheyApplyToAndNothingIsCalledUnanswered() {
        CrdReading reading = read(answers("service-request-1", "auth-needed", "service-request-2", "auth-needed"));
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.AUTH_REQUIRED);
        assertThat(reading.requestedCodes()).isEmpty();
        assertThat(reading.unanswered()).isEmpty();
        assertThat(reading.detail().coverageInformation()).extracting(CoverageInformationView::subject)
                .containsExactly("ServiceRequest/service-request-1", "ServiceRequest/service-request-2");
    }


    // ---- codes ----

    @Test
    void everyStandardSubExtensionIsReadIntoItsSlot() {
        // A coverage information extension that conforms to CRD 2.0.1 (CRD201-25): info-needed beside
        // a conditional (crd-ci-q3), a questionnaire and a response beside doc-needed (crd-ci-q1), and
        // no satisfied-pa-id since pa-needed is not satisfied (crd-ci-q5).
        String extension = String.join(",",
                "{\"url\":\"coverage\",\"valueReference\":{\"reference\":\"Coverage/c1\",\"display\":\"Plan\"}}",
                code("covered", "conditional"), code("pa-needed", "auth-needed"), code("doc-needed", "both"),
                code("doc-purpose", "withpa"), code("doc-purpose", "retain-doc"), code("info-needed", "timeframe"),
                "{\"url\":\"questionnaire\",\"valueCanonical\":\"https://example.org/Questionnaire/q1\"}",
                "{\"url\":\"response\",\"valueReference\":{\"reference\":\"QuestionnaireResponse/qr-1\"}}",
                "{\"url\":\"date\",\"valueDate\":\"2026-01-15\"}",
                "{\"url\":\"coverage-assertion-id\",\"valueString\":\"a-1\"}",
                "{\"url\":\"reason\",\"valueCodeableConcept\":{\"text\":\"Because\",\"coding\":[{\"system\":\"s\",\"code\":\"gold-card\",\"display\":\"Gold card\"}]}}",
                "{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":{\"coding\":[{\"system\":"
                        + "\"http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp\",\"code\":\"allowed-quantity\"}]}},"
                        + "{\"url\":\"value\",\"valueQuantity\":{\"value\":2}},{\"url\":\"qualification\",\"valueString\":\"Per calendar year\"}]}");
        CrdReading reading = read(systemAction(extension));
        CoverageInformationView view = only(reading);
        assertThat(view.appliesTo().value()).isEqualTo("ServiceRequest/service-request-1");
        assertThat(view.coverage().value()).isEqualTo("Coverage/c1 (Plan)");
        assertThat(view.covered().code()).isEqualTo("conditional");
        assertThat(view.covered().version()).isEqualTo("CRD 2.0.1");
        assertThat(view.paNeeded().display()).isEqualTo("Prior authorization is needed");
        assertThat(view.docNeeded().code()).isEqualTo("both");
        assertThat(view.docPurpose()).extracting(CodedValue::code).containsExactly("withpa", "retain-doc");
        assertThat(view.docPurpose()).extracting(CodedValue::recognised).containsExactly(true, true);
        assertThat(view.infoNeeded()).extracting(CodedValue::code).containsExactly("timeframe");
        assertThat(view.questionnaires()).extracting(Shown::value).containsExactly("https://example.org/Questionnaire/q1");
        // The 2.0.1 response sub-extension has its own slot and is not left among the uninterpreted ones (CRD201-03).
        assertThat(view.responses()).extracting(Shown::value).containsExactly("QuestionnaireResponse/qr-1");
        assertThat(view.satisfiedPaId().presence()).isEqualTo(Presence.ABSENT);
        assertThat(view.date().value()).isEqualTo("2026-01-15");
        assertThat(view.coverageAssertionId().value()).isEqualTo("a-1");
        assertThat(view.reasons()).extracting(Shown::value).containsExactly("Because [Gold card (s|gold-card)]");
        // The third part of a detail is qualification in 2.0.1, and it stays with its detail (CRD201-12).
        assertThat(view.details()).singleElement().satisfies(detail -> {
            assertThat(detail.qualification().value()).isEqualTo("Per calendar year");
            assertThat(detail.category().presence()).isEqualTo(Presence.ABSENT);
        });
        assertThat(view.otherSubExtensions()).isEmpty();
        assertThat(codes(reading)).containsEntry("doc-purpose", List.of("withpa", "retain-doc"));
        // A definite pa-needed is not hidden behind info-needed: what is conditional here is coverage.
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.DOCUMENTATION_REQUIRED);
        // And the extension breaks no rule CRD 2.0.1 states for it.
        VendorExchange exchange = TestExchanges.crd(200, systemAction(extension));
        assertThat(new CrdConformanceChecker(null, EpaProperties.of(java.util.Map.of("mode", "live")))
                .check(reading, exchange, null, null)).isEmpty();
    }

    @Test
    void theBallotsDetailCategoryIsReadIntoItsDetailAndANameNoVersionDefinesSaysSo() {
        CrdReading reading = read(systemAction(code("covered", "covered") + ",{\"url\":\"detail\",\"extension\":[{\"url\":\"category\","
                + "\"valueCode\":\"cat-limitation\"},{\"url\":\"code\",\"valueCodeableConcept\":{\"text\":\"limit\"}},{\"url\":\"value\","
                + "\"valueString\":\"2 a year\"},{\"url\":\"qualifier\",\"valueString\":\"old name\"}]},{\"url\":\"billingCode\",\"valueCoding\""
                + ":{\"code\":\"x\"}},{\"url\":\"made-up\",\"valueString\":\"y\"}"));
        CoverageInformationView view = only(reading);
        assertThat(view.details()).singleElement().satisfies(detail -> assertThat(detail.category().value()).isEqualTo("cat-limitation"));
        assertThat(view.otherSubExtensions()).extracting(SubExtensionView::url, SubExtensionView::definedIn).containsExactly(
                org.assertj.core.groups.Tuple.tuple("detail.qualifier", "defined in no version of CRD this tool can read"),
                org.assertj.core.groups.Tuple.tuple("billingCode", "defined in CRD 2.0.1"),
                org.assertj.core.groups.Tuple.tuple("made-up", "defined in no version of CRD this tool can read"));
    }

    @Test
    void theAuthorizationOutOfNetworkOnlyDetailOfCrd201IsRead() {
        CrdReading reading = read(systemAction(code("covered", "covered") + "," + code("pa-needed", "auth-needed")
                + ",{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":{\"coding\":[{\"system\":"
                + "\"http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp\",\"code\":\"auth-out-network-only\"}]}},{\"url\":\"value\","
                + "\"valueBoolean\":true}]}"));
        assertThat(only(reading).authOutOfNetworkOnly()).isTrue();
        assertThat(CrdVerdicts.verdict(reading, TestExchanges.crd(200, "{}")).supporting())
                .contains("The payer adds that prior authorization is needed only if the service is delivered out of network "
                        + "(detail auth-out-network-only).");
    }

    @Test
    void anUnrecognisedCodeIsShownNotHiddenAndIsNotMappedToAKnownOne() {
        CrdReading reading = read(systemAction(code("covered", "covered") + "," + code("pa-needed", "auth-maybe")));
        CodedValue paNeeded = only(reading).paNeeded();
        assertThat(paNeeded.recognised()).isFalse();
        assertThat(paNeeded.code()).isEqualTo("auth-maybe");
        assertThat(paNeeded.display()).isEqualTo("Unrecognised code: auth-maybe");
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.UNRECOGNISED_CODE);
        assertThat(codes(reading)).containsEntry("pa-needed", List.of("auth-maybe"));
        // A code that differs only by case is not the standard code.
        assertThat(read(systemAction(code("pa-needed", "No-Auth"))).outcome()).isEqualTo(CrdOutcome.UNRECOGNISED_CODE);
    }

    @Test
    void aCodeIsReadFromACodingOrAConceptAsWellAsFromACode() {
        assertThat(read(systemAction("{\"url\":\"pa-needed\",\"valueCoding\":{\"system\":\"s\",\"code\":\"no-auth\"}}")).outcome())
                .isEqualTo(CrdOutcome.NO_AUTH_REQUIRED);
        assertThat(read(systemAction("{\"url\":\"pa-needed\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"satisfied\"}]}}")).outcome())
                .isEqualTo(CrdOutcome.AUTH_SATISFIED);
        assertThat(read(systemAction("{\"url\":\"pa-needed\",\"valueString\":\"performpa\"}")).outcome())
                .isEqualTo(CrdOutcome.PERFORMER_AUTH);
    }

    @Test
    void aSubExtensionTheToolHasNoSlotForIsStillShownInTheDesignedView() {
        CrdReading reading = read(systemAction(String.join(",", code("covered", "covered"), code("pa-needed", "no-auth"),
                "{\"url\":\"billingCode\",\"valueCoding\":{\"system\":\"http://www.ama-assn.org/go/cpt\",\"code\":\"95810\"}}",
                "{\"url\":\"expiry-date\",\"valueDate\":\"2026-12-31\"}",
                "{\"url\":\"vendor-specific-thing\",\"valueString\":\"x\"}",
                "{\"valueString\":\"no url here\"}")));
        assertThat(only(reading).otherSubExtensions()).extracting(s -> s.url())
                .containsExactly("billingCode", "expiry-date", "vendor-specific-thing", "(no url)");
        assertThat(only(reading).otherSubExtensions().get(0).value().value()).isEqualTo("http://www.ama-assn.org/go/cpt|95810");
        assertThat(only(reading).otherSubExtensions().get(3).value().value()).isEqualTo("no url here");
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.NO_AUTH_REQUIRED);
    }

    @Test
    void aRepeatedSingleValuedElementKeepsTheFirstAndShowsTheRest() {
        CrdReading reading = read(systemAction(code("pa-needed", "no-auth") + "," + code("pa-needed", "auth-needed")));
        assertThat(only(reading).paNeeded().code()).isEqualTo("no-auth");
        assertThat(only(reading).otherSubExtensions()).singleElement().satisfies(other -> {
            assertThat(other.url()).isEqualTo("pa-needed (repeated)");
            assertThat(other.value().value()).isEqualTo("auth-needed");
        });
    }

    @Test
    void severalServicesWithDifferentAnswersAreMixedAndEachIsListed() {
        String body = "{\"systemActions\":["
                + "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\",\"extension\":[{\"url\":\"" + EXT
                + "\",\"extension\":[" + code("pa-needed", "auth-needed") + "]}]}},"
                + "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-2\",\"extension\":[{\"url\":\"" + EXT
                + "\",\"extension\":[" + code("pa-needed", "no-auth") + "]}]}}]}";
        CrdReading reading = read(body);
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.MIXED);
        assertThat(reading.detail().coverageInformation()).extracting(CoverageInformationView::outcome)
                .containsExactly(CrdOutcome.AUTH_REQUIRED, CrdOutcome.NO_AUTH_REQUIRED);
        assertThat(reading.detail().coverageInformation()).extracting(v -> v.appliesTo().value())
                .containsExactly("ServiceRequest/service-request-1", "ServiceRequest/service-request-2");
        // The codes of the two answers are kept apart, never pooled into one list.
        assertThat(reading.answers()).extracting(a -> a.codes().get("pa-needed")).containsExactly(List.of("auth-needed"), List.of("no-auth"));
        // The same answer for every service is that answer, not MIXED.
        assertThat(read(body.replace("\"no-auth\"", "\"auth-needed\"")).outcome()).isEqualTo(CrdOutcome.AUTH_REQUIRED);
    }

    // ---- placement ----

    @Test
    void coverageInformationIsFoundInEveryCardExtensionShapeVendorsUse() {
        String sub = "[" + code("covered", "covered") + "," + code("pa-needed", "no-auth") + "]";
        String fhirArray = "{\"cards\":[{\"summary\":\"s\",\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":" + sub + "}]}]}";
        String objectValue = "{\"cards\":[{\"summary\":\"s\",\"extension\":{\"anything\":{\"url\":\"" + EXT + "\",\"extension\":" + sub + "}}}]}";
        String objectArray = "{\"cards\":[{\"summary\":\"s\",\"extension\":{\"list\":[{\"url\":\"" + EXT + "\",\"extension\":" + sub + "}]}}]}";
        String keyedByUrl = "{\"cards\":[{\"summary\":\"s\",\"extension\":{\"" + EXT + "\":" + sub + "}}]}";
        String keyedObject = "{\"cards\":[{\"summary\":\"s\",\"extension\":{\"davinci-crd.coverage-information\":{\"extension\":" + sub + "}}}]}";
        for (String body : List.of(fhirArray, objectValue, objectArray, keyedByUrl, keyedObject)) {
            CrdReading reading = read(body);
            assertThat(reading.outcome()).as(body).isEqualTo(CrdOutcome.NO_AUTH_REQUIRED);
            assertThat(only(reading).foundAt()).as(body).startsWith("cards[0].extension");
            assertThat(reading.additionalFields()).as(body).isEmpty();
        }
    }

    @Test
    void aCardExtensionThatIsNotCoverageInformationStaysVisible() {
        CrdReading reading = read("{\"cards\":[{\"summary\":\"s\",\"extension\":{\"com.vendor.flag\":true,\"other\":[{\"url\":\"x\",\"valueString\":\"y\"}]}}]}");
        assertThat(reading.detail().coverageInformation()).isEmpty();
        assertThat(paths(reading)).containsExactly("cards[0].extension.\"com.vendor.flag\"", "cards[0].extension.other[0].url",
                "cards[0].extension.other[0].valueString");
    }

    // ---- nothing silently dropped ----

    @Test
    void aFieldTheMapperWasNeverToldAboutReachesTheAdditionalFieldsAtEveryLevel() {
        String body = "{\"vendorTraceId\":\"t-1\",\"cards\":[{\"summary\":\"s\",\"vendorScore\":0.9,\"source\":{\"label\":\"P\",\"vendorSourceId\":7},"
                + "\"links\":[{\"label\":\"l\",\"url\":\"u\",\"type\":\"absolute\",\"vendorLinkFlag\":true}]}],"
                + "\"systemActions\":[{\"type\":\"update\",\"description\":\"d\",\"vendorActionNote\":\"n\",\"resource\":{\"resourceType\":\"ServiceRequest\","
                + "\"id\":\"service-request-1\",\"status\":\"draft\",\"vendorResourceField\":null,\"extension\":[{\"url\":\"" + EXT + "\",\"id\":\"ext-id\","
                + "\"extension\":[{\"url\":\"covered\",\"valueCode\":\"covered\",\"vendorSubField\":\"\"},"
                + "{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":{\"text\":\"Program\",\"vendorConceptField\":1}},"
                + "{\"url\":\"value\",\"valueString\":\"Imaging\"},{\"url\":\"vendorPart\",\"valueString\":\"vp\"}]}]},"
                + "{\"url\":\"https://example.org/other-extension\",\"valueString\":\"kept\"}]}}]}";
        CrdReading reading = read(body);
        assertThat(paths(reading)).containsExactlyInAnyOrder(
                "vendorTraceId",
                "cards[0].vendorScore",
                "cards[0].source.vendorSourceId",
                "cards[0].links[0].vendorLinkFlag",
                "systemActions[0].vendorActionNote");
        // What the resource of an action holds beyond its coverage information is shown with that
        // action, so an echoed order does not fill the list of fields nobody anticipated.
        List<AdditionalField> rest = reading.detail().systemActions().get(0).resource();
        assertThat(rest).extracting(AdditionalField::path).containsExactlyInAnyOrder(
                "systemActions[0].resource.status",
                "systemActions[0].resource.vendorResourceField",
                "systemActions[0].resource.extension[0].id",
                "systemActions[0].resource.extension[0].extension[0].vendorSubField",
                "systemActions[0].resource.extension[0].extension[1].extension[0].valueCodeableConcept.vendorConceptField",
                "systemActions[0].resource.extension[1].url",
                "systemActions[0].resource.extension[1].valueString");
        // The three kinds of nothing keep their own presence.
        assertThat(rest).filteredOn(f -> f.path().endsWith("vendorResourceField")).singleElement()
                .satisfies(f -> assertThat(f.presence()).isEqualTo(Presence.NULL));
        assertThat(rest).filteredOn(f -> f.path().endsWith("vendorSubField")).singleElement()
                .satisfies(f -> assertThat(f.presence()).isEqualTo(Presence.EMPTY));
        // An unknown part of a detail entry is shown in the designed view, since the detail list must be shown in full.
        assertThat(only(reading).otherSubExtensions()).singleElement().satisfies(o -> assertThat(o.url()).isEqualTo("detail.vendorPart"));
    }

    @Test
    void everyCardFieldIsInTheDesignedView() {
        String body = "{\"cards\":[{\"uuid\":\"u-1\",\"summary\":\"S\",\"detail\":\"D\",\"indicator\":\"warning\",\"selectionBehavior\":\"any\","
                + "\"source\":{\"label\":\"Payer\",\"url\":\"https://example.org\",\"icon\":\"https://example.org/i.png\","
                + "\"topic\":{\"system\":\"s\",\"code\":\"guideline\",\"display\":\"Guideline\"}},"
                + "\"links\":[{\"label\":\"L\",\"url\":\"https://example.org/l\",\"type\":\"smart\",\"appContext\":\"ctx\"}],"
                + "\"overrideReasons\":[{\"system\":\"s\",\"code\":\"r1\",\"display\":\"Reason one\"}],"
                + "\"suggestions\":[{\"label\":\"Do it\",\"uuid\":\"sg-1\",\"isRecommended\":true,\"actions\":[{\"type\":\"create\",\"description\":\"Create\","
                + "\"resource\":{\"resourceType\":\"Task\",\"id\":\"t1\"}},{\"type\":\"delete\",\"description\":\"Delete\",\"resourceId\":\"ServiceRequest/x\"}]}]}]}";
        CrdReading reading = read(body);
        assertThat(reading.additionalFields()).isEmpty();
        var card = reading.detail().cards().get(0);
        assertThat(card.uuid().value()).isEqualTo("u-1");
        assertThat(card.summary().value()).isEqualTo("S");
        assertThat(card.detail().value()).isEqualTo("D");
        assertThat(card.indicator().value()).isEqualTo("warning");
        assertThat(card.selectionBehavior().value()).isEqualTo("any");
        assertThat(card.sourceLabel().value()).isEqualTo("Payer");
        assertThat(card.sourceUrl().value()).isEqualTo("https://example.org");
        assertThat(card.sourceIcon().value()).isEqualTo("https://example.org/i.png");
        assertThat(card.sourceTopic().value()).isEqualTo("Guideline (s|guideline)");
        assertThat(card.links().get(0).appContext().value()).isEqualTo("ctx");
        assertThat(card.overrideReasons().get(0).value()).isEqualTo("Reason one (s|r1)");
        assertThat(card.suggestions().get(0).isRecommended().value()).isEqualTo("true");
        assertThat(card.suggestions().get(0).actions()).hasSize(2);
        ActionView create = card.suggestions().get(0).actions().get(0);
        assertThat(create.resourceType().value()).isEqualTo("Task");
        // The id of the resource carried and the action's own resourceId are two fields, kept in two slots.
        assertThat(create.resourceLogicalId().value()).isEqualTo("t1");
        assertThat(create.resourceId()).isEqualTo(Shown.absent());
        ActionView delete = card.suggestions().get(0).actions().get(1);
        assertThat(delete.resourceId().value()).isEqualTo("ServiceRequest/x");
        assertThat(delete.resourceLogicalId()).isEqualTo(Shown.absent());
        ActionView both = read("{\"systemActions\":[{\"type\":\"update\",\"resourceId\":\"ServiceRequest/a\",\"resource\":{\"resourceType\":"
                + "\"ServiceRequest\",\"id\":\"b\"}}]}").detail().systemActions().get(0);
        assertThat(both.resourceId().value()).isEqualTo("ServiceRequest/a");
        assertThat(both.resourceLogicalId().value()).isEqualTo("b");
        // A card with none of the optional fields reports each as not returned, not as blank.
        var bare = read("{\"cards\":[{\"summary\":\"only\"}]}").detail().cards().get(0);
        assertThat(bare.detail()).isEqualTo(Shown.absent());
        assertThat(bare.sourceLabel()).isEqualTo(Shown.absent());
        assertThat(bare.links()).isEmpty();
    }

    @Test
    void anEmptyListIsToldApartFromAListThatWasNotSent() {
        // Both give an empty list in the view. Only the one that was sent appears among the returned fields.
        CrdReading sentEmpty = read("{\"cards\":[{\"summary\":\"s\",\"links\":[],\"suggestions\":[]}]}");
        assertThat(sentEmpty.detail().cards().get(0).links()).isEmpty();
        assertThat(sentEmpty.additionalFields()).containsExactly(new AdditionalField("cards[0].links", Presence.EMPTY, "[]"),
                new AdditionalField("cards[0].suggestions", Presence.EMPTY, "[]"));
        CrdReading notSent = read("{\"cards\":[{\"summary\":\"s\"}]}");
        assertThat(notSent.detail().cards().get(0).links()).isEmpty();
        assertThat(notSent.additionalFields()).isEmpty();
    }

    @Test
    void aKeyThatAppearsTwiceInTheBodyIsPointedOut() {
        CrdReading reading = read("{\"cards\":[{\"summary\":\"s\",\"indicator\":\"info\",\"indicator\":\"warning\"}]}");
        assertThat(reading.detail().cards().get(0).indicator().value()).isEqualTo("warning");
        assertThat(reading.additionalFields()).singleElement().satisfies(note -> {
            assertThat(note.kind()).isEqualTo(AdditionalField.Kind.NOTE);
            assertThat(note.path()).isEqualTo("(body)");
            assertThat(note.value()).contains("repeats a key").contains("wire view");
        });
    }

    @Test
    void aFieldOfTheWrongShapeIsNotLost() {
        // cards is an object where an array is expected, and a card is a string.
        CrdReading objectCards = read("{\"cards\":{\"summary\":\"not an array\"}}");
        assertThat(objectCards.detail().cards()).isEmpty();
        assertThat(paths(objectCards)).containsExactly("cards.summary");
        CrdReading stringCard = read("{\"cards\":[\"just text\",{\"summary\":\"real\"}]}");
        assertThat(stringCard.detail().cards()).hasSize(1);
        assertThat(stringCard.detail().cards().get(0).index()).isEqualTo(1);
        assertThat(paths(stringCard)).containsExactly("cards[0]");
    }

    // ---- every case, not the happy path ----

    @Test
    void anOperationOutcomeIsReadTheSameWayWhateverStatusCarriedIt() {
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"id\":\"oo-1\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\","
                + "\"details\":{\"text\":\"Bad order\",\"coding\":[{\"system\":\"s\",\"code\":\"E1\"}]},\"diagnostics\":\"diag\","
                + "\"expression\":[\"context.draftOrders\"],\"location\":[\"/context/draftOrders\"],\"vendorHint\":\"h\"},"
                + "{\"severity\":\"warning\",\"code\":\"informational\"}]}";
        for (int status : new int[] {200, 400, 422, 500}) {
            CrdReading reading = mapper.read(TestExchanges.crd(status, outcome));
            assertThat(reading.detail().responseKind()).isEqualTo(CrdResponseKind.OPERATION_OUTCOME);
            assertThat(reading.detail().issues()).hasSize(2);
            var first = reading.detail().issues().get(0);
            assertThat(first.severity().value()).isEqualTo("error");
            assertThat(first.code().value()).isEqualTo("invalid");
            assertThat(first.detailsText().value()).isEqualTo("Bad order");
            assertThat(first.detailsCodes()).extracting(Shown::value).containsExactly("s|E1");
            assertThat(first.diagnostics().value()).isEqualTo("diag");
            assertThat(first.expression()).extracting(Shown::value).containsExactly("context.draftOrders");
            assertThat(first.location()).extracting(Shown::value).containsExactly("/context/draftOrders");
            assertThat(reading.detail().issues().get(1).diagnostics()).isEqualTo(Shown.absent());
            assertThat(paths(reading)).containsExactly("id", "issue[0].vendorHint");
        }
        assertThat(mapper.read(TestExchanges.crd(200, outcome)).outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(mapper.read(TestExchanges.crd(400, outcome)).outcome()).isEqualTo(CrdOutcome.REJECTED);
        assertThat(mapper.read(TestExchanges.crd(500, outcome)).outcome()).isEqualTo(CrdOutcome.SERVICE_ERROR);
    }

    @Test
    void aCdsResponseThatArrivesWithAnErrorStatusIsStillShownButIsNotADetermination() {
        CrdReading reading = mapper.read(TestExchanges.crd(500, systemAction(code("pa-needed", "no-auth"))));
        // The body says no-auth, but the service failed. The status wins: this is not a clean answer.
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.SERVICE_ERROR);
        assertThat(only(reading).paNeeded().code()).isEqualTo("no-auth");
    }

    @Test
    void aBodyThatCannotBeParsedIsReportedAsSuchAndNeverAsADetermination() {
        CrdReading truncated = mapper.read(TestExchanges.crd(200, "{\"cards\": ["));
        assertThat(truncated.outcome()).isEqualTo(CrdOutcome.MALFORMED_RESPONSE);
        assertThat(truncated.detail().responseKind()).isEqualTo(CrdResponseKind.NOT_JSON);
        assertThat(truncated.detail().responseNote()).contains("wire view").contains("not valid JSON");
        // The text as received is on the result itself, not only in the wire view, which is collapsed by default.
        assertThat(truncated.additionalFields()).containsExactly(AdditionalField.unparsedBody("{\"cards\": ["));
        assertThat(truncated.additionalFields().get(0).kind()).isEqualTo(AdditionalField.Kind.RAW_BODY);

        CrdReading html500 = mapper.read(TestExchanges.crd(502, "<html>Bad Gateway</html>"));
        assertThat(html500.outcome()).isEqualTo(CrdOutcome.SERVICE_ERROR);
        assertThat(html500.detail().responseKind()).isEqualTo(CrdResponseKind.NOT_JSON);
        assertThat(html500.additionalFields()).extracting(AdditionalField::value).containsExactly("<html>Bad Gateway</html>");
    }

    @Test
    void anEmptyBodyIsMalformedAndIsNotTheSameAsAnEmptyCdsResponse() {
        CrdReading emptyBody = mapper.read(TestExchanges.crd(200, null));
        assertThat(emptyBody.outcome()).isEqualTo(CrdOutcome.MALFORMED_RESPONSE);
        assertThat(emptyBody.detail().responseKind()).isEqualTo(CrdResponseKind.NO_BODY);
        assertThat(read("{\"cards\":[]}").outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
    }

    @Test
    void jsonThatIsNotAnObjectIsListedInFullAndInterpretsNothing() {
        CrdReading reading = read("[{\"summary\":\"array at the root\"}]");
        assertThat(reading.detail().responseKind()).isEqualTo(CrdResponseKind.OTHER_JSON);
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(paths(reading)).containsExactly("[0].summary");
    }

    @Test
    void transportFailuresHaveTheirOwnOutcomesAndNoContent() {
        CrdReading timeout = mapper.read(TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN));
        assertThat(timeout.outcome()).isEqualTo(CrdOutcome.TIMEOUT);
        assertThat(timeout.detail().responseKind()).isEqualTo(CrdResponseKind.NO_RESPONSE);
        CrdReading unreachable = mapper.read(TestExchanges.unavailable(EpaOperation.CRD_ORDER_SIGN));
        assertThat(unreachable.outcome()).isEqualTo(CrdOutcome.UNREACHABLE);
        assertThat(unreachable.answers()).isEmpty();
        assertThat(unreachable.additionalFields()).isEmpty();
    }

    // ---- vendor conventions ----

    @Test
    void notFoundIsRecognisedOnlyFromACodedIssueThatSaysWhichThingWasNotFound() {
        String member = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"expression\":[\"Patient.identifier\"]}]}";
        String provider = member.replace("Patient.identifier", "PractitionerRole.practitioner");
        String byLocation = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"location\":[\"/Coverage\"]}]}";
        String byDetailsCode = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"details\":{\"coding\":[{\"code\":\"member-not-found\"}]}}]}";
        String unspecific = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"diagnostics\":\"Member not found\"}]}";
        String both = member.replace("Patient.identifier", "Patient.generalPractitioner");
        String wrongCode = member.replace("not-found", "invalid");

        assertThat(mapper.read(TestExchanges.crd(404, member)).outcome()).isEqualTo(CrdOutcome.MEMBER_NOT_FOUND);
        assertThat(mapper.read(TestExchanges.crd(200, member)).outcome()).isEqualTo(CrdOutcome.MEMBER_NOT_FOUND);
        assertThat(mapper.read(TestExchanges.crd(404, provider)).outcome()).isEqualTo(CrdOutcome.PROVIDER_NOT_FOUND);
        assertThat(mapper.read(TestExchanges.crd(404, byLocation)).outcome()).isEqualTo(CrdOutcome.MEMBER_NOT_FOUND);
        assertThat(mapper.read(TestExchanges.crd(404, byDetailsCode)).outcome()).isEqualTo(CrdOutcome.MEMBER_NOT_FOUND);
        // Wording alone is not read, an issue naming both is not guessed at, and another code is not a not-found.
        assertThat(mapper.read(TestExchanges.crd(404, unspecific)).outcome()).isEqualTo(CrdOutcome.REJECTED);
        assertThat(mapper.read(TestExchanges.crd(404, both)).outcome()).isEqualTo(CrdOutcome.REJECTED);
        assertThat(mapper.read(TestExchanges.crd(404, wrongCode)).outcome()).isEqualTo(CrdOutcome.REJECTED);
    }

    @Test
    void coverageInactiveIsRecognisedFromAReasonCodeAndOtherwiseItIsJustNotCovered() {
        String inactive = systemAction(code("covered", "not-covered")
                + ",{\"url\":\"reason\",\"valueCodeableConcept\":{\"coding\":[{\"system\":\"s\",\"code\":\"Coverage_Inactive\"}],\"text\":\"anything\"}}");
        String textOnly = systemAction(code("covered", "not-covered")
                + ",{\"url\":\"reason\",\"valueCodeableConcept\":{\"text\":\"Coverage inactive on date of service\"}}");
        assertThat(read(inactive).outcome()).isEqualTo(CrdOutcome.COVERAGE_INACTIVE);
        assertThat(read(textOnly).outcome()).isEqualTo(CrdOutcome.NOT_COVERED);
        assertThat(only(read(textOnly)).reasons().get(0).value()).isEqualTo("Coverage inactive on date of service");
    }

    @Test
    void theDelegatedVendorIsRecognisedByDetailCodeOrByAKnownVendorNameAndNeverGuessed() {
        String byCode = systemAction(code("pa-needed", "auth-needed") + ",{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":"
                + "{\"coding\":[{\"code\":\"um-vendor\"}]}},{\"url\":\"value\",\"valueString\":\"EVICORE\"}]}");
        String byName = systemAction(code("pa-needed", "auth-needed") + ",{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":"
                + "{\"text\":\"Routing\"}},{\"url\":\"value\",\"valueString\":\"optum post-acute\"}]}");
        String unknownVendorUnderVendorCode = byCode.replace("EVICORE", "Some New Vendor");
        String unrelated = systemAction(code("pa-needed", "auth-needed") + ",{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":"
                + "{\"text\":\"Program\"}},{\"url\":\"value\",\"valueString\":\"Advanced imaging\"}]}");

        assertThat(read(byCode).delegatedVendor()).isEqualTo("eviCore");
        assertThat(only(read(byCode)).delegatedVendorBasis()).isEqualTo("the detail code \"um-vendor\"");
        assertThat(read(byName).delegatedVendor()).isEqualTo("Optum Post Acute");
        assertThat(only(read(byName)).delegatedVendorBasis()).contains("name of a current delegated vendor");
        // A vendor the tool does not know is passed through as written, not dropped and not corrected.
        assertThat(read(unknownVendorUnderVendorCode).delegatedVendor()).isEqualTo("Some New Vendor");
        assertThat(read(unrelated).delegatedVendor()).isNull();
        assertThat(only(read(unrelated)).details()).hasSize(1);
        assertThat(read(systemAction(code("pa-needed", "auth-needed"))).delegatedVendor()).isNull();
    }
}
