package com.thehiddenbrain.interop.patientaccess.conformance;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.ClinicalChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.CoverageChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.DiscoveryChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.EobChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.ErrorHandlingChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.FormularyChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.PagingChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.PatientChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.PerformanceChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.PriorAuthChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.ProvenanceChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.SecurityChecks;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.SmartChecks;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.thehiddenbrain.interop.patientaccess.conformance.CheckStatus.FAIL;
import static com.thehiddenbrain.interop.patientaccess.conformance.CheckStatus.INFO;
import static com.thehiddenbrain.interop.patientaccess.conformance.CheckStatus.PASS;
import static com.thehiddenbrain.interop.patientaccess.conformance.CheckStatus.SKIP;
import static com.thehiddenbrain.interop.patientaccess.conformance.CheckStatus.WARN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole suite synchronously against {@link FakePatientAccessApi} on WireMock: a conformant
 * server yields the expected PASS/INFO/SKIP outcomes, a broken one the expected FAIL/WARN outcomes.
 */
class ChecksAgainstWireMockTest {

    static final String PATIENT = "Patient1";

    static WireMockServer server;
    static FakePatientAccessApi fake;
    static IgCatalog catalog = new IgCatalog();

    @TempDir
    Path dir;
    TestGraph graph;
    ConformanceRunner runner;
    ConformanceSuite suite;

