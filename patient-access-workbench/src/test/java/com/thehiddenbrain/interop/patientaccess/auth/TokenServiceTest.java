package com.thehiddenbrain.interop.patientaccess.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.JsonFile;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.ClientAuthMethod;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.history.RequestRecord;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import com.thehiddenbrain.interop.patientaccess.support.Forms;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final String SECRET = "s3cret-value-9876";
    private static final String TOKEN_RESPONSE = "{\"access_token\":\"AT-1\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"system/*.rs\"}";

    @TempDir
    Path dir;
    WireMockServer wiremock;
    TestGraph g;
    String base;
    String tokenUrl;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        base = wiremock.baseUrl() + "/fhir";
        tokenUrl = wiremock.baseUrl() + "/oauth/token";
        g = new TestGraph(dir);
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private Environment clientCredentials(ClientAuthMethod method, String audience, Map<String, String> extra) {
        return g.environment("cc-" + method, base, new EnvironmentInput.AuthInput(AuthMode.CLIENT_CREDENTIALS, false, null, tokenUrl, "client-1", SECRET,
                method, "system/*.rs", audience, null, null, null, null, null, extra, Map.of()), List.of());
    }

    private Environment backendServices(String jwk, String alg) {
        return g.environment("bs-" + alg, base, new EnvironmentInput.AuthInput(AuthMode.BACKEND_SERVICES, false, null, tokenUrl, "bs-client", null,
                null, null, null, null, jwk, alg, null, null, Map.of(), Map.of()), List.of());
    }

    private Map<String, String> onlyTokenRequest() {
        List<LoggedRequest> requests = wiremock.findAll(postRequestedFor(urlPathEqualTo("/oauth/token")));
        assertThat(requests).hasSize(1);
        return Forms.parse(requests.get(0).getBodyAsString());
    }

    @Test
    void clientCredentialsWithBasicAuthenticationSendsScopeAudienceAndExtraParams() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, TOKEN_RESPONSE)));
        Environment env = clientCredentials(ClientAuthMethod.CLIENT_SECRET_BASIC, "https://fhir.example.org/aud", Map.of("resource", "r1"));

        assertThat(g.tokens.bearerFor(env)).contains("AT-1");

        String basic = "Basic " + Base64.getEncoder().encodeToString(("client-1:" + SECRET).getBytes(StandardCharsets.UTF_8));
        wiremock.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withHeader("Authorization", equalTo(basic))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded")));
        Map<String, String> form = onlyTokenRequest();
        assertThat(form).containsEntry("grant_type", "client_credentials").containsEntry("scope", "system/*.rs")
                .containsEntry("audience", "https://fhir.example.org/aud").containsEntry("resource", "r1")
                .doesNotContainKeys("client_id", "client_secret");

        TokenStatus status = g.tokens.status(env);
        assertThat(status.present()).isTrue();
        assertThat(status.expired()).isFalse();
        assertThat(status.source()).isEqualTo("client_credentials");
        assertThat(status.tokenType()).isEqualTo("Bearer");
        assertThat(status.scope()).isEqualTo("system/*.rs");
        assertThat(status.obtainedAt()).isEqualTo(g.clock.instant());
        assertThat(status.expiresAt()).isEqualTo(g.clock.instant().plusSeconds(3600));
        assertThat(status.context()).containsEntry("expires_in", "3600").containsKey("access_token_claims");

        RequestRecord rec = g.history.list(env.id(), "auth", null, 10).stream().findFirst().flatMap(s -> g.history.get(s.id())).orElseThrow();
        assertThat(rec.requestHeaders()).containsEntry("Authorization", "Basic ***");
        assertThat(rec.responseBody()).doesNotContain("AT-1");
    }

    @Test
    void clientCredentialsWithPostAuthenticationPutsTheCredentialsInTheForm() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, TOKEN_RESPONSE)));
        Environment env = clientCredentials(ClientAuthMethod.CLIENT_SECRET_POST, null, Map.of());

        g.tokens.bearerFor(env);
        wiremock.verify(postRequestedFor(urlPathEqualTo("/oauth/token")).withoutHeader("Authorization"));
        Map<String, String> form = onlyTokenRequest();
        assertThat(form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", "client-1").containsEntry("client_secret", SECRET)
                .doesNotContainKey("audience");
        RequestRecord rec = g.history.list(env.id(), "auth", null, 10).stream().findFirst().flatMap(s -> g.history.get(s.id())).orElseThrow();
        assertThat(rec.requestBody()).contains("client_secret=***").doesNotContain(SECRET);
    }

    @Test
    void tokenIsCachedUntilShortlyBeforeExpiryAndPersisted() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, TOKEN_RESPONSE)));
        Environment env = clientCredentials(ClientAuthMethod.CLIENT_SECRET_BASIC, null, Map.of());

        g.tokens.bearerFor(env);
        g.clock.advance(Duration.ofSeconds(3600 - TokenService.SKEW_SECONDS - 1));
        g.tokens.bearerFor(env);
        wiremock.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));

        g.clock.advance(Duration.ofSeconds(2));
        g.tokens.bearerFor(env);
        wiremock.verify(2, postRequestedFor(urlPathEqualTo("/oauth/token")));
        assertThat(g.tokens.status(env).obtainedAt()).isEqualTo(g.clock.instant());

        TokenStore reloaded = new TokenStore(dir.resolve("tokens.json"));
        AccessToken stored = reloaded.find(env.id()).orElseThrow();
        assertThat(g.crypto.reveal(stored.accessToken())).isEqualTo("AT-1");
        assertThat(stored.expiresAt()).isEqualTo(g.clock.instant().plusSeconds(3600));
    }

    @Test
    void missingExpiresInDefaultsToFiveMinutes() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, "{\"access_token\":\"AT-2\"}")));
        Environment env = clientCredentials(ClientAuthMethod.CLIENT_SECRET_BASIC, null, Map.of());
        AccessToken t = g.tokens.current(env);
        assertThat(t.expiresAt()).isEqualTo(g.clock.instant().plusSeconds(300));
        assertThat(t.tokenType()).isEqualTo("Bearer");
        assertThat(t.scope()).isNull();
        assertThat(t.hasRefreshToken()).isFalse();
    }

    @Test
    void errorResponsesBecomeAuthFailedWithARedactedUpstream() {
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(401, "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\"}")));
        Environment env = clientCredentials(ClientAuthMethod.CLIENT_SECRET_BASIC, null, Map.of());

        assertThatThrownBy(() -> g.tokens.bearerFor(env))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> {
                    WorkbenchException e = (WorkbenchException) t;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_FAILED);
                    assertThat(e.getMessage()).contains("answered 401").contains("invalid_client: bad secret");
                    assertThat(e.getUpstream().httpStatus()).isEqualTo(401);
                    assertThat(e.getUpstream().url()).isEqualTo(tokenUrl);
                    assertThat(e.getUpstream().body()).isEqualTo("(token response redacted)");
                    assertThat(e.getUpstream().requestId()).isNotBlank();
                });
        assertThat(g.tokens.status(env).present()).isFalse();

        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, "{\"token_type\":\"Bearer\"}")));
        assertThatThrownBy(() -> g.tokens.bearerFor(env)).isInstanceOf(WorkbenchException.class).hasMessageContaining("answered 200");

        Environment noEndpoint = g.environment("no-endpoint", "http://127.0.0.1:1/fhir", new EnvironmentInput.AuthInput(AuthMode.CLIENT_CREDENTIALS, true,
                null, null, "c", SECRET, null, null, null, null, null, null, null, null, Map.of(), Map.of()), List.of());
        assertThatThrownBy(() -> g.tokens.bearerFor(noEndpoint)).isInstanceOf(WorkbenchException.class)
                .hasMessageContaining("no token endpoint for environment 'no-endpoint'");
    }

    @Test
    void staticTokenIsSentAsIsAndNeverExposedByTheStatus() throws Exception {
        Environment env = g.environment("static", base, new EnvironmentInput.AuthInput(AuthMode.STATIC_TOKEN, false, null, null, null, null, null,
                null, null, "static-token-value-XYZ1", null, null, null, null, Map.of(), Map.of()), List.of());
        assertThat(g.tokens.bearerFor(env)).contains("static-token-value-XYZ1");
        Environment padded = g.environment("padded", base, new EnvironmentInput.AuthInput(AuthMode.STATIC_TOKEN, false, null, null, null, null, null,
                null, null, " padded-token \n", null, null, null, null, Map.of(), Map.of()), List.of());
        assertThat(g.tokens.bearerFor(padded)).contains("padded-token");
        TokenStatus status = g.tokens.status(env);
        assertThat(status.present()).isTrue();
        assertThat(status.source()).isEqualTo("static");
        assertThat(status.mode()).isEqualTo(AuthMode.STATIC_TOKEN);
        assertThat(status.tokenHint()).isEqualTo("***XYZ1");
        assertThat(JsonFile.MAPPER.writeValueAsString(status)).doesNotContain("static-token-value");
        assertThatThrownBy(() -> g.tokens.obtain(env, null, null)).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.NOT_SUPPORTED));
        assertThat(g.tokens.bearerFor(g.openEnvironment("open", base))).isEmpty();
    }

    @Test
    void manualTokensAreStoredEncryptedAndCanBeForgotten() throws Exception {
        Environment env = g.openEnvironment("manual", base);
        AccessToken t = g.tokens.storeManual(env, " manual-token-value-ABCD ", 120L, "Patient/7");
        assertThat(g.crypto.reveal(t.accessToken())).isEqualTo("manual-token-value-ABCD");
        assertThat(t.expiresAt()).isEqualTo(g.clock.instant().plusSeconds(120));
        assertThat(t.source()).isEqualTo("manual");
        assertThat(t.context()).containsEntry("format", "opaque");

        TokenStatus status = g.tokens.status(env);
        assertThat(status.present()).isTrue();
        assertThat(status.patient()).isEqualTo("Patient/7");
        assertThat(status.refreshable()).isFalse();
        assertThat(status.tokenHint()).isEqualTo("***ABCD");
        String json = JsonFile.MAPPER.writeValueAsString(status);
        assertThat(json).doesNotContain("manual-token-value").contains("***ABCD");
        assertThat(Files.readString(dir.resolve("tokens.json"))).doesNotContain("manual-token-value").contains("\"enc\"");

        g.clock.advance(Duration.ofSeconds(121));
        assertThat(g.tokens.status(env).expired()).isTrue();
        AccessToken hourLong = g.tokens.storeManual(env, "other-token", null, null);
        assertThat(hourLong.expiresAt()).isEqualTo(g.clock.instant().plusSeconds(3600));
        assertThatThrownBy(() -> g.tokens.storeManual(env, " ", null, null)).isInstanceOf(WorkbenchException.class).hasMessageContaining("accessToken is required");

        g.tokens.forget(env.id());
        assertThat(g.tokens.status(env).present()).isFalse();
        assertThat(g.tokens.stored(env.id())).isEmpty();
        assertThat(Files.readString(dir.resolve("tokens.json"))).doesNotContain("\"enc\"");
    }

    @Test
    void backendServicesSignsAnRs384ClientAssertionTheServerCanVerify() throws Exception {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("kid-rs").generate();
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, TOKEN_RESPONSE)));
        Environment env = backendServices(rsa.toJSONString(), "RS384");
        Instant now = g.clock.instant();

        AccessToken t = g.tokens.obtain(env, null, "bs-corr");
        assertThat(t.source()).isEqualTo("backend_services");
        assertThat(g.crypto.reveal(t.accessToken())).isEqualTo("AT-1");

        wiremock.verify(postRequestedFor(urlPathEqualTo("/oauth/token")).withoutHeader("Authorization"));
        Map<String, String> form = onlyTokenRequest();
        assertThat(form).containsEntry("grant_type", "client_credentials")
                .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                .containsEntry("scope", "system/*.rs");
        SignedJWT jwt = SignedJWT.parse(form.get("client_assertion"));
        assertThat(jwt.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS384);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("kid-rs");
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("JWT");
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("bs-client");
        assertThat(claims.getSubject()).isEqualTo("bs-client");
        assertThat(claims.getAudience()).containsExactly(tokenUrl);
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(claims.getExpirationTime().toInstant()).isAfter(now).isBeforeOrEqualTo(now.plusSeconds(300));

        RequestRecord rec = g.history.list(env.id(), "auth", "bs-corr", 10).stream().findFirst().flatMap(s -> g.history.get(s.id())).orElseThrow();
        assertThat(rec.requestBody()).contains("client_assertion=***").doesNotContain(form.get("client_assertion"));
    }

    @Test
    void backendServicesSupportsEs384AndRejectsMismatchedAlgorithms() throws Exception {
        ECKey ec = new ECKeyGenerator(Curve.P_384).keyID("kid-ec").generate();
        wiremock.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(Fixtures.json(200, TOKEN_RESPONSE)));
        Environment env = backendServices(ec.toJSONString(), "ES384");

        g.tokens.obtain(env, null, null);
        SignedJWT jwt = SignedJWT.parse(onlyTokenRequest().get("client_assertion"));
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES384);
        assertThat(jwt.verify(new ECDSAVerifier(ec.toECPublicKey()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(tokenUrl);

        Environment mismatched = backendServices(ec.toJSONString(), "RS384");
        assertThatThrownBy(() -> g.tokens.obtain(mismatched, null, null)).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("EC key needs an ES* algorithm");
        assertThatThrownBy(() -> ClientAssertions.build("c", tokenUrl, ec.toPublicJWK().toJSONString(), "ES384", Instant.now()))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("public key");
        assertThatThrownBy(() -> ClientAssertions.build("c", tokenUrl, "{not json", "ES384", Instant.now()))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("not a valid JWK");
    }

    @Test
    void publicJwksExposesNoPrivateParameters() throws Exception {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("k1").generate();
        String jwks = ClientAssertions.publicJwks(rsa.toJSONString());
        assertThat(jwks).contains("\"keys\"").doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"");
        JWK published = JWKSet.parse(jwks).getKeyByKeyId("k1");
        assertThat(published.isPrivate()).isFalse();
        assertThat(published.getKeyType().getValue()).isEqualTo("RSA");

        // a JWK Set with the private key also works, and the public part of it is what gets published
        JWKSet set = new JWKSet(List.of(rsa.toPublicJWK(), rsa));
        String fromSet = ClientAssertions.publicJwks(set.toString(false));
        assertThat(JWKSet.parse(fromSet).getKeys()).singleElement().satisfies(k -> assertThat(k.isPrivate()).isFalse());
        assertThatThrownBy(() -> ClientAssertions.publicJwks(new JWKSet(rsa.toPublicJWK()).toString()))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("holds no private key");
    }
}
