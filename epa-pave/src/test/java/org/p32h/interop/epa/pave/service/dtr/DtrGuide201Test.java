package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * What DTR 2.0.1 requires of the QuestionnaireResponse this tool builds, sends to next-question and
 * hands to PAS, as the running service builds it: the coverage and the order in qr-context, the
 * intended use from the request or from CRD and never assumed, the author of an answer a person
 * gives, and a finding of kind REQUEST_INCOMPLETE for whatever nobody supplied.
 */
class DtrGuide201Test {

    private static final String QR_CONTEXT = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";
    private static final String QR_COVERAGE = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage";
    private static final String INTENDED_USE = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/intendedUse";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);
    private final OnyxDtrClient client = new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP));

    private DtrService service() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()),
                OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        return new DtrService(support.epa(), gateway, client, new FakeCodeList(), support);
    }

    private RunEnvelope<DtrDetail> fetch(String memberId) {
        return service().runPackage(DtrFixtures.packageRequest(memberId).build(), RunOrigin.manual(), null);
    }

    private static DtrNextQuestionRequest.Builder firstStep(RunEnvelope<DtrDetail> fetched, String memberId, Map<String, List<String>> carry) {
        return DtrNextQuestionRequest.builder().member(DtrFixtures.member(memberId)).provider(DtrFixtures.provider())
                .procedureCodes(List.of(CodeInput.of("72148"))).carry(carry).questionnaire(fetched.detail().questionnaires().get(0).json());
    }

    private static JsonNode sent(RunEnvelope<DtrDetail> result) {
        return JsonSafe.parse(result.wire().request().body()).node();
    }

    private static List<String> urls(JsonNode response) {
        return JsonSafe.elements(response, "extension").stream().map(extension -> JsonSafe.text(extension, "url")).toList();
    }

    @Test
    void aResponseStartedHereNamesCoverageAndOrderInQrContextAndStatesTheIntendedUseCrdHandedOn() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-LOOPERR");
        Map<String, List<String>> carry = new LinkedHashMap<>(fetched.nextStep().carry());
        carry.put(NextStepView.CARRY_DOC_PURPOSE, List.of("withpa"));
        RunEnvelope<DtrDetail> first = service().runNextQuestion(firstStep(fetched, "P32H-DTR-LOOPERR", carry).build(), RunOrigin.manual(), null);
        JsonNode response = sent(first);

        // DTR 2.0.1 dtr-questionnaireresponse: extension:context 2..* (qr-context, coverage and order), extension:intendedUse 1..*.
        assertThat(urls(response)).containsExactly(QR_CONTEXT, QR_CONTEXT, INTENDED_USE);
        assertThat(response.at("/extension/0/valueReference/reference").asString()).isEqualTo("Coverage/coverage-1");
        assertThat(response.at("/extension/1/valueReference/reference").asString()).isEqualTo("ServiceRequest/service-request-1");
        assertThat(response.at("/extension/2/valueCodeableConcept/coding/0/system").asString())
                .isEqualTo("http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp");
        assertThat(response.at("/extension/2/valueCodeableConcept/coding/0/code").asString()).isEqualTo("withpa");
        assertThat(first.detail().requestNotes()).contains("The response states its intended use as withpa, as CRD said the documentation "
                + "is for (doc-purpose). DTR 2.0.1 requires a response to state one.");
        // Neither the coverage, the order nor the intended use is among what the request left out.
        assertThat(first.detail().requestNotes()).noneMatch(note -> note.contains("qr-context extensions, naming"))
                .noneMatch(note -> note.contains("requires at least one intendedUse"));
        // The author of the response is the provider on the form, by its local id.
        assertThat(response.at("/author/reference").asString()).isEqualTo("Practitioner/practitioner-1");
    }

    @Test
    void theLaterFormOfTheCoverageIsWrittenOnlyWhenTheRequestAsksForItAndTheGapItLeavesIsNamed() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-LOOPERR");
        gateway.variable("dtrCoverageExtension", "qr-coverage");
        RunEnvelope<DtrDetail> first = service().runNextQuestion(firstStep(fetched, "P32H-DTR-LOOPERR", fetched.nextStep().carry()).build(),
                RunOrigin.manual(), null);
        JsonNode response = sent(first);
        assertThat(urls(response)).containsExactly(QR_COVERAGE, QR_CONTEXT);
        assertThat(first.detail().requestNotes()).contains("The DTR 2.0.1 QuestionnaireResponse profile (dtr-questionnaireresponse) requires "
                + "at least two qr-context extensions, naming the coverage and the order. This response has 1: the coverage is named in "
                + "qr-coverage, the DTR 2.2.0-ballot form, which 2.0.1 does not define (the variable dtrCoverageExtension asked for it).");
        assertThat(first.findings()).filteredOn(finding -> finding.kind() == FindingKind.REQUEST_INCOMPLETE).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("QuestionnaireResponse.extension:context"));
    }

    @Test
    void whatTheRequestLeftOutComesFirstAmongTheNotesAndIsOneFindingOnTheResultAndTheLedgerLine() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-LOOPERR");
        RunEnvelope<DtrDetail> first = service().runNextQuestion(firstStep(fetched, "P32H-DTR-LOOPERR", fetched.nextStep().carry()).build(),
                RunOrigin.manual(), null);
        // Nothing stated an intended use: it is left out, never assumed, and the first note counts what was left out.
        assertThat(sent(first).toString()).doesNotContain(INTENDED_USE);
        assertThat(first.detail().requestNotes().get(0)).startsWith("This request goes out without ")
                .contains("the DTR 2.0.1 profiles require (").contains("QuestionnaireResponse.extension:intendedUse");
        Finding incomplete = first.findings().stream().filter(finding -> finding.kind() == FindingKind.REQUEST_INCOMPLETE).findFirst().orElseThrow();
        assertThat(incomplete.title()).startsWith("The request left out ").endsWith(" DTR 2.0.1 requires");
        assertThat(incomplete.subject()).isEqualTo("DTR 2.0.1");
        assertThat(ledger.last().findings()).contains("REQUEST_INCOMPLETE");
        // The outcome is the service's answer, not changed by what the request lacked.
        assertThat(first.verdict().outcomeCode()).isNotEqualTo("REJECTED");

        // The package request says the same of the coverage it sends without a status or a payer, which nobody supplied.
        RequestPreview preview = service().previewPackage(DtrFixtures.packageRequest("S123456789").build(), null);
        assertThat(preview.notes().get(0)).startsWith("This request goes out without ");
        assertThat(preview.notes()).contains("The CRD 2.0.1 Coverage profile (profile-coverage), which DTR 2.0.1 requires of the coverage "
                + "parameter, requires Coverage.status. It was left out: it comes from the coverage status the member lookup returned, and "
                + "none this tool can map to a FHIR code was supplied.", "The CRD 2.0.1 Coverage profile (profile-coverage), which DTR 2.0.1 "
                + "requires of the coverage parameter, requires Coverage.payor. It was left out: it comes from the variable payerIdentifier "
                + "or payerName, and neither is set.");
        assertThat(fetched.findings()).extracting(Finding::kind).contains(FindingKind.REQUEST_INCOMPLETE);
    }

    @Test
    void aResponseTheServiceReturnsInTheLaterFormIsReadAndNamedWithItsVersionNotCalledWrong() {
        String later = "{\"resourceType\":\"QuestionnaireResponse\",\"meta\":{\"profile\":[\"http://hl7.org/fhir/us/davinci-dtr/"
                + "StructureDefinition/dtr-questionnaireresponse-adapt\"]},\"extension\":[{\"url\":\"" + QR_COVERAGE + "\",\"valueReference\":"
                + "{\"reference\":\"Coverage/coverage-1\"}},{\"url\":\"" + QR_CONTEXT + "\",\"valueReference\":{\"reference\":"
                + "\"ServiceRequest/service-request-1\"}}],\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"derivedFrom\":"
                + "[\"https://example.org/p32h-placeholder/Questionnaire/imaging-adaptive|1.0.0\"],\"status\":\"active\",\"item\":[{\"linkId\":"
                + "\"q1\",\"type\":\"boolean\",\"text\":\"First\"}]}],\"questionnaire\":\"#q\",\"status\":\"in-progress\"}";
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()),
                OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        OnyxDtrClient answering = new OnyxDtrClient() {
            @Override
            public org.p32h.interop.epa.pave.model.VendorExchange questionnairePackage(org.p32h.interop.epa.pave.service.config.PreparedCall call,
                    org.p32h.interop.epa.pave.model.ResolvedText body, org.p32h.interop.epa.pave.client.MockSelection selection) {
                throw new AssertionError("not called");
            }

            @Override
            public org.p32h.interop.epa.pave.model.VendorExchange nextQuestion(org.p32h.interop.epa.pave.service.config.PreparedCall call,
                    org.p32h.interop.epa.pave.model.ResolvedText body, org.p32h.interop.epa.pave.client.MockSelection selection) {
                return DtrFixtures.answered(org.p32h.interop.epa.pave.model.EpaOperation.DTR_NEXT_QUESTION, 200, later);
            }
        };
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-LOOPERR");
        RunEnvelope<DtrDetail> step = new DtrService(support.epa(), gateway, answering, new FakeCodeList(), support)
                .runNextQuestion(firstStep(fetched, "P32H-DTR-LOOPERR", fetched.nextStep().carry()).build(), RunOrigin.manual(), null);

        assertThat(step.verdict().outcomeCode()).isEqualTo("NEXT_QUESTION");
        assertThat(step.findings()).filteredOn(finding -> finding.kind() == FindingKind.OTHER_VERSION_CONTENT)
                .extracting(Finding::title).containsExactly("The response names its coverage in qr-coverage, a DTR 2.2.0-ballot extension",
                        "The response declares a DTR 2.2.0-ballot profile");
        assertThat(step.findings()).noneMatch(finding -> finding.kind() == FindingKind.CODE_OUTSIDE_VALUE_SET);
        // The extension is read and shown where it sits, not left among the fields nobody interpreted.
        assertThat(step.detail().questionnaireResponses().get(0).extensions()).hasSize(2);
    }

    @Test
    void anAnswerAPersonGivesWithNoProviderOnTheFormIsRefusedNamingDtrx1AndNothingIsSentOrRecorded() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-ADAPT");
        // A step that adds no answer adds nothing a person gave, so it needs no author.
        RunEnvelope<DtrDetail> first = service().runNextQuestion(DtrNextQuestionRequest.builder().member(DtrFixtures.member("P32H-DTR-ADAPT"))
                .carry(fetched.nextStep().carry()).questionnaireResponse(fetched.detail().questionnaireResponses().get(0).json()).build(),
                RunOrigin.manual(), null);
        assertThat(first.verdict().outcomeCode()).isEqualTo("NEXT_QUESTION");

        int recorded = ledger.records().size();
        DtrNextQuestionRequest unauthored = DtrNextQuestionRequest.builder().member(DtrFixtures.member("P32H-DTR-ADAPT"))
                .carry(first.nextStep().carry()).questionnaireResponse(first.detail().questionnaireResponses().get(0).json())
                .answers(List.of(DtrAnswerInput.of("mri-1-therapy", "no"))).build();
        assertThatThrownBy(() -> service().runNextQuestion(unauthored, RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("provider.npi");
                    assertThat(error.message()).isEqualTo(DtrService.AUTHOR_NEEDED);
                }));
        assertThatThrownBy(() -> service().previewNextQuestion(unauthored, null)).isInstanceOf(ValidationException.class);
        assertThat(ledger.records()).hasSize(recorded);
    }

    @Test
    void aStaticResponseStatesTheIntendedUseTheEnvironmentGivesAndLeavesNothingOutWhenEveryFactIsSupplied() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DOC-DTR");
        DtrResponseRequest.Builder answered = DtrResponseRequest.builder().member(DtrFixtures.member("P32H-DOC-DTR")).provider(DtrFixtures.provider())
                .procedureCodes(List.of(CodeInput.of("95810"))).carry(fetched.nextStep().carry())
                .questionnaire(fetched.detail().questionnaires().get(0).json())
                .answers(List.of(DtrAnswerInput.of("sleep-1", "true"), DtrAnswerInput.of("sleep-2", "14"), DtrAnswerInput.of("sleep-3", "snoring")));

        RunEnvelope<DtrDetail> without = service().runResponse(answered.build(), RunOrigin.manual());
        assertThat(without.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(without.findings()).filteredOn(finding -> finding.kind() == FindingKind.REQUEST_INCOMPLETE).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.title()).isEqualTo("The response left out 1 element DTR 2.0.1 requires");
                    assertThat(finding.detail()).contains("QuestionnaireResponse.extension:intendedUse").contains("PAS sends the response as it is");
                });
        assertThat(without.detail().form().notes().get(0)).isEqualTo("This response leaves out 1 element the DTR 2.0.1 profiles require "
                + "(QuestionnaireResponse.extension:intendedUse). Each is explained below with what would supply it.");

        // The response step sends nothing and takes no run values, so the value of the active environment is the one it reads.
        gateway.variable("dtrIntendedUse", "withpa");
        RunEnvelope<DtrDetail> with = service().runResponse(answered.build(), RunOrigin.manual());
        JsonNode response = JsonSafe.parse(with.detail().questionnaireResponses().get(0).json()).node();
        assertThat(urls(response)).containsExactly(QR_CONTEXT, QR_CONTEXT, INTENDED_USE);
        assertThat(with.findings()).noneMatch(finding -> finding.kind() == FindingKind.REQUEST_INCOMPLETE);
        // Every answer the person gave names the provider as its author, as dtrx-1 requires.
        for (JsonNode item : response.get("item")) {
            assertThat(item.at("/answer/0/extension/0/extension/1/url").asString()).isEqualTo("author");
        }
    }
}
