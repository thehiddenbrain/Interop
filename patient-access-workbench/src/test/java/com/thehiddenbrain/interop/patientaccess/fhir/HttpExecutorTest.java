package com.thehiddenbrain.interop.patientaccess.fhir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import com.thehiddenbrain.interop.patientaccess.history.RequestRecord;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpExecutorTest {

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

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void recordsTimingHeadersAndRedactedEntryInTheHistory() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/metadata")).willReturn(Fixtures.fhir(Fixtures.resource("CapabilityStatement", "cs1"))
                .withHeader("Set-Cookie", "session=abc").withHeader("X-Request-Id", "srv-1")));

        HttpResult r = g.http.execute(env, HttpExecutor.Call.get(base + "/metadata",
                headers("Accept", "application/fhir+json", "Authorization", "Bearer very-secret-token", "X-Api-Key", "key-123", "X-Trace", "t1"),
                "discovery", "corr-1", Set.of("x-api-key")));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.ok()).isTrue();
        assertThat(r.resourceType()).isEqualTo("CapabilityStatement");
        assertThat(r.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(r.requestId()).hasSize(20);
        assertThat(r.header("x-request-id")).isEqualTo("srv-1");
        wiremock.verify(getRequestedFor(urlPathEqualTo("/fhir/metadata"))
                .withHeader("User-Agent", equalTo("paw-test"))
                .withHeader("Accept", equalTo("application/fhir+json"))
                .withHeader("Authorization", equalTo("Bearer very-secret-token"))
                .withHeader("X-Api-Key", equalTo("key-123")));

        assertThat(g.history.size()).isEqualTo(1);
        RequestRecord rec = g.history.get(r.requestId()).orElseThrow();
        assertThat(rec.environmentId()).isEqualTo(env.id());
        assertThat(rec.environmentName()).isEqualTo("wm");
        assertThat(rec.purpose()).isEqualTo("discovery");
        assertThat(rec.correlationId()).isEqualTo("corr-1");
        assertThat(rec.method()).isEqualTo("GET");
        assertThat(rec.url()).isEqualTo(base + "/metadata");
        assertThat(rec.at()).isEqualTo(g.clock.instant());
        assertThat(rec.status()).isEqualTo(200);
        assertThat(rec.durationMs()).isEqualTo(r.durationMs());
        assertThat(rec.error()).isNull();
        assertThat(rec.summary()).isEqualTo("CapabilityStatement/cs1");
        assertThat(rec.requestHeaders()).containsEntry("User-Agent", "paw-test").containsEntry("Authorization", "Bearer ***")
                .containsEntry("X-Api-Key", "***").containsEntry("X-Trace", "t1");
        // java.net.http lower-cases response header names
        assertThat(rec.responseHeaders()).containsEntry("set-cookie", List.of("***")).containsEntry("x-request-id", List.of("srv-1"));
        assertThat(rec.responseBody()).contains("CapabilityStatement");
        assertThat(rec.responseBodyTruncated()).isFalse();
    }

    @Test
    void truncatesLongBodiesInTheHistoryButNotInTheResult() {
        WorkbenchProperties small = new WorkbenchProperties(dir.toString(), "", "", new WorkbenchProperties.Ui(true), new WorkbenchProperties.Demo(false),
                new WorkbenchProperties.Security(new WorkbenchProperties.Security.Basic(false, "workbench", "")),
                new WorkbenchProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(10), "paw-test", 0),
                new WorkbenchProperties.History(200, 100, false), new WorkbenchProperties.Search(50, 5),
                new WorkbenchProperties.Conformance(3, 2, 3000, 10000), new WorkbenchProperties.Validation(dir.resolve("packages").toString()));
        TestGraph tiny = new TestGraph(dir.resolve("tiny"), small);
        Environment e = tiny.openEnvironment("tiny", base);
        String body = "{\"resourceType\":\"Patient\",\"id\":\"" + "x".repeat(300) + "\"}";
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/long")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", Fixtures.FHIR_JSON).withBody(body)));

        HttpResult r = tiny.http.execute(e, HttpExecutor.Call.get(base + "/Patient/long", Map.of(), "read", null, Set.of()));
        assertThat(r.body()).isEqualTo(body);
        RequestRecord rec = tiny.history.get(r.requestId()).orElseThrow();
        assertThat(rec.responseBodyTruncated()).isTrue();
        assertThat(rec.responseBody()).hasSize(100).isEqualTo(body.substring(0, 100));
    }

    @Test
    void retriesOnceOn429HonouringRetryAfter() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).inScenario("throttle").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0").withBody("slow down")).willSetStateTo("ok"));
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient")).inScenario("throttle").whenScenarioStateIs("ok")
                .willReturn(Fixtures.fhir(Fixtures.emptyBundle())));

        HttpResult r = g.http.execute(env, HttpExecutor.Call.get(base + "/Patient", Map.of(), "search", "c", Set.of()));
        assertThat(r.status()).isEqualTo(200);
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/fhir/Patient")));
        assertThat(g.history.list(null, null, "c", 10)).extracting(RequestRecord.Summary::status).containsExactly(200, 429);

        assertThat(HttpExecutor.retryAfterMs("2")).isEqualTo(2000);
        assertThat(HttpExecutor.retryAfterMs("0")).isEqualTo(250);
        assertThat(HttpExecutor.retryAfterMs("600")).isEqualTo(10_000);
        assertThat(HttpExecutor.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(1000);
        assertThat(HttpExecutor.retryAfterMs(null)).isEqualTo(1000);
    }

    @Test
    void givesUpAfterTheConfiguredRetries() {
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Coverage")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));
        HttpResult r = g.http.execute(env, HttpExecutor.Call.get(base + "/Coverage", Map.of(), "search", null, Set.of()));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.ok()).isFalse();
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/fhir/Coverage")));
    }

    @Test
    void timeoutBecomesUpstreamUnreachableWithAHistoryEntry() {
        FhirOptions quick = new FhirOptions(null, null, null, true, false, false, null, 200, false);
        Environment slowEnv = g.environments.require(g.environments.create(new EnvironmentInput("slow", null, EnvironmentTier.SANDBOX, base,
                new EnvironmentInput.AuthInput(AuthMode.NONE, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                List.of(), List.of(), quick, null, null, null, true, null)).id());
        wiremock.stubFor(get(urlPathEqualTo("/fhir/Patient/slow")).willReturn(Fixtures.fhir(Fixtures.resource("Patient", "slow")).withFixedDelay(1500)));

        assertThatThrownBy(() -> g.http.execute(slowEnv, HttpExecutor.Call.get(base + "/Patient/slow", Map.of(), "read", "t", Set.of())))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> {
                    WorkbenchException e = (WorkbenchException) t;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.UPSTREAM_UNREACHABLE);
                    assertThat(e.getMessage()).contains("timeout after 200 ms");
                    assertThat(e.getUpstream().url()).isEqualTo(base + "/Patient/slow");
                    assertThat(e.getUpstream().httpStatus()).isNull();
                    RequestRecord rec = g.history.get(e.getUpstream().requestId()).orElseThrow();
                    assertThat(rec.error()).contains("timeout");
                    assertThat(rec.status()).isNull();
                    assertThat(rec.purpose()).isEqualTo("read");
                });
    }

    @Test
    void connectionRefusedIsUpstreamUnreachable() {
        Environment dead = g.openEnvironment("dead", "http://127.0.0.1:1/fhir");
        assertThatThrownBy(() -> g.http.execute(dead, HttpExecutor.Call.get("http://127.0.0.1:1/fhir/metadata", Map.of(), "discovery", null, Set.of())))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.UPSTREAM_UNREACHABLE));
        assertThat(g.history.list(dead.id(), null, null, 10)).singleElement().satisfies(s -> assertThat(s.error()).isNotBlank());
    }

    @Test
    void formPostsAreSentAndTokenFieldsAreRedactedInBothDirections() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200,
                "{\"access_token\":\"AT-SECRET\",\"refresh_token\":\"RT-SECRET\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        Environment tokenEnv = g.openEnvironment("token", wiremock.baseUrl() + "/oauth");

        HttpResult r = g.http.execute(tokenEnv, HttpExecutor.Call.postForm(wiremock.baseUrl() + "/oauth/token", headers("Accept", "application/json"),
                "grant_type=client_credentials&client_id=c&client_secret=S3CRET&scope=system%2F*.rs", "auth", null, Set.of()));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("access_token").asText()).isEqualTo("AT-SECRET");
        wiremock.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("grant_type=client_credentials&client_id=c&client_secret=S3CRET&scope=system%2F*.rs")));

        RequestRecord rec = g.history.get(r.requestId()).orElseThrow();
        assertThat(rec.method()).isEqualTo("POST");
        assertThat(rec.requestBody()).isEqualTo("grant_type=client_credentials&client_id=c&client_secret=***&scope=system%2F*.rs");
        assertThat(rec.responseBody()).doesNotContain("AT-SECRET").doesNotContain("RT-SECRET").contains("\"access_token\":\"***\"");
    }

    @Test
    void summarizesBundlesAndOperationOutcomes() {
        assertThat(HttpExecutor.summarize(new HttpResult("u", 200, Map.of(), Fixtures.json(Fixtures.bundle(Fixtures.resource("Patient", "1"))), 1, "i")))
                .isEqualTo("Bundle searchset: 1 entries of 1");
        assertThat(HttpExecutor.summarize(new HttpResult("u", 404, Map.of(), Fixtures.json(Fixtures.operationOutcome("error", "not-found", "gone")), 1, "i")))
                .isEqualTo("OperationOutcome: error/not-found: gone");
        assertThat(HttpExecutor.summarize(new HttpResult("u", 200, Map.of(), "<html/>", 1, "i"))).isNull();
        assertThat(HttpExecutor.charsetOf("application/fhir+json; charset=ISO-8859-1").name()).isEqualTo("ISO-8859-1");
        assertThat(HttpExecutor.charsetOf("application/fhir+json; charset=\"bogus-cs\"").name()).isEqualTo("UTF-8");
        assertThat(HttpExecutor.charsetOf(null).name()).isEqualTo("UTF-8");
    }
}
