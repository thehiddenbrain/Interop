package com.thehiddenbrain.interop.patientaccess.auth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class SmartDiscoveryServiceTest {

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
        env = g.openEnvironment("wm", base);
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private static String smartConfiguration(String as) {
        return "{\"issuer\":\"" + as + "\",\"authorization_endpoint\":\"" + as + "/authorize\",\"token_endpoint\":\"" + as + "/token\","
                + "\"jwks_uri\":\"" + as + "/jwks\",\"capabilities\":[\"launch-standalone\",\"permission-patient\"],"
                + "\"code_challenge_methods_supported\":[\"S256\"],\"scopes_supported\":[\"openid\",\"patient/*.rs\"],"
                + "\"grant_types_supported\":[\"authorization_code\"],\"token_endpoint_auth_methods_supported\":[\"client_secret_basic\"]}";
    }

    private static ObjectNode capabilityStatement(String as) {
        ObjectNode cs = Fixtures.resource("CapabilityStatement", "cs");
        cs.put("fhirVersion", "4.0.1");
        ObjectNode ext = cs.putArray("rest").addObject().putObject("security").putArray("extension").addObject()
                .put("url", SmartDiscoveryService.OAUTH_URIS_EXTENSION);
        ext.putArray("extension")
                .add(Fixtures.MAPPER.createObjectNode().put("url", "authorize").put("valueUri", as + "/cs-authorize"))
                .add(Fixtures.MAPPER.createObjectNode().put("url", "token").put("valueUri", as + "/cs-token"))
                .add(Fixtures.MAPPER.createObjectNode().put("url", "register").put("valueUri", as + "/cs-register"));
        return cs;
    }

    @Test
    void parsesTheWellKnownDocumentAndTheCapabilityStatement() {
        String as = wiremock.baseUrl() + "/as";
        wiremock.stubFor(get(urlPathEqualTo("/fhir/.well-known/smart-configuration")).willReturn(Fixtures.json(200, smartConfiguration(as))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(capabilityStatement(as))));

        SmartDiscoveryService.Discovery d = g.discovery.discover(env, false, "corr");
        assertThat(d.smartConfiguration()).isNotNull();
        assertThat(d.smartConfigurationStatus()).isEqualTo(200);
        assertThat(d.smartConfigurationUrl()).isEqualTo(base + "/.well-known/smart-configuration");
        assertThat(d.capabilityStatement().path("fhirVersion").asText()).isEqualTo("4.0.1");
        assertThat(d.metadataStatus()).isEqualTo(200);
        assertThat(d.metadataRequestId()).isNotBlank();
        assertThat(d.notes()).isEmpty();
        OAuthEndpoints e = d.endpoints();
        assertThat(e.authorizationEndpoint()).isEqualTo(as + "/authorize");
        assertThat(e.tokenEndpoint()).isEqualTo(as + "/token");
        assertThat(e.issuer()).isEqualTo(as);
        assertThat(e.jwksUri()).isEqualTo(as + "/jwks");
        assertThat(e.capabilities()).containsExactly("launch-standalone", "permission-patient");
        assertThat(e.codeChallengeMethods()).containsExactly("S256");
        assertThat(e.scopesSupported()).contains("patient/*.rs");
        assertThat(e.grantTypesSupported()).containsExactly("authorization_code");
        assertThat(e.tokenEndpointAuthMethods()).containsExactly("client_secret_basic");
        assertThat(e.source()).isEqualTo("smart-configuration");
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/.well-known/smart-configuration")).withHeader("Accept", equalTo("application/json, application/fhir+json")));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/metadata")).withHeader("Accept", equalTo("application/fhir+json")));
        assertThat(g.history.list(env.id(), "discovery", "corr", 10)).hasSize(2);
    }

    @Test
    void fallsBackToTheOauthUrisExtensionOfTheCapabilityStatement() {
        String as = wiremock.baseUrl() + "/as";
        wiremock.stubFor(get(urlPathEqualTo("/fhir/.well-known/smart-configuration")).willReturn(aResponse().withStatus(404).withBody("no")));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(capabilityStatement(as))));

        SmartDiscoveryService.Discovery d = g.discovery.discover(env, false, null);
        assertThat(d.smartConfiguration()).isNull();
        assertThat(d.smartConfigurationStatus()).isEqualTo(404);
        assertThat(d.notes()).containsExactly("smart-configuration: HTTP 404 (not JSON)");
        assertThat(d.endpoints().authorizationEndpoint()).isEqualTo(as + "/cs-authorize");
        assertThat(d.endpoints().tokenEndpoint()).isEqualTo(as + "/cs-token");
        assertThat(d.endpoints().registrationEndpoint()).isEqualTo(as + "/cs-register");
        assertThat(d.endpoints().source()).isEqualTo("capabilitystatement");
        assertThat(d.endpoints().capabilities()).isEmpty();
    }

    @Test
    void recordsNotesWhenNothingCanBeDiscovered() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/.well-known/smart-configuration")).willReturn(aResponse().withStatus(500).withBody("boom")));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(Fixtures.operationOutcome("error", "exception", "down"))));

        SmartDiscoveryService.Discovery d = g.discovery.discover(env, false, null);
        assertThat(d.smartConfiguration()).isNull();
        assertThat(d.capabilityStatement()).isNull();
        assertThat(d.endpoints().authorizationEndpoint()).isNull();
        assertThat(d.endpoints().tokenEndpoint()).isNull();
        assertThat(d.endpoints().source()).isEqualTo("none");
        assertThat(d.notes()).containsExactly("smart-configuration: HTTP 500 (not JSON)", "metadata: HTTP 200 OperationOutcome",
                "no OAuth endpoints found in smart-configuration or CapabilityStatement.rest.security");

        Environment dead = g.openEnvironment("dead", "http://127.0.0.1:1/fhir");
        SmartDiscoveryService.Discovery unreachable = g.discovery.discover(dead, false, null);
        assertThat(unreachable.smartConfigurationStatus()).isNull();
        assertThat(unreachable.metadataStatus()).isNull();
        assertThat(unreachable.notes()).hasSize(3).satisfies(n -> {
            assertThat(n.get(0)).startsWith("smart-configuration: GET http://127.0.0.1:1/fhir/.well-known/smart-configuration failed");
            assertThat(n.get(1)).startsWith("metadata: GET http://127.0.0.1:1/fhir/metadata failed");
        });
    }

    @Test
    void cachesPerEnvironmentVersionUntilRefreshedEvictedOrExpired() {
        String as = wiremock.baseUrl() + "/as";
        wiremock.stubFor(get(urlPathEqualTo("/fhir/.well-known/smart-configuration")).willReturn(Fixtures.json(200, smartConfiguration(as))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(capabilityStatement(as))));

        g.discovery.discover(env, false, null);
        g.discovery.discover(env, false, null);
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/fhir/metadata")));

        g.discovery.discover(env, true, null);
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/fhir/metadata")));

        g.environments.update(env.id(), new EnvironmentInput(null, null, null, null, null, null, null, null, null, null, "edited", null, null));
        Environment edited = g.environments.require(env.id());
        assertThat(edited.version()).isEqualTo(2);
        g.discovery.discover(edited, false, null);
        wiremock.verify(3, getRequestedFor(urlPathEqualTo("/fhir/metadata")));

        g.discovery.evict(env.id());
        g.discovery.discover(edited, false, null);
        wiremock.verify(4, getRequestedFor(urlPathEqualTo("/fhir/metadata")));

        g.clock.advance(Duration.ofMinutes(11));
        g.discovery.discover(edited, false, null);
        wiremock.verify(5, getRequestedFor(urlPathEqualTo("/fhir/metadata")));
    }

    @Test
    void configuredEndpointsWinAndDiscoveryFillsTheGaps() {
        String as = wiremock.baseUrl() + "/as";
        wiremock.stubFor(get(urlPathEqualTo("/fhir/.well-known/smart-configuration")).willReturn(Fixtures.json(200, smartConfiguration(as))));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(capabilityStatement(as))));

        Environment partly = g.environment("partly", base, new EnvironmentInput.AuthInput(AuthMode.SMART_AUTHORIZATION_CODE, true, null,
                "https://configured.example.org/token", "app", null, null, null, null, null, null, null, null, null, Map.of(), Map.of()), List.of());
        OAuthEndpoints e = g.discovery.endpointsFor(partly, null);
        assertThat(e.tokenEndpoint()).isEqualTo("https://configured.example.org/token");
        assertThat(e.authorizationEndpoint()).isEqualTo(as + "/authorize");
        assertThat(e.source()).isEqualTo("configured+smart-configuration");
        assertThat(e.issuer()).isEqualTo(as);

        Environment fixed = g.environment("fixed", base, new EnvironmentInput.AuthInput(AuthMode.SMART_AUTHORIZATION_CODE, false,
                "https://configured.example.org/authorize", "https://configured.example.org/token", "app", null, null, null, null, null, null, null,
                null, null, Map.of(), Map.of()), List.of());
        OAuthEndpoints c = g.discovery.endpointsFor(fixed, null);
        assertThat(c.authorizationEndpoint()).isEqualTo("https://configured.example.org/authorize");
        assertThat(c.source()).isEqualTo("configured");
        assertThat(c.capabilities()).isEmpty();
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/fhir/metadata")));

        Environment dead = g.environment("dead", "http://127.0.0.1:1/fhir", new EnvironmentInput.AuthInput(AuthMode.SMART_AUTHORIZATION_CODE, true,
                null, null, "app", null, null, null, null, null, null, null, null, null, Map.of(), Map.of()), List.of());
        OAuthEndpoints none = g.discovery.endpointsFor(dead, null);
        assertThat(none.tokenEndpoint()).isNull();
        assertThat(none.authorizationEndpoint()).isNull();
        assertThat(none.source()).isEqualTo("none");
    }
}
