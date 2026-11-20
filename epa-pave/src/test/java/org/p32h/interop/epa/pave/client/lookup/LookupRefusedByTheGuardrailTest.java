package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * G11: a member lookup the production guardrail refuses says it was refused and why. It is not
 * reported as a lookup that is switched off, and nothing is sent.
 */
class LookupRefusedByTheGuardrailTest {

    @TempDir
    Path directory;

    @Test
    void aLookupWithAHeaderThatAsksForADeleteIsRefusedAndSaysSoOnProduction() {
        Map<String, String> lookup = Map.of("member.url", "https://members.example.invalid/api/members/{id}",
                "member.headers.X-HTTP-Method-Override", "DELETE");
        ConfigFixture fixture = ConfigFixture.on(directory).live().allowProdWrites().lookup("member.url", lookup.get("member.url"))
                .lookup("member.headers.X-HTTP-Method-Override", "DELETE").build();
        fixture.activeEnvironmentOf("vendor-live", EnvironmentKind.PROD, "baseUrl", "https://onyx.example.invalid/fhir");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        HttpMemberLookupClient members = new HttpMemberLookupClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(OnyxProperties.of(Map.of()), fixture.gateway, client, ConfigFixture.CLOCK), fixture.gateway,
                LookupProperties.of(lookup), client, ConfigFixture.CLOCK);

        MemberLookupResult result = members.lookup(MemberIds.forms("S12345678   01"));
        server.verify();

        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.message()).isEqualTo("Member lookup was refused by the production guardrail and not sent: "
                + "\"vendor-live\" is a production environment and production writes are locked for this session. It counts as a "
                + "write because its header X-HTTP-Method-Override asks the far side to act on it as DELETE.")
                .doesNotContain("switched off").doesNotContain("mapped to").doesNotContain("S1234567801");
        assertThat(result.error().action()).endsWith("Until then, type the member details by hand.");
        // Refused by the guardrail, which the error panel shows as such, not as a configuration that is missing.
        assertThat(result.error().kind()).isEqualTo(org.p32h.interop.epa.pave.model.ErrorKind.BLOCKED);
    }
}
