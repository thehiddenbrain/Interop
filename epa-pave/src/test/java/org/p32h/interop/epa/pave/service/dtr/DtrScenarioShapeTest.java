package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * The mock scenarios in the shape DTR 2.0.1 gives (DTR-08), and the sentinels that mean the same
 * case in DTR as in CRD (V4-04). The rules are those of the DTR 2.0.1 package, named where each is
 * checked; a file that departs from 2.0.1 on purpose says so in its description and is held to
 * what it says.
 */
class DtrScenarioShapeTest {

    private static final String DTR = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/";
    private static final String ADAPTIVE = "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";

    private final ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);

    private List<Scenario> all() {
        List<Scenario> all = scenarios.list(MockOnyxDtrClient.SCENARIOS);
        assertThat(all).hasSize(15);
        return all;
    }

    /** Every resource of a type beneath a node, wherever it sits. */
    private static List<JsonNode> resources(JsonNode node, String type) {
        List<JsonNode> out = new ArrayList<>();
        visit(node, resource -> {
            if (QuestionnaireJson.isType(resource, type)) {
                out.add(resource);
            }
        });
        return out;
    }

    private static void visit(JsonNode node, Consumer<JsonNode> each) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            each.accept(node);
            JsonSafe.properties(node).forEach(property -> visit(property.getValue(), each));
        } else if (node.isArray()) {
            node.forEach(element -> visit(element, each));
        }
    }

    private static boolean adaptive(JsonNode questionnaire) {
        return JsonSafe.elements(questionnaire, "extension").stream().anyMatch(e -> ADAPTIVE.equals(JsonSafe.presentText(e, "url")))
                || JsonSafe.elements(JsonSafe.child(questionnaire, "meta"), "profile").stream()
                .anyMatch(profile -> String.valueOf(JsonSafe.presentText(profile)).contains("adapt"));
    }

    private static List<String> urls(JsonNode resource) {
        return JsonSafe.elements(resource, "extension").stream().map(e -> String.valueOf(JsonSafe.presentText(e, "url"))).toList();
    }

    @Test
    void everyFileSaysWhichVersionItModelsAndOneThatDepartsSaysSo() {
        for (Scenario scenario : all()) {
            assertThat(scenario.description()).as(scenario.id()).contains("DTR 2.0.1");
            assertThat(JsonSafe.presentText(scenario.raw(), "basis")).as(scenario.id()).contains("Shaped to DTR 2.0.1");
        }
    }

    @Test
    void aStandardQuestionnaireStatesItsSubjectAndANarrativeAsDtr201Requires() {
        int checked = 0;
        for (Scenario scenario : all()) {
            for (JsonNode questionnaire : resources(scenario.raw(), "Questionnaire")) {
                // dtr-std-questionnaire 2.0.1: subjectType 1..1 patternCode Patient, text 1..1. The adaptive profiles also require text.
                assertThat(JsonSafe.child(questionnaire, "text").isObject()).as(scenario.id() + " " + questionnaire.get("id")).isTrue();
                if (!adaptive(questionnaire)) {
                    assertThat(JsonSafe.elements(questionnaire, "subjectType")).as(scenario.id()).extracting(JsonSafe::presentText)
                            .containsExactly("Patient");
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(6);
    }

    @Test
    void everyDraftResponseNamesItsCoverageAndOrderInQrContextAndStatesItsIntendedUse() {
        int drafts = 0;
        for (Scenario scenario : all()) {
            JsonNode main = JsonSafe.child(scenario.raw(), "response");
            for (JsonNode response : resources(main, "QuestionnaireResponse")) {
                drafts++;
                String what = scenario.id() + " " + response.get("id");
                // dtr-questionnaireresponse 2.0.1: extension:context 2..* (qr-context), extension:intendedUse 1..*, authored 1..1.
                assertThat(urls(response)).as(what).containsExactly(DTR + "qr-context", DTR + "qr-context", DTR + "intendedUse")
                        .doesNotContain(DTR + "qr-coverage");
                assertThat(response.at("/extension/0/valueReference/reference").asString()).as(what).isEqualTo("Coverage/coverage-1");
                assertThat(response.at("/extension/2/valueCodeableConcept/coding/0/system").asString())
                        .isEqualTo("http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp");
                assertThat(response.has("authored")).as(what).isTrue();
                assertThat(response.at("/meta/profile/0").asString()).as(what).isEqualTo(DTR + "dtr-questionnaireresponse");
            }
        }
        assertThat(drafts).isGreaterThanOrEqualTo(6);
    }

    @Test
    void everyPackageBundleCarriesADraftUnlessItsFileSaysWhyNot() {
        for (Scenario scenario : all()) {
            JsonNode main = JsonSafe.child(scenario.raw(), "response");
            for (JsonNode bundle : resources(main, "Bundle")) {
                List<JsonNode> questionnaires = resources(bundle, "Questionnaire");
                boolean draft = JsonSafe.elements(bundle, "entry").stream()
                        .anyMatch(entry -> QuestionnaireJson.isType(JsonSafe.child(entry, "resource"), "QuestionnaireResponse"));
                // The 2.0.1 $questionnaire-package: "the Questionnaire Bundle SHALL contain one or more initial draft QuestionnaireResponses".
                if (!draft) {
                    assertThat(scenario.description()).as(scenario.id()).contains("departs from DTR 2.0.1 on purpose");
                }
                // dtrb-1: a package bundle starts with its questionnaire, which only scenario 7 departs from, saying so.
                if (!questionnaires.isEmpty()) {
                    assertThat(QuestionnaireJson.isType(bundle.at("/entry/0/resource"), "Questionnaire")).as(scenario.id()).isTrue();
                }
            }
        }
    }

    @Test
    void theQuestionnaireInsideAResponseHasNoUrlAndOneDerivedFromNamingTheOneInThePackage() {
        int checked = 0;
        for (Scenario scenario : all()) {
            List<JsonNode> packaged = resources(JsonSafe.child(scenario.raw(), "response"), "Questionnaire").stream()
                    .filter(q -> q.has("url")).toList();
            for (JsonNode response : resources(scenario.raw(), "QuestionnaireResponse")) {
                JsonNode inside = QuestionnaireJson.containedQuestionnaire(response);
                if (inside == null) {
                    continue;
                }
                checked++;
                // dtr-questionnaire-adapt 2.0.1: url 0..0, derivedFrom 1..1.
                assertThat(inside.has("url")).as(scenario.id()).isFalse();
                assertThat(QuestionnaireJson.derivedFrom(inside)).as(scenario.id()).singleElement()
                        .isIn(packaged.stream().map(QuestionnaireJson::canonical).toList());
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(10);
    }

    @Test
    void theCrdSentinelsForTheSameCasesSelectTheDtrScenarioThatMeansTheSameThing() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()),
                OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        DtrService service = new DtrService(support.epa(), FakeGateway.unconfigured(),
                new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP)), new FakeCodeList(), support);
        Map<String, String> sameCase = Map.of("P32H-NOMEM", "MEMBER_NOT_FOUND", "P32H-NOPROV", "PROVIDER_NOT_FOUND",
                "P32H-ERR500", "SERVICE_ERROR", "P32H-TIMEOUT", "TIMEOUT", "P32H-BAD400", "REJECTED");
        sameCase.forEach((sentinel, outcome) -> {
            RunEnvelope<DtrDetail> result = service.runPackage(DtrFixtures.packageRequest(sentinel).build(), RunOrigin.manual(), null);
            assertThat(result.verdict().outcomeCode()).as(sentinel).isEqualTo(outcome);
            assertThat(result.verdict().supporting().get(0)).as(sentinel).contains("selected by the CRD sentinel member ID " + sentinel
                    + ", which this scenario answers for the same case");
        });
        // Case is forgiven, as for a DTR sentinel.
        assertThat(service.runPackage(DtrFixtures.packageRequest("p32h-nomem").build(), RunOrigin.manual(), null).verdict().outcomeCode())
                .isEqualTo("MEMBER_NOT_FOUND");
        // A member not found is not answered as a provider not found because the NPI is the sentinel one, as in CRD.
        RunEnvelope<DtrDetail> both = service.runPackage(DtrFixtures.packageRequest("P32H-NOMEM")
                .provider(org.p32h.interop.epa.pave.model.ProviderDetail.builder().npi("9999999995").lastName("Provider").build()).build(),
                RunOrigin.manual(), null);
        assertThat(both.verdict().outcomeCode()).isEqualTo("MEMBER_NOT_FOUND");
        // An ID that matches nothing still gets the default, and the result says it is the default.
        RunEnvelope<DtrDetail> unknown = service.runPackage(DtrFixtures.packageRequest("S123456789").build(), RunOrigin.manual(), null);
        assertThat(unknown.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(unknown.verdict().supporting().get(0)).contains("the default");
        // The DTR sentinels keep working.
        assertThat(service.runPackage(DtrFixtures.packageRequest("P32H-DTR-NOMEM").build(), RunOrigin.manual(), null).verdict().outcomeCode())
                .isEqualTo("MEMBER_NOT_FOUND");
    }
}
