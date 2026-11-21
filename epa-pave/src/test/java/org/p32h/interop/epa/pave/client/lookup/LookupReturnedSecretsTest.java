package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;

/**
 * A secret the lookup request carried and the far side sends back is masked in what the mapping
 * copies into the result: the record, the fields, the returned fields and the error panel, as it
 * is in the wire view. A lookup sits in front of every case, so a member API that echoes its api
 * key would otherwise put it on every screen.
 */
class LookupReturnedSecretsTest {

    private static final String TOKEN = "placeholder-access-token-ABCDEF";
    private static final String KEY = "placeholder-api-key-123456";
    private static final String URL = "https://example.org/p32h-placeholder/members/S123456789";

    private static VendorExchange answered(int status, String body) {
        SentSecrets secrets = SentSecrets.builder().accessToken(TOKEN).header("X-Api-Key", KEY).build();
        return Exchanges.fromResponse(new WireRequest("GET", URL, URL, List.of(), null, true, null, secrets), status, List.of(), body, 5L, true);
    }

    @Test
    void whatTheMemberMappingCopiesOutOfTheBodyShowsTheMaskAndNotTheSecret() {
        String body = "{\"memberId\":\"S123456789\",\"firstName\":\"Avery\",\"lastName\":\"Placeholder\",\"dob\":\"1970-01-31\","
                + "\"lob\":\"Medicare\",\"planName\":\"Plan " + KEY + "\",\"x-" + KEY + "\":\"unrecognised field quoting " + TOKEN + "\"}";
        MemberLookupResult result = MemberResponseMapping.map(answered(200, body), MemberIds.forms("S123456789"), LocalDate.of(2026, 3, 10));

        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.member().planName()).isEqualTo("Plan " + Secrets.mask("X-Api-Key header"));
        assertThat(JsonSafe.mapper().writeValueAsString(result)).doesNotContain(TOKEN).doesNotContain(KEY);
        assertThat(result.additionalFields()).anySatisfy(field -> assertThat(field.path()).contains("x-" + Secrets.mask("X-Api-Key header")));
    }

    @Test
    void whatTheProviderMappingCopiesOutOfAnAnswerItCannotReadShowsTheMaskAndNotTheSecret() {
        String body = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"security\","
                + "\"diagnostics\":\"token " + TOKEN + " refused\"}],\"" + KEY + "\":true}";
        ProviderLookupResult result = ProviderResponseMapping.map(answered(403, body), "1234567893");

        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(JsonSafe.mapper().writeValueAsString(result)).doesNotContain(TOKEN).doesNotContain(KEY)
                .contains(Secrets.mask("access token"));
    }

    @Test
    void aBodyThatIsNotJsonIsSaidToBeMaskedWhereItRepeatsASecret() {
        MemberLookupResult result = MemberResponseMapping.map(answered(200, "not json " + TOKEN), MemberIds.forms("S123456789"),
                LocalDate.of(2026, 3, 10));
        assertThat(result.error().action()).startsWith("The body is shown under the returned fields as received, except that a place "
                + "repeating a secret this tool holds is masked.");
        assertThat(JsonSafe.mapper().writeValueAsString(result)).doesNotContain(TOKEN);
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    /**
     * A lookup comes from no collection, so the gateway holds the secrets of every collection
     * uploaded for it. An answer that repeats a secret variable of one, which the lookup never
     * carried, is masked in the record, the returned fields and the wire view.
     */
    @Test
    void aLookupAnswerThatRepeatsASecretOfACollectionUploadedIsMaskedInTheResultAndTheWireView() {
        String collectionSecret = "placeholder-collection-secret-5e5e";
        ConfigFixture fixture = ConfigFixture.on(directory).lookup("member.url", "https://lookup.example.invalid/member/{id}").build();
        fixture.activeEnvironmentOf("Held", EnvironmentKind.SANDBOX, "baseUrl", "https://onyx.example.invalid/fhir");
        fixture.collectionOf("Plain", ConfigFixture.items(ConfigFixture.item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription")),
                "[{\"key\": \"tenantPassphrase\", \"value\": \"" + collectionSecret + "\", \"type\": \"secret\"}]");
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", "S123456789"));
        WireRequest request = Exchanges.wireRequest(call, null, ExchangeOptions.json("application/json"), true, null);
        assertThat(JsonSafe.mapper().writeValueAsString(request)).doesNotContain(collectionSecret);

        String body = "{\"memberId\":\"S123456789\",\"firstName\":\"Avery " + collectionSecret + "\",\"lastName\":\"Placeholder\","
                + "\"dob\":\"1970-01-31\",\"lob\":\"Medicare\",\"" + collectionSecret + "\":\"x\"}";
        VendorExchange exchange = Exchanges.fromResponse(request, 200, List.of(), body, 7L, true);
        MemberLookupResult result = MemberResponseMapping.map(exchange, MemberIds.forms("S123456789"), LocalDate.of(2026, 3, 10));

        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.member().firstName()).isEqualTo("Avery " + Secrets.mask("tenantPassphrase"));
        assertThat(JsonSafe.mapper().writeValueAsString(result)).doesNotContain(collectionSecret);
        assertThat(exchange.wireResponse().body()).doesNotContain(collectionSecret);
        assertThat(exchange.wireResponse().maskedPlaces()).isEqualTo(2);
    }
}
