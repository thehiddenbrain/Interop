package com.thehiddenbrain.interop.patientaccess.fhir;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthConfig;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlBuilderTest {

    private static Environment env(String baseUrl, boolean allowHostMismatch) {
        FhirOptions fhir = new FhirOptions(null, null, null, true, false, allowHostMismatch, null, null, false);
        return new Environment("e1", "test", "vendor", EnvironmentTier.UAT, baseUrl, AuthConfig.none(), List.of(), List.of(), fhir,
                null, List.of(), null, true, 1, null, null);
    }

    private static final Environment ENV = env("https://fhir.example.org/r4/", false);

    @Test
    void encodeKeepsFhirTokenCharactersReadableAndEscapesTheRest() {
        assertThat(UrlBuilder.encode("http://sys|123")).isEqualTo("http://sys%7C123");
        assertThat(UrlBuilder.encode("a,b:c/d")).isEqualTo("a,b:c/d");
        assertThat(UrlBuilder.encode("John Smith")).isEqualTo("John%20Smith");
        assertThat(UrlBuilder.encode("2024-01-01T00:00:00+01:00")).isEqualTo("2024-01-01T00:00:00%2B01:00");
        assertThat(UrlBuilder.encode("a&b=c")).isEqualTo("a%26b%3Dc");
        assertThat(UrlBuilder.encode(null)).isEmpty();
        assertThat(UrlBuilder.encodePath("Patient 1")).isEqualTo("Patient%201");
    }

    @Test
    void sanitizeEscapesOnlyWhatJavaUriRefuses() {
        assertThat(UrlBuilder.sanitize("https://h/fhir/Patient?identifier=sys|1&name=a b")).isEqualTo("https://h/fhir/Patient?identifier=sys%7C1&name=a%20b");
        assertThat(UrlBuilder.sanitize("https://h/x?q=\"<{}>^`\\")).isEqualTo("https://h/x?q=%22%3C%7B%7D%3E%5E%60%5C");
        assertThat(UrlBuilder.sanitize("https://h/fhir/Patient?_lastUpdated=ge2024-01-01&_count=50")).isEqualTo("https://h/fhir/Patient?_lastUpdated=ge2024-01-01&_count=50");
        assertThat(UrlBuilder.sanitize(null)).isNull();
    }

    @Test
    void searchAndReadUrlsUseTheTrimmedBaseUrl() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("identifier", "http://sys|M1");
        params.add("_lastUpdated", "ge2024-01-01T00:00:00+00:00");
        params.add("_count", "50");
        params.add("_count", "60");
        assertThat(UrlBuilder.searchUrl(ENV, "Patient", params))
                .isEqualTo("https://fhir.example.org/r4/Patient?identifier=http://sys%7CM1&_lastUpdated=ge2024-01-01T00:00:00%2B00:00&_count=50&_count=60");
        assertThat(UrlBuilder.searchUrl(ENV, "Patient", null)).isEqualTo("https://fhir.example.org/r4/Patient");
        assertThat(UrlBuilder.searchUrl(ENV, "Patient", new LinkedMultiValueMap<>())).isEqualTo("https://fhir.example.org/r4/Patient");
        assertThat(UrlBuilder.readUrl(ENV, "Patient", "a/b c")).isEqualTo("https://fhir.example.org/r4/Patient/a%2Fb%20c");
        MultiValueMap<String, String> modifier = new LinkedMultiValueMap<>();
        modifier.add("identifier:of-type", "sys|MB|1");
        assertThat(UrlBuilder.query(modifier)).isEqualTo("identifier:of-type=sys%7CMB%7C1");
        assertThat(UrlBuilder.hostOf("https://FHIR.example.org:8443/x")).isEqualTo("fhir.example.org");
        assertThat(UrlBuilder.hostOf("not a url")).isNull();
    }

    @Test
    void relativeUrlsResolveAgainstTheBase() {
        assertThat(UrlBuilder.resolveInside(ENV, "Patient?name=a b")).isEqualTo("https://fhir.example.org/r4/Patient?name=a%20b");
        assertThat(UrlBuilder.resolveInside(ENV, "/metadata")).isEqualTo("https://fhir.example.org/r4/metadata");
        assertThat(UrlBuilder.resolveInside(ENV, "https://fhir.example.org/r4/Patient?_getpages=x|y"))
                .isEqualTo("https://fhir.example.org/r4/Patient?_getpages=x%7Cy");
        assertThat(UrlBuilder.resolveInside(ENV, "https://fhir.example.org/r4")).isEqualTo("https://fhir.example.org/r4");
    }

    @Test
    void guardAcceptsOnlyTheEnvironmentsHostAndBasePath() {
        UrlBuilder.guard(ENV, "https://fhir.example.org/r4/Patient/1");
        UrlBuilder.guard(ENV, "HTTPS://FHIR.EXAMPLE.ORG:443/r4/Patient/1");

        assertThatThrownBy(() -> UrlBuilder.guard(ENV, "https://evil.example.org/r4/Patient"))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.TARGET_NOT_ALLOWED))
                .hasMessageContaining("not on the environment's host");
        assertThatThrownBy(() -> UrlBuilder.guard(ENV, "http://fhir.example.org/r4/Patient")).hasMessageContaining("not on the environment's host");
        assertThatThrownBy(() -> UrlBuilder.guard(ENV, "https://fhir.example.org:8443/r4/Patient")).hasMessageContaining("not on the environment's host");
        assertThatThrownBy(() -> UrlBuilder.guard(ENV, "https://fhir.example.org/admin/users"))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("outside the FHIR base path");
        assertThatThrownBy(() -> UrlBuilder.guard(ENV, "https://fhir.example.org/r4evil/Patient")).hasMessageContaining("outside the FHIR base path");
        assertThatThrownBy(() -> UrlBuilder.guard(ENV, "Patient/1")).hasMessageContaining("not an absolute URL");
        assertThatThrownBy(() -> UrlBuilder.guard(ENV, "https://fhir.example.org/r4/Patient?bad=%zz")).hasMessageContaining("not a valid URL");

        Environment lenient = env("https://fhir.example.org/r4", true);
        UrlBuilder.guard(lenient, "https://paging.example.org/other/Patient?page=2");
        UrlBuilder.guard(lenient, "https://fhir.example.org/other/Patient?page=2");
        assertThat(UrlBuilder.resolveInside(lenient, "https://paging.example.org/other/Patient?page=2")).isEqualTo("https://paging.example.org/other/Patient?page=2");
    }
}
