package com.thehiddenbrain.interop.patientaccess.auth;

import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthConfig;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Discovered OAuth endpoints and server-provided URLs are guarded like configured ones. */
class DiscoveredEndpointGuardTest {

    private static Environment env(EnvironmentTier tier, String base) {
        return new Environment("e", "env", null, tier, base, AuthConfig.none(), List.of(), List.of(), FhirOptions.defaults(), null, List.of(),
                null, true, 1, null, null);
    }

    @Test
    void discoveredEndpointsMustBeHttpsForProdAndNeverPrivate() {
        Environment prod = env(EnvironmentTier.PROD, "https://api-t-prd.safhir.io/v1/api/pdex");
        assertThat(SmartDiscoveryService.validateDiscovered(prod, "https://auth.safhir.io/v1/token", "token_endpoint")).isEqualTo("https://auth.safhir.io/v1/token");
        assertThatThrownBy(() -> SmartDiscoveryService.validateDiscovered(prod, "http://auth.safhir.io/v1/token", "token_endpoint"))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> SmartDiscoveryService.validateDiscovered(prod, "https://169.254.169.254/latest/token", "token_endpoint"))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("private or metadata");
        assertThatThrownBy(() -> SmartDiscoveryService.validateDiscovered(prod, "https://10.0.0.5/token", "token_endpoint"))
                .isInstanceOf(WorkbenchException.class).hasMessageContaining("private or metadata");
        // a local sandbox may discover local endpoints
        Environment local = env(EnvironmentTier.SANDBOX, "http://localhost:8090/demo/fhir");
        assertThat(SmartDiscoveryService.validateDiscovered(local, "http://localhost:8090/demo/auth/token", "token_endpoint")).isNotNull();
        assertThat(SmartDiscoveryService.validateDiscovered(local, null, "token_endpoint")).isNull();
    }

    @Test
    void guardRefusesDotSegmentsAndBackslashes() {
        Environment env = env(EnvironmentTier.UAT, "https://fhir.example.org/r4");
        assertThatThrownBy(() -> UrlBuilder.guard(env, "https://fhir.example.org/r4/../admin")).isInstanceOf(WorkbenchException.class)
                .hasMessageContaining("dot segments");
        assertThatThrownBy(() -> UrlBuilder.guard(env, "https://fhir.example.org/r4/%2e%2e/admin")).isInstanceOf(WorkbenchException.class);
        assertThatThrownBy(() -> UrlBuilder.guard(env, "https://fhir.example.org/r4/Patient/..")).isInstanceOf(WorkbenchException.class);
        UrlBuilder.guard(env, "https://fhir.example.org/r4/Patient?name=a.b");
    }
}
