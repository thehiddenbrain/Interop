package com.thehiddenbrain.interop.patientaccess.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenResponseParserTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private final SecretCrypto crypto = SecretCrypto.withKey(TestGraph.KEY);

    private static HttpResult response(int status, String body) {
        return new HttpResult("https://as.example.org/token", status, Map.of("Content-Type", List.of("application/json")), body, 5, "req");
    }

    private static String jwt(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    @Test
    void opaqueTokensOnlyReportTheirFormat() {
        assertThat(TokenResponseParser.accessTokenClaims("opaque-token-123")).containsExactly(Map.entry("format", "opaque"));
        assertThat(TokenResponseParser.accessTokenClaims("a.b")).containsExactly(Map.entry("format", "opaque"));
        assertThat(TokenResponseParser.accessTokenClaims("not.a.jwt")).containsExactly(Map.entry("format", "opaque"));
        assertThat(TokenResponseParser.accessTokenClaims(null)).containsExactly(Map.entry("format", "opaque"));
    }

    @Test
    void jwtAccessTokensExposeTheirNonSecretClaims() {
        String token = jwt(new JWTClaimsSet.Builder().issuer("https://as.example.org").subject("app").audience("https://fhir.example.org")
                .expirationTime(Date.from(NOW.plusSeconds(60))).claim("scope", "patient/*.rs").claim("scp", List.of("a", "b")).build());
        Map<String, Object> claims = TokenResponseParser.accessTokenClaims(token);
        assertThat(claims).containsEntry("format", "jwt").containsEntry("iss", "https://as.example.org").containsEntry("sub", "app")
                .containsEntry("aud", List.of("https://fhir.example.org")).containsEntry("scope", "patient/*.rs").containsEntry("scp", List.of("a", "b"));
        assertThat(claims.get("exp")).isEqualTo(Date.from(NOW.plusSeconds(60)));
    }

    @Test
    void idTokenClaimsIncludeFhirUserAndSurviveGarbage() {
        String id = jwt(new JWTClaimsSet.Builder().issuer("iss").subject("sub").audience("app").claim("fhirUser", "https://fhir.example.org/Patient/1").build());
        assertThat(TokenResponseParser.idTokenClaims(id)).containsEntry("iss", "iss").containsEntry("fhirUser", "https://fhir.example.org/Patient/1");
        assertThat(TokenResponseParser.idTokenClaims("garbage")).containsKey("error");
    }

    @Test
    void parseKeepsContextAndFallsBackToThePreviousRefreshToken() {
        String idToken = jwt(new JWTClaimsSet.Builder().issuer("iss").subject("sub").build());
        AccessToken previous = new AccessToken("env", crypto.seal("old-at"), crypto.seal("old-rt"), null, "Bearer", null, null, NOW, NOW, "authorization_code", Map.of());
        AccessToken t = TokenResponseParser.parse("env", response(200,
                "{\"access_token\":\"new-at\",\"id_token\":\"" + idToken + "\",\"token_type\":\"bearer\",\"expires_in\":\"120\",\"patient\":\"p1\",\"extra\":{\"x\":1}}"),
                crypto, NOW, "refresh_token", previous);
        assertThat(crypto.reveal(t.accessToken())).isEqualTo("new-at");
        assertThat(crypto.reveal(t.refreshToken())).isEqualTo("old-rt");
        assertThat(crypto.reveal(t.idToken())).isEqualTo(idToken);
        assertThat(t.tokenType()).isEqualTo("bearer");
        assertThat(t.patient()).isEqualTo("p1");
        assertThat(t.expiresAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(t.source()).isEqualTo("refresh_token");
        assertThat(t.context()).containsEntry("patient", "p1").containsEntry("extra", "{\"x\":1}").containsKey("id_token_claims")
                .doesNotContainKeys("access_token", "refresh_token", "id_token");
        assertThat(t.expired(NOW.plusSeconds(119), 0)).isFalse();
        assertThat(t.expired(NOW.plusSeconds(60), 60)).isTrue();
    }

    @Test
    void responsesWithoutAnAccessTokenAreAuthFailures() {
        assertThatThrownBy(() -> TokenResponseParser.parse("env", response(200, "{\"token_type\":\"Bearer\"}"), crypto, NOW, "x", null))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> {
                    WorkbenchException e = (WorkbenchException) t;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_FAILED);
                    assertThat(e.getUpstream().body()).isEqualTo("(token response redacted)");
                });
        assertThatThrownBy(() -> TokenResponseParser.parse("env", response(502, "<html>gateway</html>"), crypto, NOW, "x", null))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("answered 502: <html>gateway</html>")
                .satisfies(t -> assertThat(((WorkbenchException) t).getUpstream().body()).isEqualTo("<html>gateway</html>"));
    }
}
