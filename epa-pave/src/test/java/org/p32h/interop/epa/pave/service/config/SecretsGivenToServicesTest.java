package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.SentSecrets;

/**
 * GAP1 of the second guardrail audit and F0 of the member data audit: the gateway gives a
 * notification receiver everything the tool could have sent to a service, in the type that only
 * masks, so a secret repeated in a notification is shown masked.
 */
class SecretsGivenToServicesTest {

    @TempDir
    Path directory;

    @Test
    void everySecretTheConfigurationGivesAServiceIsMaskedAndNothingElse() {
        Map<String, String> operatingSystem = new HashMap<>();
        operatingSystem.put("PAVE_TENANT_KEY", "placeholder-os-secret-111111");
        ConfigFixture fixture = ConfigFixture.on(directory).live().operatingSystem(operatingSystem)
                .onyx("auth.client-secret", "placeholder-client-secret-222222")
                .onyx("variables.ymlApiKey", "placeholder-yml-key-333333")
                .onyx("subscription.callback-url", "https://pave.example.invalid/cb/{{receiverSecret}}?access_token=placeholder-cb-444444&tenant=t1")
                .build();
        fixture.activeEnvironmentOf("Echo Sandbox", EnvironmentKind.SANDBOX, "baseUrl", "https://sbx.example.invalid/fhir",
                "clientSecret", "placeholder-env-secret-555555", "tenantKey", "{{env:PAVE_TENANT_KEY}}",
                "receiverSecret", "placeholder-receiver-666666", "memberSystem", "placeholder-not-secret-777777");
        fixture.gateway.heldAccessToken(new org.springframework.beans.factory.support.StaticListableBeanFactory(
                Map.of("token", (HeldAccessToken) secrets -> secrets.accessToken("placeholder-token-888888"))).getBeanProvider(HeldAccessToken.class));

        SentSecrets secrets = fixture.gateway.secretsGivenToServices();
        String received = "a placeholder-env-secret-555555 b placeholder-os-secret-111111 c placeholder-client-secret-222222 "
                + "d placeholder-yml-key-333333 e placeholder-cb-444444 f placeholder-receiver-666666 g placeholder-token-888888 "
                + "h placeholder-not-secret-777777 https://sbx.example.invalid/fhir";
        SentSecrets.Masked masked = secrets.mask(received);

        assertThat(masked.places()).isEqualTo(7);
        assertThat(masked.text()).doesNotContain("555555").doesNotContain("111111").doesNotContain("222222").doesNotContain("333333")
                .doesNotContain("444444").doesNotContain("666666").doesNotContain("888888")
                .contains(Secrets.mask("clientSecret")).contains(Secrets.mask("access token"))
                // An ordinary variable and the address stay as they were.
                .contains("placeholder-not-secret-777777").contains("https://sbx.example.invalid/fhir");
        // The type hands no value back, and prints none.
        assertThat(secrets.toString()).doesNotContain("placeholder");
    }

    @Test
    void withNothingConfiguredNothingIsMasked() {
        ConfigFixture fixture = ConfigFixture.on(directory).build();
        assertThat(fixture.gateway.secretsGivenToServices().mask("anything at all").places()).isZero();
    }
}
