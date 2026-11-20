package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * G3: the Onyx access token goes only to an address the configuration in force names for an Onyx
 * operation by itself, for every call of every live protocol client, not only for a raw send.
 */
class OnyxTokenDestinationTest {

    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String BASE = "https://onyx.example.invalid/fhir";
    private static final String ELSEWHERE = "https://collector.example.invalid/attacker";

    @TempDir
    Path directory;

    @Test
    void aReadPointedElsewhereByARunValueGoesWithoutTheTokenAndSaysSo() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().onyx("subscription.url", "{{baseUrl}}").build();
        fixture.activeEnvironmentOf("Onyx Sandbox", EnvironmentKind.SANDBOX, "baseUrl", BASE);
        OnyxProperties onyx = OnyxProperties.of(Map.of("auth.enabled", "true", "auth.token-url", TOKEN_URL,
                "auth.client-id", "placeholder-client", "auth.client-secret", "placeholder-secret",
                "subscription.url", "{{baseUrl}}"));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        RestClient client = builder.build();
        OnyxAccess access = OnyxAccessTestFactory.create(new ExchangeExecutor(), onyx, fixture.gateway, client, ConfigFixture.CLOCK);

        server.expect(ExpectedCount.manyTimes(), requestTo(TOKEN_URL)).andRespond(withSuccess(
                "{\"access_token\":\"placeholder-access-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/Subscription/SUB-1")).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withSuccess("{\"resourceType\":\"Subscription\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ELSEWHERE + "/Subscription/SUB-1")).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"resourceType\":\"Subscription\"}", MediaType.APPLICATION_JSON));

        VendorExchange own = access.send(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withUrlValue("id", "SUB-1")),
                null, ExchangeOptions.read("application/fhir+json"));
        VendorExchange moved = access.send(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withUrlValue("id", "SUB-1")
                .withRunValues(Map.of("baseUrl", ELSEWHERE))), null, ExchangeOptions.read("application/fhir+json"));
        server.verify();

        assertThat(own.request().note()).isNull();
        assertThat(moved.ok()).isTrue();
        assertThat(moved.request().note()).isEqualTo("The Onyx access token was not attached: this request goes to "
                + "https://collector.example.invalid, which is not an address the configuration in force names for an Onyx operation. "
                + "The token is sent only to the service it was issued for.");
        assertThat(OnyxHosts.admits(fixture.gateway, BASE + "/Claim/$submit")).isTrue();
        assertThat(OnyxHosts.admits(fixture.gateway, "http://onyx.example.invalid/fhir")).isFalse();
        assertThat(OnyxHosts.admits(fixture.gateway, "https://onyx.example.invalid:8443/fhir")).isFalse();
        assertThat(OnyxHosts.admits(fixture.gateway, ELSEWHERE)).isFalse();
    }

    @Test
    void everyCollectionRequestMappedToAnOnyxOperationIsAnAddressTheConfigurationNames() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().build();
        fixture.activeEnvironmentOf("Onyx Sandbox", EnvironmentKind.SANDBOX, "baseUrl", BASE);
        fixture.collectionOf("Two gateways", ConfigFixture.items(
                ConfigFixture.item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire"),
                ConfigFixture.item("inquiry on the second gateway", "POST", "https://gw2.example.invalid:8443/gw2/Claim/$inquire")));

        // The second request is chosen with no value typed for the run, so its address is configuration.
        assertThat(OnyxHosts.admits(fixture.gateway, "https://gw2.example.invalid:8443/gw2/Claim/$inquire")).isTrue();
        assertThat(OnyxHosts.admits(fixture.gateway, BASE + "/Claim/$inquire")).isTrue();
        assertThat(OnyxHosts.admits(fixture.gateway, "https://gw2.example.invalid/gw2/Claim/$inquire")).isFalse();
        assertThat(OnyxHosts.admits(fixture.gateway, ELSEWHERE)).isFalse();
    }

    @Test
    void anAddressWhosePathStillWaitsForAnIdentifierStillNamesTheHostTheTokenMayGoTo() {
        // The only Onyx request of the collection has a placeholder no layer fills until a run gives it.
        ConfigFixture fixture = ConfigFixture.on(directory).live().build();
        fixture.activeEnvironmentOf("Onyx Sandbox", EnvironmentKind.SANDBOX, "baseUrl", BASE);
        fixture.collectionOf("Only one", ConfigFixture.items(ConfigFixture.item("subscription-get Happy Path", "GET",
                "{{baseUrl}}/Subscription/{{subscriptionId}}")));

        assertThat(OnyxHosts.admits(fixture.gateway, BASE + "/Subscription/SUB-1")).isTrue();
        assertThat(OnyxHosts.of(fixture.gateway)).containsExactly("onyx.example.invalid");
        assertThat(OnyxHosts.admits(fixture.gateway, ELSEWHERE + "/Subscription/SUB-1")).isFalse();
    }
}
