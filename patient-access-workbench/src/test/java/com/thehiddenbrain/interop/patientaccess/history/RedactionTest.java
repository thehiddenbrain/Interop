package com.thehiddenbrain.interop.patientaccess.history;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionTest {

    @Test
    void masksCredentialHeadersKeepingTheAuthorizationScheme() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer eyJhbGciOi...");
        headers.put("Proxy-Authorization", "Basic abc");
        headers.put("X-API-KEY", "k");
        headers.put("Ocp-Apim-Subscription-Key", "sub");
        headers.put("Cookie", "session=1");
        headers.put("X-Vendor-Secret", "v");
        headers.put("Accept", "application/fhir+json");

        Map<String, String> out = Redaction.headers(headers, Set.of("x-vendor-secret"));
        assertThat(out).containsExactly(
                Map.entry("Authorization", "Bearer ***"),
                Map.entry("Proxy-Authorization", "***"),
                Map.entry("X-API-KEY", "***"),
                Map.entry("Ocp-Apim-Subscription-Key", "***"),
                Map.entry("Cookie", "***"),
                Map.entry("X-Vendor-Secret", "***"),
                Map.entry("Accept", "application/fhir+json"));
        assertThat(Redaction.headers(Map.of("Authorization", "tokenwithoutscheme"), null)).containsEntry("Authorization", "***");
        assertThat(Redaction.headers(null, null)).isEmpty();
        assertThat(Redaction.isSensitiveHeader("AUTHORIZATION", null)).isTrue();
        assertThat(Redaction.isSensitiveHeader("X-Trace", Set.of("x-other"))).isFalse();
    }

    @Test
    void masksMultiValueResponseHeaders() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Set-Cookie", List.of("a=1", "b=2"));
        headers.put("Content-Type", List.of("application/json"));
        headers.put(null, List.of("HTTP/1.1 200"));
        Map<String, List<String>> out = Redaction.multiHeaders(headers, Set.of());
        assertThat(out).containsExactly(Map.entry("Set-Cookie", List.of("***")), Map.entry("Content-Type", List.of("application/json")));
        assertThat(Redaction.multiHeaders(null, null)).isEmpty();
    }

    @Test
    void masksTokenFieldsInJsonBodies() {
        String body = "{\"access_token\":\"AT\",\"refresh_token\":\"RT\",\"id_token\":\"IT\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"patient/*.rs\"}";
        String out = Redaction.body(body, "application/json;charset=UTF-8");
        assertThat(out).doesNotContain("\"AT\"").doesNotContain("\"RT\"").doesNotContain("\"IT\"")
                .contains("\"access_token\":\"***\"").contains("\"refresh_token\":\"***\"").contains("\"id_token\":\"***\"")
                .contains("\"token_type\":\"Bearer\"").contains("\"expires_in\":300");
        assertThat(Redaction.body("{\"error\":\"invalid_client\"}", "application/json")).isEqualTo("{\"error\":\"invalid_client\"}");
        assertThat(Redaction.body("[1,2]", "application/json")).isEqualTo("[1,2]");
        assertThat(Redaction.body("{not json", "application/json")).isEqualTo("{not json");
    }

    @Test
    void masksSecretFieldsInFormBodiesAndLeavesOtherContentAlone() {
        String form = "grant_type=authorization_code&code=abc&client_secret=S&code_verifier=V&client_id=app&redirect_uri=http%3A%2F%2Flocalhost%2Fcb";
        assertThat(Redaction.body(form, "application/x-www-form-urlencoded"))
                .isEqualTo("grant_type=authorization_code&code=***&client_secret=***&code_verifier=***&client_id=app&redirect_uri=http%3A%2F%2Flocalhost%2Fcb");
        assertThat(Redaction.body("client_assertion=eyJ&client_assertion_type=urn", "application/x-www-form-urlencoded"))
                .isEqualTo("client_assertion=***&client_assertion_type=urn");
        // token-shaped bodies are masked whatever the declared content type (servers mislabel token responses)
        assertThat(Redaction.body("access_token=leaked", "text/plain")).isEqualTo("access_token=***");
        assertThat(Redaction.body("{\"access_token\":\"leaked\",\"x\":1}", "text/plain")).doesNotContain("leaked");
        assertThat(Redaction.body("plain words without secrets", "text/plain")).isEqualTo("plain words without secrets");
        assertThat(Redaction.body("", "application/json")).isEmpty();
        assertThat(Redaction.body(null, null)).isNull();
    }
}