    @BeforeAll
    static void startServer() {
        fake = new FakePatientAccessApi(catalog);
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(fake));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withTransformers(FakePatientAccessApi.NAME)));
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        fake.load();
        graph = new TestGraph(dir);
        suite = suite(graph.catalog);
        runner = new ConformanceRunner(suite, new RunStore(dir.resolve("conformance")), graph.gateway, graph.catalog, graph.checker,
                graph.priorAuth, graph.discovery, graph.properties, new SyncTaskExecutor(), graph.clock);
    }

    /** The real check beans, wired by a plain annotation context instead of the full Boot application. */
    static ConformanceSuite suite(IgCatalog catalog) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(IgCatalog.class, () -> catalog);
            ctx.register(DiscoveryChecks.class, SmartChecks.class, SecurityChecks.class, PatientChecks.class, CoverageChecks.class,
                    EobChecks.class, PriorAuthChecks.class, ClinicalChecks.class, ProvenanceChecks.class, PagingChecks.class,
                    ErrorHandlingChecks.class, FormularyChecks.class, PerformanceChecks.class, ConformanceSuite.class);
            ctx.refresh();
            return ctx.getBean(ConformanceSuite.class);
        }
    }

    Environment open(String name) {
        return graph.openEnvironment(name, server.baseUrl() + FakePatientAccessApi.OPEN_PREFIX);
    }

    Environment secured(String name, String token) {
        return graph.environment(name, server.baseUrl() + FakePatientAccessApi.SECURE_PREFIX,
                new EnvironmentInput.AuthInput(AuthMode.STATIC_TOKEN, false, null, null, null, null, null, null, null, token, null, null, null,
                        null, Map.of(), Map.of()), List.of());
    }

    static Map<String, CheckResult> byId(ConformanceRun run) {
        Map<String, CheckResult> out = new LinkedHashMap<>();
        for (CheckResult r : run.results()) {
            out.put(r.checkId(), r);
        }
        return out;
    }

    static void expect(Map<String, CheckResult> results, CheckStatus status, String... ids) {
        for (String id : ids) {
            CheckResult r = results.get(id);
            assertThat(r).as("result of " + id).isNotNull();
            assertThat(r.status()).as(id + ": " + r.message() + " " + r.details()).isEqualTo(status);
        }
    }

    @Test
    void conformantServerPassesTheSuite() {
        ConformanceRun run = runner.runNow(open("open"), PATIENT, List.of());
        Map<String, CheckResult> r = byId(run);

        assertThat(run.status()).isEqualTo(ConformanceRun.DONE);
        assertThat(run.results()).hasSize(suite.all().size());
        assertThat(run.counts().get("ERROR")).as("errors: " + run.results().stream().filter(x -> x.status() == CheckStatus.ERROR).toList()).isZero();
        assertThat(run.counts().get("FAIL")).as("failures: " + run.results().stream().filter(x -> x.status() == FAIL).toList()).isZero();

        expect(r, PASS, "discovery.metadata", "discovery.fhirVersion", "discovery.jsonFormat", "discovery.resources.declared",
                "discovery.searchParams.declared", "discovery.profiles.declared", "discovery.security.smart");
        expect(r, INFO, "discovery.implementationGuides");
        expect(r, PASS, "smart.wellKnown.present", "smart.wellKnown.required", "smart.wellKnown.capabilities", "smart.wellKnown.scopes",
                "smart.endpoints.consistent");
        expect(r, WARN, "smart.endpoints.https"); // plain http on loopback
        expect(r, SKIP, "security.unauthenticated.rejected", "security.invalidToken.rejected", "security.token.obtained", "security.wwwAuthenticate");
        expect(r, PASS, "security.metadata.open");
        expect(r, PASS, "patient.read", "patient.vread", "patient.search.id", "patient.search.identifier", "patient.search.name",
                "patient.search.name.birthdate", "patient.search.gender.name", "patient.search.family.gender", "patient.search.birthdate.family",
                "patient.profile.declared", "patient.profileLite", "patient.identifier.memberId", "patient.lastUpdated", "patient.unknown.404",
                "patient.revinclude.provenance");
        expect(r, PASS, "coverage.search.patient", "coverage.search.beneficiary", "coverage.search.id", "coverage.profile.declared",
                "coverage.profileLite", "coverage.payor.resolvable", "coverage.include.payor", "coverage.lastUpdated", "coverage.subscriberId");
        expect(r, INFO, "coverage.status");
        expect(r, PASS, "eob.search.patient", "eob.search.id", "eob.search.identifier", "eob.search.lastUpdated", "eob.search.serviceDate",
                "eob.search.serviceStartDate", "eob.search.billablePeriodStart", "eob.search.type", "eob.include.all", "eob.include.specific",
                "eob.profile.declared", "eob.profileLite", "eob.use.claim", "eob.references.resolvable", "eob.lastUpdated",
                "eob.identifier.uniqueClaimId");
        expect(r, INFO, "eob.financial", "eob.types.present", "eob.timeliness");
        expect(r, PASS, "priorauth.search.use", "priorauth.profile.declared", "priorauth.profileLite", "priorauth.use.fixed", "priorauth.decision",
                "priorauth.dates", "priorauth.items.services", "priorauth.quantities", "priorauth.denialReason", "priorauth.reviewAction",
                "priorauth.insurerProvider", "priorauth.search.id", "priorauth.read", "priorauth.search.lastUpdated");
        expect(r, INFO, "priorauth.present", "priorauth.drugs", "priorauth.timeliness");
        assertThat(r.get("priorauth.present").message()).startsWith("2 prior authorization");
        expect(r, PASS, "clinical.Encounter.search.patient", "clinical.Condition.search.patient", "clinical.Encounter.combos",
                "clinical.Encounter.profile", "clinical.Encounter.lastUpdated", "clinical.Device.search.patient");
        expect(r, SKIP, "clinical.Condition.combos", "clinical.Condition.profile");
        expect(r, INFO, "clinical.summary");
        expect(r, PASS, "provenance.revinclude", "provenance.profile");
        expect(r, PASS, "paging.count", "paging.next", "paging.next.sameHost", "paging.total", "paging.self");
        expect(r, PASS, "errors.unknownType", "errors.unknownResource", "errors.strictHandling", "errors.malformedDate", "errors.operationOutcome");
        expect(r, INFO, "formulary.insurancePlan", "formulary.formularyItem", "formulary.medicationKnowledge");
        expect(r, INFO, "performance.metadata", "performance.patientRead", "performance.eobSearch");

        assertThat(r.get("eob.search.patient").requestIds()).as("evidence recorded").isNotEmpty();
        assertThat(r.get("priorauth.denialReason").details()).anyMatch(d -> d.contains("197"));
    }

    @Test
    void securedServerEnforcesTokens() {
        Map<String, CheckResult> r = byId(runner.runNow(secured("secured", FakePatientAccessApi.TOKEN), PATIENT, List.of("security", "patient")));

        expect(r, PASS, "security.unauthenticated.rejected", "security.invalidToken.rejected", "security.token.obtained", "security.metadata.open",
                "security.wwwAuthenticate", "patient.read", "patient.search.id");
        assertThat(r.get("security.wwwAuthenticate").details()).anyMatch(d -> d.contains("invalid_token"));
    }

    @Test
    void openSecuredServerFailsTheSecurityChecks() {
        fake.fault(FakePatientAccessApi.Fault.OPEN_AUTH);
        Map<String, CheckResult> r = byId(runner.runNow(secured("wide-open", FakePatientAccessApi.TOKEN), PATIENT, List.of("security")));

        expect(r, FAIL, "security.unauthenticated.rejected", "security.invalidToken.rejected");
        expect(r, PASS, "security.token.obtained");
        expect(r, SKIP, "security.wwwAuthenticate");
    }

    @Test
    void brokenServerFailsTheRightChecks() {
        fake.fault(FakePatientAccessApi.Fault.IGNORE_USE_PARAM, FakePatientAccessApi.Fault.LEAK_OTHER_PATIENT, FakePatientAccessApi.Fault.PLAIN_404,
                FakePatientAccessApi.Fault.NO_NEXT_LINK, FakePatientAccessApi.Fault.NO_SMART_SECURITY, FakePatientAccessApi.Fault.NO_PKCE,
                FakePatientAccessApi.Fault.LENIENT_ONLY);
        // the denied prior authorization loses its denial reason
        ArrayNode adjudications = (ArrayNode) fake.resource("ExplanationOfBenefit", "PDexPriorAuthDenied").path("item").get(0).path("adjudication");
        adjudications.remove(adjudications.size() - 1);
        // the patient loses its profile and member identifier, a coverage its subscriberId, a claim its uniqueclaimid identifier
        ObjectNode patient = fake.resource("Patient", PATIENT);
        patient.remove("meta");
        ((ArrayNode) patient.path("identifier")).removeAll();
        fake.resource("Coverage", "Coverage1").remove("subscriberId");
        ((ObjectNode) fake.resource("ExplanationOfBenefit", "EOBProfessional2").path("identifier").get(0)).remove("type");

        Map<String, CheckResult> r = byId(runner.runNow(open("broken"), PATIENT, List.of()));

        expect(r, FAIL, "discovery.security.smart", "smart.wellKnown.required", "patient.profile.declared", "patient.identifier.memberId",
                "patient.lastUpdated", "coverage.subscriberId", "eob.search.patient", "eob.identifier.uniqueClaimId", "priorauth.denialReason",
                "errors.unknownType", "errors.unknownResource", "errors.operationOutcome");
        expect(r, WARN, "priorauth.search.use", "errors.strictHandling", "paging.total");
        expect(r, INFO, "paging.next");
        expect(r, SKIP, "smart.endpoints.consistent", "paging.next.sameHost");
        expect(r, PASS, "paging.count", "eob.search.id", "priorauth.decision", "priorauth.dates");
        assertThat(r.get("eob.search.patient").details()).anyMatch(d -> d.contains("EOBInpatient1"));
        assertThat(r.get("priorauth.denialReason").details()).anyMatch(d -> d.contains("PDexPriorAuthDenied"));
    }

    @Test
    void runWithoutPatientSkipsPatientChecks() {
        Map<String, CheckResult> r = byId(runner.runNow(open("no-patient"), null, List.of("discovery", "eob", "errors")));

        expect(r, PASS, "discovery.metadata", "errors.unknownType");
        expect(r, SKIP, "eob.search.patient", "eob.profileLite");
        assertThat(r.get("eob.search.patient").message()).contains("needs a patient");
    }
}
