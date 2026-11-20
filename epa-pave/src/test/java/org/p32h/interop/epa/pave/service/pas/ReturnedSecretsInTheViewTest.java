package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.PasJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.PasJson.decided;
import static org.p32h.interop.epa.pave.service.pas.PasJson.items;
import static org.p32h.interop.epa.pave.service.pas.PasJson.sent;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;

/**
 * A secret the request carried and the service sends back is masked in what the PAS and
 * subscription mappers copy into the designed view and the additional fields, as it is in the
 * wire view. Without it, a service that quotes the bearer token in ClaimResponse.disposition puts
 * the token in the result the browser receives.
 */
class ReturnedSecretsInTheViewTest {

    private static final String TOKEN = "placeholder-access-token-ABCDEF";
    private static final String KEY = "placeholder-api-key-123456";
    private static final String URL = "https://example.org/p32h-placeholder/fhir/Claim/$submit";

    private static VendorExchange answered(String body) {
        SentSecrets secrets = SentSecrets.builder().accessToken(TOKEN).variable("apiKey", KEY).build();
        return Exchanges.fromResponse(new WireRequest("POST", URL, URL, List.of(), "{}", true, null, secrets), 200, List.of(), body, 5L, true);
    }

    @Test
    void whatThePasMapperCopiesOutOfTheBodyShowsTheMaskAndNotTheSecret() {
        String body = bundle(claimResponse("\"outcome\":\"complete\"", "\"disposition\":\"echo Bearer " + TOKEN + " /fhir/Claim/$submit\"",
                "\"preAuthRef\":\"" + KEY + "\"", "\"note-" + KEY + "\":\"unrecognised field quoting " + TOKEN + "\"",
                items(decided(1, "A1"))));
        PasReading reading = new PasResponseMapper().read(answered(body), sent("E0601"));

        String disposition = reading.detail().claimResponses().get(0).disposition().value();
        assertThat(disposition).isEqualTo("echo Bearer " + Secrets.ACCESS_TOKEN_MASK + " /fhir/Claim/$submit");
        assertThat(reading.detail().claimResponses().get(0).preAuthRef().value()).isEqualTo(Secrets.API_KEY_MASK);
        String shown = JsonSafe.mapper().writeValueAsString(reading.detail()) + JsonSafe.mapper().writeValueAsString(reading.additionalFields());
        assertThat(shown).doesNotContain(TOKEN).doesNotContain(KEY).contains(Secrets.ACCESS_TOKEN_MASK);
        // The field nobody anticipated is still listed, with its name and value masked.
        assertThat(reading.additionalFields()).anySatisfy(field -> assertThat(field.path()).contains("note-" + Secrets.API_KEY_MASK));
        // What decides is read as before.
        assertThat(reading.outcome()).isEqualTo(org.p32h.interop.epa.pave.model.pas.PasOutcome.APPROVED);
    }

    @Test
    void aBodyThatRepeatsNoSecretIsReadAsItCame() {
        VendorExchange exchange = answered(bundle(claimResponse("\"outcome\":\"complete\"", "\"disposition\":\"approved\"",
                items(decided(1, "A1")))));
        assertThat(exchange.withReturnedSecretsMasked()).isSameAs(exchange);
        assertThat(new PasResponseMapper().read(exchange, sent("E0601")).detail().claimResponses().get(0).disposition().value())
                .isEqualTo("approved");
    }

    @Test
    void whatTheSubscriptionMapperCopiesOutOfTheBodyShowsTheMaskAndNotTheSecret() {
        String body = "{\"resourceType\":\"Subscription\",\"id\":\"sub-1\",\"status\":\"active\",\"reason\":\"made with " + TOKEN + "\","
                + "\"criteria\":\"http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic\",\"channel\":{\"type\":\"rest-hook\","
                + "\"endpoint\":\"https://example.org/p32h-placeholder/notify?key=" + KEY + "\",\"payload\":\"application/fhir+json\"}}";
        SubscriptionReading reading = new SubscriptionResponseMapper().read(answered(body), EpaOperation.SUBSCRIPTION_GET, "sub-1");

        String shown = JsonSafe.mapper().writeValueAsString(reading.detail()) + JsonSafe.mapper().writeValueAsString(reading.additionalFields());
        assertThat(shown).doesNotContain(TOKEN).doesNotContain(KEY).contains(Secrets.ACCESS_TOKEN_MASK).contains(Secrets.API_KEY_MASK);
    }

    /** The masks, as the request side writes them. */
    private static final class Secrets {
        static final String ACCESS_TOKEN_MASK = org.p32h.interop.epa.pave.model.Secrets.mask("access token");
        static final String API_KEY_MASK = org.p32h.interop.epa.pave.model.Secrets.mask("apiKey");
    }
}
