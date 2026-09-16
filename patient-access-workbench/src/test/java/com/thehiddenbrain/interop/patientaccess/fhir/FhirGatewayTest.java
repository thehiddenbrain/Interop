package com.thehiddenbrain.interop.patientaccess.fhir;

import tools.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FhirGatewayTest {

    @TempDir
    Path dir;
    WireMockServer wiremock;
    TestGraph g;
    Environment env;
    String base;
    FhirGateway.Options options = FhirGateway.Options.of(FhirGateway.PURPOSE_SEARCH, "corr");

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        base = wiremock.baseUrl() + "/fhir";
        g = new TestGraph(dir);
        env = g.openEnvironment("wm", base);
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private static MultiValueMap<String, String> params(String... kv) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.add(kv[i], kv[i + 1]);
        }
        return p;
    }

    private Environment envWith(String name, FhirOptions fhir, EnvironmentInput.AuthInput auth, List<EnvironmentInput.HeaderInput> headers) {
        return g.environments.require(g.environments.create(new EnvironmentInput(name, null, EnvironmentTier.SANDBOX, base,
                auth == null ? new EnvironmentInput.AuthInput(AuthMode.NONE, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null) : auth,
                headers, List.of(), fhir, null, null, null, true, null)).id());
    }

    @Test
    void searchAddsCountUnlessGivenOrDisabled() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).willReturn(Fixtures.fhir(Fixtures.bundle(Fixtures.resource("Patient", "1")))));

        SearchPage page = g.gateway.search(env, "Patient", params("name", "Smith"), options);
        assertThat(page.count()).isEqualTo(1);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("name", equalTo("Smith")).withQueryParam("_count", equalTo("50")));

        g.gateway.search(env, "Patient", params("_count", "5"), options);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("_count", equalTo("5")));

        Environment noCount = envWith("nocount", new FhirOptions(null, null, null, false, false, false, null, null, false), null, null);
        g.gateway.search(noCount, "Patient", params("name", "NoCount"), options);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("name", equalTo("NoCount")).withQueryParam("_count", absent()));

        Environment pageSize = envWith("pagesize", new FhirOptions(7, null, null, true, false, false, null, null, false), null, null);
        assertThat(g.gateway.pageSize(pageSize)).isEqualTo(7);
        g.gateway.search(pageSize, "Patient", null, options);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("_count", equalTo("7")));
    }

    @Test
    void searchErrorsCarryTheUpstreamBody() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).willReturn(Fixtures.fhir(400, Fixtures.operationOutcome("error", "invalid", "bad param"))));
        assertThatThrownBy(() -> g.gateway.search(env, "Patient", params("foo", "bar"), options))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> {
                    WorkbenchException e = (WorkbenchException) t;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
                    assertThat(e.getMessage()).contains("answered 400").contains("error/invalid: bad param");
                    assertThat(e.getUpstream().httpStatus()).isEqualTo(400);
                    assertThat(e.getUpstream().body()).contains("bad param");
                    assertThat(e.getUpstream().requestId()).isNotBlank();
                });

        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).willReturn(Fixtures.fhir(Fixtures.resource("Coverage", "not-a-bundle"))));
        assertThatThrownBy(() -> g.gateway.search(env, "Coverage", null, options)).isInstanceOf(WorkbenchException.class)
                .hasMessageContaining("did not return a Bundle (got Coverage)");
    }

    @Test
    void pageFollowsLinksOnlyInsideTheEnvironment() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).willReturn(Fixtures.fhir(Fixtures.bundle(Fixtures.resource("Patient", "2")))));
        SearchPage page = g.gateway.page(env, base + "/Patient?_getpages=abc&_getpagesoffset=50", options);
        assertThat(page.resources()).hasSize(1);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/Patient")).withQueryParam("_getpages", equalTo("abc")));

        assertThatThrownBy(() -> g.gateway.page(env, "http://evil.example.org/fhir/Patient?page=2", options))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.TARGET_NOT_ALLOWED));
        assertThatThrownBy(() -> g.gateway.page(env, wiremock.baseUrl() + "/admin/Patient", options))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("outside the FHIR base path");
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/fhir/Patient")));
    }

    @Test
    void searchAllFollowsNextLinksUpToMaxPagesAndFlagsTruncation() {
        String page2 = base + "/Patient?page=2";
        String page3 = base + "/Patient?page=3";
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).withQueryParam("page", absent())
                .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(Fixtures.resource("Patient", "a")), List.of(Fixtures.resource("Organization", "o1")), 3, page2))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).withQueryParam("page", equalTo("2"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(Fixtures.resource("Patient", "b")), List.of(), 3, page3))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).withQueryParam("page", equalTo("3"))
                .willReturn(Fixtures.fhir(Fixtures.bundle(List.of(Fixtures.resource("Patient", "c")), List.of(), 3, null))));

        FhirGateway.Collected limited = g.gateway.searchAll(env, "Patient", params("name", "x"), options, 2);
        assertThat(limited.pages()).isEqualTo(2);
        assertThat(limited.truncated()).isTrue();
        assertThat(limited.total()).isEqualTo(3);
        assertThat(limited.resources()).extracting(r -> r.path("id").asString("")).containsExactly("a", "b");
        assertThat(limited.included()).extracting(r -> r.path("id").asString("")).containsExactly("o1");
        assertThat(limited.requestIds()).hasSize(2).doesNotContainNull();

        // no explicit limit: the environment's maxPages, else paw.search.max-pages (5 in the test graph)
        FhirGateway.Collected all = g.gateway.searchAll(env, "Patient", params("name", "x"), options, null);
        assertThat(all.pages()).isEqualTo(3);
        assertThat(all.truncated()).isFalse();
        assertThat(all.resources()).extracting(r -> r.path("id").asString("")).containsExactly("a", "b", "c");

        Environment onePage = envWith("onepage", new FhirOptions(null, 1, null, true, false, false, null, null, false), null, null);
        FhirGateway.Collected single = g.gateway.searchAll(onePage, "Patient", params("name", "x"), options, null);
        assertThat(single.pages()).isEqualTo(1);
        assertThat(single.truncated()).isTrue();
    }

    @Test
    void readReturnsJsonOrFailsWithTheUpstreamBody() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/p1")).willReturn(Fixtures.fhir(Fixtures.resource("Patient", "p1"))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/html")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody("<html>login page</html>")));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/missing")).willReturn(Fixtures.fhir(404, Fixtures.operationOutcome("error", "not-found", "no Patient/missing"))));

        JsonNode patient = g.gateway.read(env, "Patient", "p1", options);
        assertThat(patient.path("id").asString("")).isEqualTo("p1");

        assertThatThrownBy(() -> g.gateway.read(env, "Patient", "html", options))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> {
                    WorkbenchException e = (WorkbenchException) t;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
                    assertThat(e.getMessage()).contains("is not JSON");
                    assertThat(e.getUpstream().httpStatus()).isEqualTo(200);
                    assertThat(e.getUpstream().body()).isEqualTo("<html>login page</html>");
                });
        assertThatThrownBy(() -> g.gateway.read(env, "Patient", "missing", options))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> {
                    WorkbenchException e = (WorkbenchException) t;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
                    assertThat(e.getUpstream().httpStatus()).isEqualTo(404);
                    assertThat(e.getMessage()).contains("no Patient/missing");
                });
        HttpResult raw = g.gateway.get(env, "Patient/missing", options);
        assertThat(raw.status()).isEqualTo(404);
        assertThatThrownBy(() -> g.gateway.getOrThrow(env, "Patient/missing", options)).isInstanceOf(WorkbenchException.class);
    }

    @Test
    void unauthenticatedOptionSendsNoBearerToken() {
        Environment tokenEnv = envWith("static", FhirOptions.defaults(), new EnvironmentInput.AuthInput(AuthMode.STATIC_TOKEN, false, null, null, null, null,
                null, null, null, "static-token-value-xyz", null, null, null, null, null, null), null);
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(Fixtures.resource("CapabilityStatement", "cs"))));

        g.gateway.get(tokenEnv, "metadata", options);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/metadata")).withHeader("Authorization", equalTo("Bearer static-token-value-xyz")));

        g.gateway.get(tokenEnv, "metadata", options.unauthenticated());
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/metadata")).withoutHeader("Authorization"));
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/fhir/metadata")));
        assertThat(g.gateway.secretHeaderNames(tokenEnv)).isEmpty();
    }

    @Test
    void headerOverridesReplaceOrRemoveHeaders() {
        Environment withHeaders = envWith("headers", new FhirOptions(null, null, "application/json+fhir", true, false, false, null, null, true), null,
                List.of(new EnvironmentInput.HeaderInput("X-Api-Key", "api-key-value-1234", true), new EnvironmentInput.HeaderInput("X-Trace", "t", false)));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(Fixtures.resource("CapabilityStatement", "cs"))));

        g.gateway.get(withHeaders, "metadata", options);
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/metadata"))
                .withHeader("Accept", equalTo("application/json+fhir"))
                .withHeader("Prefer", equalTo("handling=lenient"))
                .withHeader("X-Api-Key", equalTo("api-key-value-1234"))
                .withHeader("X-Trace", equalTo("t")));
        assertThat(g.gateway.secretHeaderNames(withHeaders)).containsExactly("x-api-key");

        Map<String, String> overrides = new HashMap<>();
        overrides.put("Accept", "application/xml");
        overrides.put("X-Trace", null);
        overrides.put("If-None-Match", "W/\"1\"");
        g.gateway.get(withHeaders, "metadata", options.withHeaders(overrides));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/metadata"))
                .withHeader("Accept", equalTo("application/xml"))
                .withHeader("If-None-Match", equalTo("W/\"1\""))
                .withHeader("X-Api-Key", equalTo("api-key-value-1234"))
                .withoutHeader("X-Trace"));
        assertThat(g.history.list(withHeaders.id(), null, null, 10)).hasSize(2);
    }
}
