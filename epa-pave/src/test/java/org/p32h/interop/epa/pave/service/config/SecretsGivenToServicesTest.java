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
    void aCallHoldsTheSecretsOfItsCollectionAndOfTheLookupConfigurationWithTheValuesInForceForItsRun() {
        ConfigFixture fixture = ConfigFixture.on(directory).lookup("provider.headers.[X-Api-Key]", "placeholder-lookup-key-3c1d")
                .lookup("member.headers.[X-Trace]", "placeholder-not-secret-999999").build();
        fixture.activeEnvironmentOf("Held", EnvironmentKind.SANDBOX, "baseUrl", "https://sbx.example.invalid/fhir",
                "clientSecret", "placeholder-env-secret-555555");
        fixture.collectionOf("Vendor", ConfigFixture.items(ConfigFixture.item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription")),
                "[{\"key\": \"tenantPassphrase\", \"value\": \"placeholder-collection-secret-91c2\", \"type\": \"secret\"}]");
        fixture.collectionOf("Other", ConfigFixture.items(ConfigFixture.item("Unrelated", "GET", "{{baseUrl}}/metadata")),
                "[{\"key\": \"otherPassphrase\", \"value\": \"placeholder-other-secret-444444\", \"type\": \"secret\"}]");

        SentSecrets held = fixture.gateway.prepare(CallIntent.of(org.p32h.interop.epa.pave.model.EpaOperation.SUBSCRIPTION_CREATE)
                .withRunValues(Map.of("clientSecret", "placeholder-run-secret-666666"))).heldSecrets();
        SentSecrets.Masked masked = held.mask("a placeholder-collection-secret-91c2 b placeholder-lookup-key-3c1d c placeholder-run-secret-666666 "
                + "d placeholder-env-secret-555555 e placeholder-other-secret-444444 f placeholder-not-secret-999999");

        // The collection the request came from, the lookup's credential header and the value in force for this run.
        assertThat(masked.text()).isEqualTo("a " + Secrets.mask("tenantPassphrase") + " b [secret X-Api-Key header] c "
                + Secrets.mask("clientSecret") + " d placeholder-env-secret-555555 e placeholder-other-secret-444444 f "
                + "placeholder-not-secret-999999");
        // A notification belongs to no one request, so it is masked with every collection's secrets and the environment's own value.
        SentSecrets.Masked notified = fixture.gateway.secretsGivenToServices().mask("e placeholder-other-secret-444444 a "
                + "placeholder-collection-secret-91c2 d placeholder-env-secret-555555");
        assertThat(notified.places()).isEqualTo(3);
        assertThat(held.toString()).doesNotContain("placeholder");
    }

    @Test
    void withNothingConfiguredNothingIsMasked() {
        ConfigFixture fixture = ConfigFixture.on(directory).build();
        assertThat(fixture.gateway.secretsGivenToServices().mask("anything at all").places()).isZero();
    }

    /**
     * A lookup comes from no collection, so a service it calls may repeat a secret of any
     * collection uploaded. Every one is held, with the values in force, as for a notification.
     */
    @Test
    void aLookupHoldsTheSecretVariablesOfEveryCollectionUploaded() {
        ConfigFixture fixture = ConfigFixture.on(directory).lookup("member.url", "https://lookup.example.invalid/member/{id}").build();
        fixture.activeEnvironmentOf("Held", EnvironmentKind.SANDBOX, "baseUrl", "https://sbx.example.invalid/fhir");
        fixture.collectionOf("Vendor", ConfigFixture.items(ConfigFixture.item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription")),
                "[{\"key\": \"tenantPassphrase\", \"value\": \"placeholder-collection-secret-91c2\", \"type\": \"secret\"}]");
        fixture.collectionOf("Other", ConfigFixture.items(ConfigFixture.item("Unrelated", "GET", "{{baseUrl}}/metadata")),
                "[{\"key\": \"otherPassphrase\", \"value\": \"placeholder-other-secret-444444\", \"type\": \"secret\"}]");

        SentSecrets held = fixture.gateway.prepare(CallIntent.of(org.p32h.interop.epa.pave.model.EpaOperation.MEMBER_LOOKUP)
                .withUrlValue("id", "S123456789")).heldSecrets();
        SentSecrets.Masked masked = held.mask("a placeholder-collection-secret-91c2 b placeholder-other-secret-444444 c S123456789");

        assertThat(masked.text()).isEqualTo("a " + Secrets.mask("tenantPassphrase") + " b " + Secrets.mask("otherPassphrase")
                + " c S123456789");
        assertThat(held.toString()).doesNotContain("placeholder");
    }

    /**
     * A value given for one run under a name that neither the environment nor any collection
     * defines is held when the name reads as a credential, as the request side masks it.
     */
    @Test
    void aValueGivenForOneRunUnderANameNoLayerDefinesIsHeldWhenItsNameIsSecret() {
        ConfigFixture fixture = ConfigFixture.on(directory).build();
        fixture.activeEnvironmentOf("Held", EnvironmentKind.SANDBOX, "baseUrl", "https://sbx.example.invalid/fhir");
        fixture.collectionOf("Vendor", ConfigFixture.items(ConfigFixture.item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription")));

        SentSecrets held = fixture.gateway.prepare(CallIntent.of(org.p32h.interop.epa.pave.model.EpaOperation.SUBSCRIPTION_CREATE)
                .withRunValues(Map.of("runOnlyPassword", "placeholder-run-only-secret-1b1b", "runOnlyNote", "placeholder-run-note-2c2c")))
                .heldSecrets();

        assertThat(held.mask("x placeholder-run-only-secret-1b1b y placeholder-run-note-2c2c").text())
                .isEqualTo("x " + Secrets.mask("runOnlyPassword") + " y placeholder-run-note-2c2c");
    }

    /**
     * What a request of the collection in force names or carries as a secret without a variable of
     * any layer: an operating system value written into a header, and a credential typed into a
     * header that is one by its name, into a query parameter or into a body field.
     */
    @Test
    void aCallHoldsWhatTheRequestsOfItsCollectionNameOrCarryAsASecretWithoutAVariable() {
        Map<String, String> operatingSystem = new HashMap<>();
        operatingSystem.put("PAVE_DIRECT_KEY", "placeholder-direct-os-1f1f");
        ConfigFixture fixture = ConfigFixture.on(directory).operatingSystem(operatingSystem).build();
        fixture.activeEnvironmentOf("Held", EnvironmentKind.SANDBOX, "baseUrl", "https://sbx.example.invalid/fhir");
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription"),
                ConfigFixture.item("Extra", "POST", "{{baseUrl}}/Extra?api_key=placeholder-typed-url-key-3d3d")
                        .header("X-Direct", "{{env:PAVE_DIRECT_KEY}}").header("X-Api-Key", "placeholder-literal-header-0e0e")
                        .header("X-Trace", "placeholder-trace-not-secret-4e4e")
                        .body("{\"client_secret\": \"placeholder-typed-body-5f5f\"}")));

        SentSecrets held = fixture.gateway.prepare(CallIntent.of(org.p32h.interop.epa.pave.model.EpaOperation.SUBSCRIPTION_CREATE))
                .heldSecrets();
        SentSecrets.Masked masked = held.mask("a placeholder-direct-os-1f1f b placeholder-literal-header-0e0e c placeholder-typed-url-key-3d3d "
                + "d placeholder-typed-body-5f5f e placeholder-trace-not-secret-4e4e");

        assertThat(masked.text()).isEqualTo("a " + Secrets.mask("env:PAVE_DIRECT_KEY") + " b [secret X-Api-Key header] c "
                + Secrets.mask("api_key as typed") + " d " + Secrets.mask("client_secret as typed")
                + " e placeholder-trace-not-secret-4e4e");
        assertThat(held.toString()).doesNotContain("placeholder");
    }
}
