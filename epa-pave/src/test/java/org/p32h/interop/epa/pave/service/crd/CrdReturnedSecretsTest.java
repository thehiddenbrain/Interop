package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;

/**
 * A secret the CRD request carried and the service sends back is masked in what the mapper copies
 * into the designed view and the additional fields, as it is in the wire view. Without it, a
 * service that quotes the bearer token in a card summary puts the token in the result the browser
 * receives.
 */
class CrdReturnedSecretsTest {

    private static final String TOKEN = "placeholder-access-token-ABCDEF";
    private static final String KEY = "placeholder-api-key-123456";
    private static final String URL = "https://example.org/p32h-placeholder/cds-services/order-sign-crd";

    private static VendorExchange answered(String body) {
        SentSecrets secrets = SentSecrets.builder().accessToken(TOKEN).header("X-Api-Key", KEY).build();
        return Exchanges.fromResponse(new WireRequest("POST", URL, URL, List.of(), "{}", true, null, secrets), 200, List.of(), body, 5L, true);
    }

    @Test
    void whatTheCrdMapperCopiesOutOfTheBodyShowsTheMaskAndNotTheSecret() {
        String body = "{\"cards\":[{\"summary\":\"echo Bearer " + TOKEN + "\",\"indicator\":\"info\",\"source\":{\"label\":\"" + KEY + "\"},"
                + "\"x-" + KEY + "\":\"unrecognised field quoting " + TOKEN + "\"}]}";
        CrdReading reading = new CrdResponseMapper().read(answered(body), List.of());

        String shown = JsonSafe.mapper().writeValueAsString(reading.detail()) + JsonSafe.mapper().writeValueAsString(reading.additionalFields());
        assertThat(shown).doesNotContain(TOKEN).doesNotContain(KEY);
        assertThat(reading.detail().cards().get(0).summary().value()).isEqualTo("echo Bearer " + Secrets.mask("access token"));
        // The field nobody anticipated is still listed, its name and its value masked.
        assertThat(reading.additionalFields()).anySatisfy(field -> assertThat(field.path()).contains("x-" + Secrets.mask("X-Api-Key header")));
    }

    @Test
    void aBodyThatCannotBeParsedSaysThatAPlaceRepeatingASecretIsMasked() {
        CrdReading reading = new CrdResponseMapper().read(Exchanges.fromResponse(new WireRequest("POST", URL, URL, List.of(), "{}", true, null,
                SentSecrets.builder().accessToken(TOKEN).build()), 200, List.of(), "not json " + TOKEN, 5L, true), List.of());
        // The screen says the body is not JSON and where it is shown; the note adds only that a place is masked, and why.
        assertThat(reading.detail().responseNote()).startsWith("It is shown as received except that a place repeating a secret the "
                + "request carried is masked.");
        assertThat(new CrdResponseMapper().read(Exchanges.fromResponse(new WireRequest("POST", URL, URL, List.of(), "{}", true, null,
                SentSecrets.builder().accessToken(TOKEN).build()), 200, List.of(), "not json", 5L, true), List.of()).detail().responseNote())
                .doesNotContain("masked").startsWith("The body is not valid JSON");
        assertThat(JsonSafe.mapper().writeValueAsString(reading.additionalFields())).doesNotContain(TOKEN);
    }
}
