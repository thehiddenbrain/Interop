package com.thehiddenbrain.interop.patientaccess.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CurlRendererTest {

    private static RequestRecord record(String method, String url, Map<String, String> headers, String body) {
        return new RequestRecord("id", Instant.EPOCH, "e", "env", "search", null, method, url, headers, body, 200, Map.of(), null, false, 1, null, null);
    }

    @Test
    void maskedHeadersBecomeShellVariablesAndEverythingElseIsSingleQuoted() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/fhir+json");
        headers.put("Authorization", "Bearer ***");
        headers.put("X-Api-Key", "***");
        headers.put("X-Note", "it's quoted");
        String curl = CurlRenderer.render(record("GET", "https://fhir.example.org/r4/Patient?identifier=sys%7C1&name=O'Brien", headers, null));

        assertThat(curl).startsWith("curl -sS -X GET \\\n  'https://fhir.example.org/r4/Patient?identifier=sys%7C1&name=O'\\''Brien'");
        assertThat(curl).contains("-H 'Accept: application/fhir+json'");
        assertThat(curl).contains("-H \"Authorization: Bearer $TOKEN\"");
        assertThat(curl).contains("-H \"X-Api-Key: $X_API_KEY\"");
        assertThat(curl).contains("-H 'X-Note: it'\\''s quoted'");
        assertThat(curl).doesNotContain("***").doesNotContain("--data-binary");
    }

    @Test
    void postBodiesAreSentWithDataBinary() {
        String curl = CurlRenderer.render(record("POST", "https://as.example.org/token", Map.of("Content-Type", "application/x-www-form-urlencoded"),
                "grant_type=client_credentials&client_secret=***"));
        assertThat(curl).startsWith("curl -sS -X POST");
        assertThat(curl).endsWith("--data-binary 'grant_type=client_credentials&client_secret=***'");
        assertThat(CurlRenderer.render(record("POST", "https://as.example.org/token", Map.of(), ""))).doesNotContain("--data-binary");
        assertThat(CurlRenderer.quote("a'b")).isEqualTo("'a'\\''b'");
        assertThat(CurlRenderer.first(List.of())).isEmpty();
        assertThat(CurlRenderer.first(List.of("x", "y"))).isEqualTo("x");
    }
}
