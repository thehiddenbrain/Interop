package com.thehiddenbrain.interop.patientaccess.patient;

import tools.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientWorkspaceServiceTest {

    @TempDir
    Path dir;
    WireMockServer wiremock;
    TestGraph g;
    Environment env;
    String base;
    ObjectNode claim;
    ObjectNode priorAuth;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        base = wiremock.baseUrl() + "/fhir";
        g = new TestGraph(dir);
        env = g.openEnvironment("wm", base);
        claim = Fixtures.demo("c4bb-ExplanationOfBenefit-EOBInpatient1");
        priorAuth = Fixtures.demo("pdex-ExplanationOfBenefit-PDexPriorAuth1");
        // every search not stubbed more specifically answers an empty searchset
        wiremock.stubFor(get(urlPathMatching("/fhir/[A-Za-z]+")).atPriority(10).willReturn(Fixtures.fhir(Fixtures.emptyBundle())));
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void overviewCountsEveryDataClassAndRecordsErrorsInstead0fThrowing() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/1")).willReturn(Fixtures.fhir(Fixtures.demo("pdex-Patient-1"))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).willReturn(Fixtures.fhir(Fixtures.bundle(Fixtures.demo("c4bb-Coverage-Coverage1")))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).willReturn(Fixtures.fhir(
                Fixtures.bundle(List.of(claim, priorAuth), List.of(), 2, base + "/ExplanationOfBenefit?page=2"))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Condition")).willReturn(Fixtures.fhir(404, Fixtures.operationOutcome("error", "not-supported", "Condition is not supported"))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Observation")).willReturn(Fixtures.fhir(Fixtures.bundle(List.of(), List.of(), 42, null))));

        PatientWorkspaceService.Overview overview = g.workspace.overview(env, "1");
        assertThat(overview.patient().display()).isEqualTo("Johnny Appleseed");
        assertThat(overview.patientResource().path("id").asString("")).isEqualTo("1");
        assertThat(overview.patientChecks()).isNotNull();
        assertThat(overview.correlationId()).startsWith("patient-");
        assertThat(overview.dataClasses()).hasSize(PatientWorkspaceService.DATA_CLASSES.size());
        Map<String, PatientWorkspaceService.DataClassCount> byKey = new java.util.HashMap<>();
        overview.dataClasses().forEach(c -> byKey.put(c.key(), c));

        assertThat(byKey.get("coverage").count()).isEqualTo(1);
        assertThat(byKey.get("coverage").total()).isEqualTo(1);
        assertThat(byKey.get("coverage").status()).isEqualTo(200);
        assertThat(byKey.get("coverage").requestId()).isNotBlank();
        // claims and prior authorizations come from the same EOB bundle, split by use; totals are unknown while there are more pages
        assertThat(byKey.get("claims").count()).isEqualTo(1);
        assertThat(byKey.get("claims").total()).isNull();
        assertThat(byKey.get("claims").more()).isTrue();
        assertThat(byKey.get("priorAuth").count()).isEqualTo(1);
        assertThat(byKey.get("priorAuth").url()).contains("use=preauthorization");
        assertThat(byKey.get("Condition").count()).isNull();
        assertThat(byKey.get("Condition").status()).isEqualTo(404);
        assertThat(byKey.get("Condition").error()).isEqualTo("error/not-supported: Condition is not supported");
        assertThat(byKey.get("Observation").count()).isZero();
        assertThat(byKey.get("Observation").total()).isEqualTo(42);
        assertThat(byKey.get("Immunization").count()).isZero();
        assertThat(byKey.get("Immunization").error()).isNull();
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Condition")).withQueryParam("patient", equalTo("1")).withQueryParam("_count", equalTo("50")));
        assertThat(g.history.list(env.id(), null, overview.correlationId(), 100)).hasSize(1 + PatientWorkspaceService.DATA_CLASSES.size());

        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/missing")).willReturn(Fixtures.fhir(404, Fixtures.operationOutcome("error", "not-found", "nope"))));
        assertThatThrownBy(() -> g.workspace.overview(env, "missing")).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.UPSTREAM_ERROR));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/bundle")).willReturn(Fixtures.fhir(Fixtures.emptyBundle())));
        assertThatThrownBy(() -> g.workspace.overview(env, "bundle")).isInstanceOf(WorkbenchException.class).hasMessageContaining("did not return a Patient");
    }

    @Test
    void claimsFilterOutPriorAuthorizationsAndPassTypeAndSince() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("type", equalTo("institutional"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(claim, priorAuth))));

        PatientWorkspaceService.ResourceList claims = g.workspace.claims(env, "1", " institutional ", "2024-01-01", Map.of("_sort", "-_lastUpdated"));
        assertThat(claims.resourceType()).isEqualTo("ExplanationOfBenefit");
        assertThat(claims.rows()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("EOBInpatient1");
            assertThat(r.columns()).containsEntry("use", "claim");
            assertThat(r.checks().errors()).isZero();
        });
        assertThat(claims.total()).isEqualTo(2);
        assertThat(claims.pages()).isEqualTo(1);
        assertThat(claims.truncated()).isFalse();
        assertThat(claims.warnings()).isEmpty();
        assertThat(claims.correlationId()).startsWith("claims-");
        assertThat(claims.urls()).singleElement().satisfies(u -> assertThat(u)
                .isEqualTo(base + "/ExplanationOfBenefit?patient=1&type=institutional&_lastUpdated=ge2024-01-01&_sort=-_lastUpdated"));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("patient", equalTo("1"))
                .withQueryParam("type", equalTo("institutional")).withQueryParam("_lastUpdated", equalTo("ge2024-01-01"))
                .withQueryParam("_sort", equalTo("-_lastUpdated")).withQueryParam("_count", equalTo("50")));

        PatientWorkspaceService.ResourceList explicitPrefix = g.workspace.claims(env, "1", null, "gt2024-06-01", null);
        assertThat(explicitPrefix.urls().get(0)).isEqualTo(base + "/ExplanationOfBenefit?patient=1&_lastUpdated=gt2024-06-01");
        assertThat(explicitPrefix.rows()).isEmpty();
    }

    @Test
    void priorAuthorizationsAreSummarisedFromTheUseSearch() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("use", equalTo("preauthorization"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(priorAuth))));

        PatientWorkspaceService.PriorAuthList list = g.workspace.priorAuthorizations(env, "1", null);
        assertThat(list.items()).singleElement().satisfies(pa -> {
            assertThat(pa.id()).isEqualTo("PDexPriorAuth1");
            assertThat(pa.decision()).isEqualTo("APPROVED");
        });
        assertThat(list.fallbackUsed()).isFalse();
        assertThat(list.notes()).isEmpty();
        assertThat(list.pages()).isEqualTo(1);
        assertThat(list.truncated()).isFalse();
        assertThat(list.requestIds()).hasSize(1);
        assertThat(list.urls()).containsExactly(base + "/ExplanationOfBenefit?patient=1&use=preauthorization");
        assertThat(list.correlationId()).startsWith("priorauth-");
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("patient", equalTo("1"))
                .withQueryParam("use", equalTo("preauthorization")).withQueryParam("_count", equalTo("50")));

        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("_lastUpdated", equalTo("ge2025-01-01"))
                .willReturn(Fixtures.fhir(Fixtures.emptyBundle())));
        PatientWorkspaceService.PriorAuthList since = g.workspace.priorAuthorizations(env, "1", "2025-01-01");
        assertThat(since.urls().get(0)).endsWith("&_lastUpdated=ge2025-01-01");
        assertThat(since.items()).isEmpty();
        assertThat(since.notes()).singleElement().satisfies(n -> assertThat(n).startsWith("no prior authorization found"));
    }

    @Test
    void serverIgnoringUseIsFilteredClientSideWithANote() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("use", equalTo("preauthorization"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(claim, priorAuth))));
        PatientWorkspaceService.PriorAuthList list = g.workspace.priorAuthorizations(env, "1", null);
        assertThat(list.items()).extracting(PriorAuthSummary::id).containsExactly("PDexPriorAuth1");
        assertThat(list.fallbackUsed()).isFalse();
        assertThat(list.notes()).singleElement().satisfies(n -> assertThat(n).contains("returned 1 EOB(s) with use != preauthorization").contains("seems to be ignored"));
    }

    @Test
    void serverRejectingUseFallsBackToAllEobs() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("use", equalTo("preauthorization"))
                .willReturn(Fixtures.fhir(400, Fixtures.operationOutcome("error", "invalid", "unknown parameter use"))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("use", absent())
                .willReturn(Fixtures.fhir(Fixtures.bundle(claim, priorAuth))));

        PatientWorkspaceService.PriorAuthList list = g.workspace.priorAuthorizations(env, "1", null);
        assertThat(list.fallbackUsed()).isTrue();
        assertThat(list.items()).extracting(PriorAuthSummary::id).containsExactly("PDexPriorAuth1");
        assertThat(list.notes()).singleElement().satisfies(n -> assertThat(n).contains("rejected with HTTP 400").contains("filtered by use / profile"));
        assertThat(list.urls()).containsExactly(base + "/ExplanationOfBenefit?patient=1");
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/fhir/ExplanationOfBenefit")));

        // a server error is not a reason to fall back
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit")).withQueryParam("use", equalTo("preauthorization"))
                .willReturn(Fixtures.fhir(500, Fixtures.operationOutcome("fatal", "exception", "boom"))));
        assertThatThrownBy(() -> g.workspace.priorAuthorizations(env, "1", null)).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getUpstream().httpStatus()).isEqualTo(500));
    }

    @Test
    void coverageIncludesPayorsAndRetriesWithoutIncludeWhenRejected() {
        ObjectNode coverage = Fixtures.demo("c4bb-Coverage-Coverage1");
        ObjectNode payer = Fixtures.demo("c4bb-Organization-Payer2");
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).withQueryParam("_include", equalTo("Coverage:payor"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(coverage), List.of(payer), 1, null))));

        PatientWorkspaceService.ResourceList withPayor = g.workspace.coverage(env, "Patient1");
        assertThat(withPayor.rows()).extracting(ResourceRow::id).containsExactly("Coverage1");
        assertThat(withPayor.included()).extracting(ResourceRow::resourceType).containsExactly("Organization");
        assertThat(withPayor.warnings()).isEmpty();
        assertThat(withPayor.correlationId()).startsWith("coverage-");
        assertThat(withPayor.urls()).containsExactly(base + "/Coverage?patient=Patient1&_include=Coverage:payor");

        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).withQueryParam("_include", equalTo("Coverage:payor"))
                .willReturn(Fixtures.fhir(400, Fixtures.operationOutcome("error", "not-supported", "_include not supported"))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).withQueryParam("_include", absent())
                .willReturn(Fixtures.fhir(Fixtures.bundle(coverage))));
        PatientWorkspaceService.ResourceList plain = g.workspace.coverage(env, "Patient1");
        assertThat(plain.rows()).hasSize(1);
        assertThat(plain.included()).isEmpty();
        assertThat(plain.warnings()).singleElement().satisfies(w -> assertThat(w).contains("_include=Coverage:payor was rejected (400)"));
        assertThat(plain.urls()).containsExactly(base + "/Coverage?patient=Patient1");
        assertThat(g.history.list(env.id(), null, plain.correlationId(), 10)).hasSize(2);

        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).withQueryParam("_include", equalTo("Coverage:payor"))
                .willReturn(Fixtures.fhir(503, Fixtures.operationOutcome("fatal", "transient", "down"))));
        assertThatThrownBy(() -> g.workspace.coverage(env, "Patient1")).isInstanceOf(WorkbenchException.class);
    }

    @Test
    void clinicalListsWarnAboutUndeclaredParametersAndTruncation() {
        String page2 = base + "/Condition?page=2";
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Condition")).withQueryParam("page", absent())
                .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(Fixtures.resource("Condition", "c1")), List.of(), 2, page2))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Condition")).withQueryParam("page", equalTo("2"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(Fixtures.resource("Condition", "c2")), List.of(), 2, null))));
        PatientWorkspaceService.ResourceList conditions = g.workspace.clinical(env, "1", "Condition", Map.of("category", "problem-list-item", "bogus", "x", "empty", " "));
        assertThat(conditions.rows()).extracting(ResourceRow::id).containsExactly("c1", "c2");
        assertThat(conditions.pages()).isEqualTo(2);
        assertThat(conditions.warnings()).singleElement().satisfies(w -> assertThat(w).contains("'bogus'"));
        assertThat(conditions.correlationId()).startsWith("condition-");
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Condition")).withQueryParam("category", equalTo("problem-list-item")).withQueryParam("empty", absent()));

        wiremock.stubFor(get(urlPathEqualTo("/fhir/Condition")).withQueryParam("page", equalTo("2"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(Fixtures.resource("Condition", "c2")), List.of(), 3, base + "/Condition?page=3"))));
        for (int i = 3; i <= 6; i++) {
            wiremock.stubFor(get(urlPathEqualTo("/fhir/Condition")).withQueryParam("page", equalTo(String.valueOf(i)))
                    .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(Fixtures.resource("Condition", "c" + i)), List.of(), 7, base + "/Condition?page=" + (i + 1)))));
        }
        PatientWorkspaceService.ResourceList truncated = g.workspace.clinical(env, "1", "Condition", null);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.pages()).isEqualTo(5);
        assertThat(truncated.warnings()).singleElement().satisfies(w -> assertThat(w).contains("stopped after 5 page(s)"));
    }

    @Test
    void resourceDetailIncludesThePriorAuthSummaryForPaEobs() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit/PDexPriorAuth1")).willReturn(Fixtures.fhir(priorAuth)));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/ExplanationOfBenefit/EOBInpatient1")).willReturn(Fixtures.fhir(claim)));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/odd")).willReturn(Fixtures.fhir(claim)));

        PatientWorkspaceService.ResourceDetail pa = g.workspace.resource(env, "ExplanationOfBenefit", "PDexPriorAuth1");
        assertThat(pa.priorAuth().decision()).isEqualTo("APPROVED");
        assertThat(pa.row().columns()).containsEntry("use", "preauthorization");
        assertThat(pa.url()).isEqualTo(base + "/ExplanationOfBenefit/PDexPriorAuth1");
        assertThat(pa.requestId()).isNotBlank();
        assertThat(g.workspace.resource(env, "ExplanationOfBenefit", "EOBInpatient1").priorAuth()).isNull();
        assertThatThrownBy(() -> g.workspace.resource(env, "Patient", "odd")).isInstanceOf(WorkbenchException.class).hasMessageContaining("did not return a Patient");
    }
}
