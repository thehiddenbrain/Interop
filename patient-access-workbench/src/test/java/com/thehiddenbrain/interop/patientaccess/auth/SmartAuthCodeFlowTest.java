package com.thehiddenbrain.interop.patientaccess.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.ClientAuthMethod;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import com.thehiddenbrain.interop.patientaccess.support.Forms;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartAuthCodeFlowTest {

    private static final String REQUEST_BASE = "http://localhost:8090";
    private static final String LOGIN_RESPONSE = "{\"access_token\":\"AT-code\",\"refresh_token\":\"RT-code\",\"token_type\":\"Bearer\","
            + "\"expires_in\":3600,\"scope\":\"launch/patient patient/*.rs offline_access\",\"patient\":\"123\"}";

    @TempDir
    Path dir;
    WireMockServer wiremock;
    TestGraph g;
    String base;
    String authorizeUrl;
    String tokenUrl;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        base = wiremock.baseUrl() + "/fhir";
        authorizeUrl = wiremock.baseUrl() + "/oauth/authorize";
        tokenUrl = wiremock.baseUrl() + "/oauth/token";
        g = new TestGraph(dir);
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private Environment smartEnv(String name, String secret, String scopes, String audience, String redirectUri, Map<String, String> extraAuthorize) {
        return g.environment(name, base, new EnvironmentInput.AuthInput(AuthMode.SMART_AUTHORIZATION_CODE, false, authorizeUrl, tokenUrl, "app-client",
                secret, ClientAuthMethod.CLIENT_SECRET_BASIC, scopes, audience, null, null, null, redirectUri, true, Map.of(), extraAuthorize), List.of());
    }

    private Map<String, String> onlyTokenRequestForm() {
        List<LoggedRequest> requests = wiremock.findAll(postRequestedFor(urlPathEqualTo("/oauth/token")));
        assertThat(requests).hasSize(1);
        return Forms.parse(requests.get(0).getBodyAsString());
    }

    @Test
    void startBuildsAnAuthorizeUrlWithStateAndPkce() {
        Environment env = smartEnv("smart", null, "launch/patient patient/*.rs", null, null, Map.of("prompt", "login"));
        String url = g.smartFlow.start(env, REQUEST_BASE + "/", "member@example.org");

        assertThat(url).startsWith(authorizeUrl + "?");
        Map<String, String> q = Forms.query(url);
        assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", "app-client")
                .containsEntry("redirect_uri", REQUEST_BASE + "/oauth/callback").containsEntry("scope", "launch/patient patient/*.rs")
                .containsEntry("aud", base).containsEntry("code_challenge_method", "S256").containsEntry("login_hint", "member@example.org")
                .containsEntry("prompt", "login");
        assertThat(q.get("state")).hasSizeGreaterThanOrEqualTo(32);
        assertThat(q.get("code_challenge")).hasSize(43);
        assertThat(g.smartFlow.redirectUri(env, REQUEST_BASE)).isEqualTo(REQUEST_BASE + "/oauth/callback");

        Environment defaults = smartEnv("defaults", null, null, "https://aud.example.org", "https://public.example.org/cb", Map.of());
        Map<String, String> d = Forms.query(g.smartFlow.start(defaults, REQUEST_BASE, null));
        assertThat(d).containsEntry("scope", "launch/patient openid fhirUser offline_access patient/*.rs")
                .containsEntry("aud", "https://aud.example.org").containsEntry("redirect_uri", "https://public.example.org/cb").doesNotContainKey("login_hint");
        assertThat(d.get("state")).isNotEqualTo(q.get("state"));
    }

    @Test
    void redirectUriPrefersThePublicBaseUrlProperty() {
        WorkbenchProperties props = new WorkbenchProperties(dir.toString(), "", "https://workbench.example.org/", "vendors.yaml", new WorkbenchProperties.Ui(true),
                new WorkbenchProperties.Demo(false), new WorkbenchProperties.Security(new WorkbenchProperties.Security.Basic(false, "workbench", "")),
                new WorkbenchProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(10), "paw-test", 1), new WorkbenchProperties.History(200, 65536, false),
                new WorkbenchProperties.Search(50, 5), new WorkbenchProperties.Conformance(3, 2, 3000, 10000), new WorkbenchProperties.Validation(dir.toString()));
        TestGraph pub = new TestGraph(dir.resolve("pub"), props);
        Environment env = pub.environment("smart", base, new EnvironmentInput.AuthInput(AuthMode.SMART_AUTHORIZATION_CODE, false, authorizeUrl, tokenUrl,
                "app", null, null, null, null, null, null, null, null, null, Map.of(), Map.of()), List.of());
        assertThat(pub.smartFlow.redirectUri(env, REQUEST_BASE)).isEqualTo("https://workbench.example.org/oauth/callback");
        assertThat(Forms.query(pub.smartFlow.start(env, REQUEST_BASE, null))).containsEntry("redirect_uri", "https://workbench.example.org/oauth/callback");
    }

    @Test
    void startRefusesOtherAuthModesAndMissingAuthorizeEndpoints() {
        Environment open = g.openEnvironment("open", base);
        assertThatThrownBy(() -> g.smartFlow.start(open, REQUEST_BASE, null)).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.NOT_SUPPORTED));
        Environment undiscoverable = g.environment("nodisc", "http://127.0.0.1:1/fhir", new EnvironmentInput.AuthInput(AuthMode.SMART_AUTHORIZATION_CODE, true,
                null, null, "app", null, null, null, null, null, null, null, null, null, Map.of(), Map.of()), List.of());
        assertThatThrownBy(() -> g.smartFlow.start(undiscoverable, REQUEST_BASE, null)).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.AUTH_FAILED))
                .hasMessageContaining("no authorization endpoint");
    }

    @Test
    void callbackWithUnknownStateOrAnErrorFails() {
        Environment env = smartEnv("smart", null, null, null, null, Map.of());
        SmartAuthCodeFlow.Outcome unknown = g.smartFlow.callback("code", "bogus-state", null, null);
        assertThat(unknown.success()).isFalse();
        assertThat(unknown.environmentId()).isNull();
        assertThat(unknown.message()).contains("unknown or expired state");

        String state = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        SmartAuthCodeFlow.Outcome denied = g.smartFlow.callback(null, state, "access_denied", "member cancelled");
        assertThat(denied.success()).isFalse();
        assertThat(denied.environmentId()).isEqualTo(env.id());
        assertThat(denied.environmentName()).isEqualTo("smart");
        assertThat(denied.message()).isEqualTo("authorization server returned access_denied: member cancelled");
        // the state is consumed by the first callback
        assertThat(g.smartFlow.callback("code", state, null, null).message()).contains("unknown or expired state");

        String noCode = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        assertThat(g.smartFlow.callback(" ", noCode, null, null).message()).contains("no authorization code");

        String expired = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        g.clock.advance(Duration.ofMinutes(11));
        assertThat(g.smartFlow.callback("code", expired, null, null).message()).contains("unknown or expired state");
        wiremock.verify(0, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void callbackExchangesTheCodeWithPkceAndStoresThePatientContext() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, LOGIN_RESPONSE)));
        Environment env = smartEnv("smart", null, null, null, null, Map.of());
        Map<String, String> authorize = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null));

        SmartAuthCodeFlow.Outcome outcome = g.smartFlow.callback("the-code", authorize.get("state"), null, null);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.environmentId()).isEqualTo(env.id());
        assertThat(outcome.patient()).isEqualTo("123");
        assertThat(outcome.message()).isEqualTo("token obtained for patient 123");

        wiremock.verify(postRequestedFor(urlPathEqualTo("/oauth/token")).withoutHeader("Authorization"));
        Map<String, String> form = onlyTokenRequestForm();
        assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "the-code")
                .containsEntry("redirect_uri", REQUEST_BASE + "/oauth/callback").containsEntry("client_id", "app-client")
                .doesNotContainKey("client_secret");
        assertThat(Pkce.challenge(form.get("code_verifier"))).isEqualTo(authorize.get("code_challenge"));

        TokenStatus status = g.tokens.status(env);
        assertThat(status.present()).isTrue();
        assertThat(status.patient()).isEqualTo("123");
        assertThat(status.refreshable()).isTrue();
        assertThat(status.source()).isEqualTo("authorization_code");
        assertThat(status.scope()).isEqualTo("launch/patient patient/*.rs offline_access");
        assertThat(status.context()).containsEntry("patient", "123").doesNotContainKeys("access_token", "refresh_token");
        assertThat(g.tokens.bearerFor(env)).contains("AT-code");
        wiremock.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void confidentialClientsAuthenticateAtTheTokenEndpoint() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, LOGIN_RESPONSE)));
        Environment env = smartEnv("confidential", "app-secret-value-0001", null, null, null, Map.of());
        String state = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        assertThat(g.smartFlow.callback("c", state, null, null).success()).isTrue();
        wiremock.verify(postRequestedFor(urlPathEqualTo("/oauth/token")).withHeader("Authorization", containing("Basic ")));
        assertThat(onlyTokenRequestForm()).doesNotContainKeys("client_id", "client_secret");
    }

    @Test
    void expiredTokensAreRefreshedWithTheRefreshToken() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).withRequestBody(containing("grant_type=authorization_code"))
                .willReturn(Fixtures.json(200, LOGIN_RESPONSE)));
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).withRequestBody(containing("grant_type=refresh_token"))
                .willReturn(Fixtures.json(200, "{\"access_token\":\"AT-refreshed\",\"token_type\":\"Bearer\",\"expires_in\":600,\"patient\":\"123\"}")));
        Environment env = smartEnv("smart", null, "patient/*.rs", null, null, Map.of());
        String state = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        g.smartFlow.callback("the-code", state, null, null);

        g.clock.advance(Duration.ofSeconds(3600));
        assertThat(g.tokens.bearerFor(env)).contains("AT-refreshed");
        List<LoggedRequest> requests = wiremock.findAll(postRequestedFor(urlPathEqualTo("/oauth/token")));
        assertThat(requests).hasSize(2);
        Map<String, String> refresh = Forms.parse(requests.get(1).getBodyAsString());
        assertThat(refresh).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "RT-code")
                .containsEntry("scope", "patient/*.rs").containsEntry("client_id", "app-client");

        TokenStatus status = g.tokens.status(env);
        assertThat(status.source()).isEqualTo("refresh_token");
        assertThat(status.expiresAt()).isEqualTo(g.clock.instant().plusSeconds(600));
        // the old refresh token is kept when the server does not rotate it
        assertThat(status.refreshable()).isTrue();
        assertThat(g.crypto.reveal(g.tokens.stored(env.id()).orElseThrow().refreshToken())).isEqualTo("RT-code");

        // an explicit obtain also refreshes and reports upstream failures as AUTH_FAILED
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).withRequestBody(containing("grant_type=refresh_token"))
                .willReturn(Fixtures.json(400, "{\"error\":\"invalid_grant\"}")));
        assertThatThrownBy(() -> g.tokens.obtain(env, g.tokens.stored(env.id()).orElseThrow(), null))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("invalid_grant")
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.AUTH_FAILED));
    }

    @Test
    void withoutARefreshTokenAnExpiredLoginMustBeRepeated() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200,
                "{\"access_token\":\"AT-only\",\"token_type\":\"Bearer\",\"expires_in\":60,\"patient\":\"123\"}")));
        Environment env = smartEnv("smart", null, null, null, null, Map.of());
        assertThatThrownBy(() -> g.tokens.bearerFor(env)).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.AUTH_FAILED))
                .hasMessageContaining("needs a SMART login");

        String state = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        assertThat(g.smartFlow.callback("c", state, null, null).success()).isTrue();
        assertThat(g.tokens.status(env).refreshable()).isFalse();

        g.clock.advance(Duration.ofSeconds(61));
        assertThat(g.tokens.status(env).expired()).isTrue();
        assertThatThrownBy(() -> g.tokens.bearerFor(env)).isInstanceOf(WorkbenchException.class)
                .hasMessageContaining("needs a SMART login").hasMessageContaining("has no refresh token");
        wiremock.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    void callbackReportsTokenEndpointFailuresWithoutThrowing() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"code used\"}")));
        Environment env = smartEnv("smart", null, null, null, null, Map.of());
        String state = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        SmartAuthCodeFlow.Outcome outcome = g.smartFlow.callback("c", state, null, null);
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("invalid_grant: code used");
        assertThat(g.tokens.status(env).present()).isFalse();

        // a deleted environment cannot complete its login
        String orphan = Forms.query(g.smartFlow.start(env, REQUEST_BASE, null)).get("state");
        g.environments.delete(env.id());
        assertThat(g.smartFlow.callback("c", orphan, null, null).message()).isEqualTo("environment no longer exists");
    }
}
