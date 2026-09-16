package com.thehiddenbrain.interop.patientaccess.search;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import com.thehiddenbrain.interop.patientaccess.environment.IdentifierSystem;
import com.thehiddenbrain.interop.patientaccess.patient.PatientSummary;
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
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberSearchServiceTest {

    private static final String SYS_A = "http://plan.example.org/member-id";
    private static final String SYS_B = "http://hl7.org/fhir/sid/us-mbi";
    private static final String SYS_C = "http://plan.example.org/medicaid";

    @TempDir
    Path dir;
    WireMockServer wiremock;
    TestGraph g;
    Environment env;
    String base;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        base = wiremock.baseUrl() + "/fhir";
        g = new TestGraph(dir);
        env = g.environments.require(g.environments.create(new EnvironmentInput("search", null, EnvironmentTier.SANDBOX, base,
                new EnvironmentInput.AuthInput(AuthMode.NONE, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                List.of(), List.of(new IdentifierSystem("Member ID", SYS_A, "MB", true), new IdentifierSystem("MBI", SYS_B, "MC", true),
                        new IdentifierSystem("Medicaid", SYS_C, "MA", false)), FhirOptions.defaults(), null, null, true, null)).id());
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private static MemberSearchRequest request(String memberId, String system, String name, String birthDate, String gender, String id,
                                               Map<String, String> extra, Boolean coverageFallback, Integer count) {
        return new MemberSearchRequest(memberId, system, name, null, null, birthDate, gender, id, extra, coverageFallback, count);
    }

    private void stubPatientSearch(String identifier, ObjectNode bundle) {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).withQueryParam("identifier", equalTo(identifier)).willReturn(Fixtures.fhir(bundle)));
    }

    @Test
    void memberIdIsSearchedPerDefaultIdentifierSystemUntilTheFirstHit() {
        ObjectNode patient = Fixtures.demo("c4bb-Patient-Patient1");
        stubPatientSearch(SYS_A + "|M1", Fixtures.emptyBundle());
        stubPatientSearch(SYS_B + "|M1", Fixtures.bundle(patient));

        MemberSearchResult result = g.memberSearch.search(env, request(" M1 ", null, null, null, null, null, null, null, null));
        assertThat(result.patients()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("Patient1");
            assertThat(p.display()).isEqualTo("Johnny Example1");
            assertThat(p.foundBy()).isEqualTo("Patient by member id " + SYS_B + "|M1");
        });
        assertThat(result.queries()).hasSize(2);
        assertThat(result.queries().get(0).description()).isEqualTo("Patient by member id " + SYS_A + "|M1");
        assertThat(result.queries().get(0).url()).isEqualTo(base + "/Patient?identifier=" + SYS_A.replace("|", "%7C") + "%7CM1&_count=50");
        assertThat(result.queries().get(0).status()).isEqualTo(200);
        assertThat(result.queries().get(0).matches()).isZero();
        assertThat(result.queries().get(0).total()).isZero();
        assertThat(result.queries().get(0).requestId()).isNotBlank();
        assertThat(result.queries().get(1).matches()).isEqualTo(1);
        assertThat(result.queries().get(1).error()).isNull();
        assertThat(result.warnings()).isEmpty();
        // the non-default Medicaid system is never tried, and Coverage is not needed once a Patient matched
        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("identifier", equalTo(SYS_C + "|M1")));
        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/fhir/Coverage")));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("identifier", equalTo(SYS_A + "|M1")).withQueryParam("_count", equalTo("50")));
    }

    @Test
    void firstHitStopsFurtherIdentifierSystemsAndASelectedSystemIsUsedAlone() {
        stubPatientSearch(SYS_A + "|M2", Fixtures.bundle(Fixtures.demo("c4bb-Patient-Patient2")));
        MemberSearchResult result = g.memberSearch.search(env, request("M2", null, null, null, null, null, null, null, null));
        assertThat(result.patients()).extracting(PatientSummary::id).containsExactly("Patient2");
        assertThat(result.queries()).hasSize(1);
        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("identifier", equalTo(SYS_B + "|M2")));

        stubPatientSearch(SYS_C + "|M3", Fixtures.bundle(Fixtures.demo("c4bb-Patient-Patient1")));
        MemberSearchResult selected = g.memberSearch.search(env, request("M3", SYS_C, null, null, null, null, null, false, null));
        assertThat(selected.patients()).hasSize(1);
        assertThat(selected.queries()).singleElement().satisfies(q -> assertThat(q.description()).isEqualTo("Patient by member id " + SYS_C + "|M3"));
    }

    @Test
    void coverageFallbackResolvesBeneficiariesWhenNoPatientMatches() {
        stubPatientSearch(SYS_A + "|M9", Fixtures.emptyBundle());
        stubPatientSearch(SYS_B + "|M9", Fixtures.emptyBundle());
        ObjectNode coverage = Fixtures.demo("c4bb-Coverage-Coverage1");
        ObjectNode duplicate = Fixtures.demo("c4bb-Coverage-Coverage2");
        duplicate.putObject("beneficiary").put("reference", "Patient/Patient1");
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).withQueryParam("identifier", equalTo(SYS_A + "|M9")).willReturn(Fixtures.fhir(Fixtures.bundle(coverage, duplicate))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/Patient1")).willReturn(Fixtures.fhir(Fixtures.demo("c4bb-Patient-Patient1"))));

        MemberSearchResult result = g.memberSearch.search(env, request("M9", null, null, null, null, null, null, null, null));
        assertThat(result.patients()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("Patient1");
            assertThat(p.foundBy()).isEqualTo("Coverage.beneficiary Patient/Patient1");
        });
        assertThat(result.queries()).extracting(MemberSearchResult.Query::description).containsExactly(
                "Patient by member id " + SYS_A + "|M9", "Patient by member id " + SYS_B + "|M9",
                "Coverage by member id " + SYS_A + "|M9", "read Patient/Patient1 (Coverage beneficiary)");
        assertThat(result.queries().get(2).matches()).isEqualTo(2);
        assertThat(result.queries().get(3).url()).isEqualTo(base + "/Patient/Patient1");
        assertThat(result.queries().get(3).status()).isEqualTo(200);
        // beneficiaries were found through the first system, so the second Coverage search is skipped
        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/fhir/Coverage")).withQueryParam("identifier", equalTo(SYS_B + "|M9")));
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/fhir/Patient/Patient1")));

        MemberSearchResult noFallback = g.memberSearch.search(env, request("M9", null, null, null, null, null, null, false, null));
        assertThat(noFallback.patients()).isEmpty();
        assertThat(noFallback.queries()).hasSize(2);
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/fhir/Coverage")));
    }

    @Test
    void demographicsUseNameBirthdateAndGender() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).withQueryParam("name", equalTo("Example1")).willReturn(Fixtures.fhir(Fixtures.bundle(Fixtures.demo("c4bb-Patient-Patient1")))));

        MemberSearchResult result = g.memberSearch.search(env, request(null, null, " Example1 ", "1986-01-01", "Male", null, null, null, 10));
        assertThat(result.patients()).extracting(PatientSummary::birthDate).containsExactly("1986-01-01");
        assertThat(result.queries()).singleElement().satisfies(q -> {
            assertThat(q.description()).isEqualTo("Patient by demographics");
            assertThat(q.url()).isEqualTo(base + "/Patient?name=Example1&birthdate=1986-01-01&gender=male&_count=10");
        });
        assertThat(result.warnings()).isEmpty();
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("name", equalTo("Example1"))
                .withQueryParam("birthdate", equalTo("1986-01-01")).withQueryParam("gender", equalTo("male")).withQueryParam("_count", equalTo("10")));

        MemberSearchResult nameOnly = g.memberSearch.search(env, request(null, null, "Example1", null, null, null, null, null, null));
        assertThat(nameOnly.warnings()).singleElement().satisfies(w -> assertThat(w).contains("name alone"));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("name", equalTo("Example1")).withQueryParam("birthdate", absent())
                .withQueryParam("_count", equalTo("50")));

        MemberSearchRequest familyGiven = new MemberSearchRequest(null, null, null, "Example1", "Johnny", null, null, null, null, null, null);
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).withQueryParam("family", equalTo("Example1")).willReturn(Fixtures.fhir(Fixtures.emptyBundle())));
        assertThat(g.memberSearch.search(env, familyGiven).queries()).singleElement()
                .satisfies(q -> assertThat(q.url()).isEqualTo(base + "/Patient?family=Example1&given=Johnny&_count=50"));
    }

    @Test
    void logicalIdIsReadDirectlyAndAMissingPatientIsRecordedNotThrown() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/Patient1")).willReturn(Fixtures.fhir(Fixtures.demo("c4bb-Patient-Patient1"))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/nope")).willReturn(Fixtures.fhir(404, Fixtures.operationOutcome("error", "not-found", "unknown id"))));

        MemberSearchResult found = g.memberSearch.search(env, request(null, null, null, null, null, " Patient1 ", null, null, null));
        assertThat(found.patients()).extracting(PatientSummary::foundBy).containsExactly("Patient/Patient1");
        assertThat(found.queries()).singleElement().satisfies(q -> {
            assertThat(q.description()).isEqualTo("read Patient/Patient1");
            assertThat(q.url()).isEqualTo(base + "/Patient/Patient1");
            assertThat(q.matches()).isEqualTo(1);
            assertThat(q.error()).isNull();
        });

        MemberSearchResult missing = g.memberSearch.search(env, request(null, null, null, null, null, "nope", null, null, null));
        assertThat(missing.patients()).isEmpty();
        assertThat(missing.queries()).singleElement().satisfies(q -> {
            assertThat(q.status()).isEqualTo(404);
            assertThat(q.matches()).isZero();
            assertThat(q.error()).isEqualTo("error/not-found: unknown id");
        });
    }

    @Test
    void extraParametersAreSentAndUndeclaredOnesWarned() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).willReturn(Fixtures.fhir(Fixtures.emptyBundle())));
        MemberSearchResult result = g.memberSearch.search(env, request(null, null, null, null, null, null,
                Map.of("address-city", "Pittsburgh", "_lastUpdated", "ge2024-01-01"), null, null));
        assertThat(result.queries()).singleElement().satisfies(q -> assertThat(q.description()).isEqualTo("Patient by demographics"));
        assertThat(result.warnings()).singleElement().satisfies(w -> assertThat(w).contains("'address-city'").contains("not declared for Patient"));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("address-city", equalTo("Pittsburgh"))
                .withQueryParam("_lastUpdated", equalTo("ge2024-01-01")));

        // with a member id, extra parameters narrow the identifier search instead of running a separate query
        stubPatientSearch(SYS_A + "|M5", Fixtures.bundle(Fixtures.demo("c4bb-Patient-Patient1")));
        MemberSearchResult narrowed = g.memberSearch.search(env, request("M5", null, null, null, null, null, Map.of("birthdate", "1986-01-01"), null, null));
        assertThat(narrowed.queries()).hasSize(1);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("identifier", equalTo(SYS_A + "|M5")).withQueryParam("birthdate", equalTo("1986-01-01")));
    }

    @Test
    void serverErrorsAreRecordedPerQueryAndTheSearchContinues() {
        stubPatientSearch(SYS_A + "|M7", Fixtures.bundle(Fixtures.demo("c4bb-Patient-Patient2")));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).withQueryParam("name", equalTo("Broken"))
                .willReturn(Fixtures.fhir(500, Fixtures.operationOutcome("fatal", "exception", "database down"))));

        MemberSearchResult result = g.memberSearch.search(env, request("M7", null, "Broken", null, null, null, null, null, null));
        assertThat(result.patients()).extracting(PatientSummary::id).containsExactly("Patient2");
        assertThat(result.queries()).hasSize(2);
        MemberSearchResult.Query failed = result.queries().get(1);
        assertThat(failed.description()).isEqualTo("Patient by demographics");
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.matches()).isZero();
        assertThat(failed.error()).contains("answered 500").contains("database down");
        assertThat(failed.requestId()).isNotBlank();
        assertThat(failed.url()).contains("name=Broken");

        Environment dead = g.openEnvironment("dead", "http://127.0.0.1:1/fhir");
        MemberSearchResult unreachable = g.memberSearch.search(dead, request(null, null, "x", null, null, null, null, null, null));
        assertThat(unreachable.patients()).isEmpty();
        assertThat(unreachable.queries()).singleElement().satisfies(q -> {
            assertThat(q.status()).isNull();
            assertThat(q.error()).contains("failed");
            assertThat(q.url()).startsWith("http://127.0.0.1:1/fhir/Patient?name=x");
        });
        assertThat(unreachable.warnings()).singleElement().satisfies(w -> assertThat(w).contains("name alone"));
    }

    @Test
    void emptyRequestsAreRejectedAndEnvironmentsWithoutSystemsSearchTheBareValue() {
        assertThatThrownBy(() -> g.memberSearch.search(env, request(" ", null, null, null, null, null, Map.of(), null, null)))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> g.memberSearch.search(env, null)).isInstanceOf(WorkbenchException.class);

        Environment bare = g.environments.require(g.environments.create(new EnvironmentInput("bare", null, EnvironmentTier.SANDBOX, base,
                new EnvironmentInput.AuthInput(AuthMode.NONE, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                List.of(), List.of(), FhirOptions.defaults(), null, null, true, null)).id());
        stubPatientSearch("M8", Fixtures.bundle(Fixtures.demo("c4bb-Patient-Patient1")));
        MemberSearchResult result = g.memberSearch.search(bare, request("M8", null, null, null, null, null, null, false, null));
        assertThat(result.patients()).hasSize(1);
        assertThat(result.warnings()).singleElement().satisfies(w -> assertThat(w).contains("no identifier systems configured"));
    }
}
