package com.thehiddenbrain.interop.patientaccess.fhir;

import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthConfig;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IgRoutingTest {

    private static Environment onyx() {
        return new Environment("e1", "Onyx UAT", "Onyx SAFHIR", EnvironmentTier.UAT, "https://api-t-uat.safhir.io/v1/api/pdex", AuthConfig.none(),
                List.of(), List.of(), FhirOptions.defaults(),
                Map.of("c4bb", "https://api-t-uat.safhir.io/v1/api/carin-bb/", "pdex", "https://api-t-uat.safhir.io/v1/api/pdex",
                        "usdf", "https://api-t-uat.safhir.io/v1/api/formulary"),
                List.of(), null, true, 1, null, null);
    }

    private static Environment single() {
        return new Environment("e2", "Single", null, EnvironmentTier.SANDBOX, "https://fhir.example.org/r4", AuthConfig.none(), List.of(), List.of(),
                FhirOptions.defaults(), null, List.of(), null, true, 1, null, null);
    }

    @Test
    void routesByResourceTypeAndUse() {
        MultiValueMap<String, String> pa = new LinkedMultiValueMap<>();
        pa.add("patient", "1");
        pa.add("use", "preauthorization");
        assertThat(IgRouting.igFor("ExplanationOfBenefit", pa)).isEqualTo("pdex");
        assertThat(IgRouting.igFor("ExplanationOfBenefit", null)).isEqualTo("c4bb");
        assertThat(IgRouting.igFor("Patient", null)).isEqualTo("c4bb");
        assertThat(IgRouting.igFor("Condition", null)).isEqualTo("pdex");
        assertThat(IgRouting.igFor("MedicationKnowledge", null)).isEqualTo("usdf");
        assertThat(IgRouting.igFor("PractitionerRole", null)).isEqualTo("plannet");
    }

    @Test
    void buildsUrlsOnTheIgBase() {
        Environment env = onyx();
        MultiValueMap<String, String> pa = new LinkedMultiValueMap<>();
        pa.add("patient", "1");
        pa.add("use", "preauthorization");
        assertThat(UrlBuilder.searchUrl(env, "ExplanationOfBenefit", pa))
                .isEqualTo("https://api-t-uat.safhir.io/v1/api/pdex/ExplanationOfBenefit?patient=1&use=preauthorization");
        MultiValueMap<String, String> claims = new LinkedMultiValueMap<>();
        claims.add("patient", "1");
        assertThat(UrlBuilder.searchUrl(env, "ExplanationOfBenefit", claims))
                .isEqualTo("https://api-t-uat.safhir.io/v1/api/carin-bb/ExplanationOfBenefit?patient=1");
        assertThat(UrlBuilder.readUrl(env, "Coverage", "c1")).startsWith("https://api-t-uat.safhir.io/v1/api/carin-bb/Coverage/c1");
        // plannet has no base configured: falls back to the default base
        assertThat(UrlBuilder.readUrl(env, "Location", "l1")).isEqualTo("https://api-t-uat.safhir.io/v1/api/pdex/Location/l1");
        assertThat(env.allBaseUrls()).containsExactlyInAnyOrder("https://api-t-uat.safhir.io/v1/api/pdex",
                "https://api-t-uat.safhir.io/v1/api/carin-bb", "https://api-t-uat.safhir.io/v1/api/formulary");
    }

    @Test
    void guardAcceptsEveryConfiguredBaseAndRejectsOthers() {
        Environment env = onyx();
        UrlBuilder.guard(env, "https://api-t-uat.safhir.io/v1/api/carin-bb/Patient?_id=1");
        UrlBuilder.guard(env, "https://api-t-uat.safhir.io/v1/api/formulary/InsurancePlan");
        assertThatThrownBy(() -> UrlBuilder.guard(env, "https://api-t-uat.safhir.io/v1/api/provider-directory/Location"))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("outside the FHIR base path");
        assertThatThrownBy(() -> UrlBuilder.guard(env, "https://evil.example.com/v1/api/pdex/Patient"))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("not on the environment's host");
        assertThat(UrlBuilder.resolveInside(env, "/Patient/1")).isEqualTo("https://api-t-uat.safhir.io/v1/api/pdex/Patient/1");
    }

    @Test
    void singleBaseEnvironmentIgnoresRouting() {
        Environment env = single();
        assertThat(UrlBuilder.searchUrl(env, "ExplanationOfBenefit", null)).isEqualTo("https://fhir.example.org/r4/ExplanationOfBenefit");
        assertThat(env.allBaseUrls()).containsExactly("https://fhir.example.org/r4");
    }
}
