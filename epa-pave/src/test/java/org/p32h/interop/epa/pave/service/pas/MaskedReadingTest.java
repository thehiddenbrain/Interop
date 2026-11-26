package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.pas.PasResponseKind;

/**
 * A mapper reads the masked body, so a secret this tool holds whose value is also a word the
 * mapper decides by (the resourceType of the answer, say) changes what is read. The note beside
 * the reading then says that the mask did it, and names the mask and nothing it stands for.
 *
 * <p>Every secret here is a placeholder made up for the test.
 */
class MaskedReadingTest {

    private static final String URL = "https://example.org/p32h-placeholder/Claim/$submit";
    private static final String ANSWER = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{"
            + "\"resourceType\":\"ClaimResponse\",\"id\":\"cr-1\",\"status\":\"active\",\"use\":\"preauthorization\","
            + "\"outcome\":\"complete\",\"disposition\":\"approved\",\"preAuthRef\":\"AUTH-PLACEHOLDER-1\"}}]}";

    private static VendorExchange answered(SentSecrets secrets, String body) {
        return Exchanges.fromResponse(new WireRequest("POST", URL, URL, List.of(), "{}", true, null, secrets), 200, List.of(), body, 5L, true);
    }

    @Test
    void aMaskOnTheResourceTypeOfTheAnswerIsNamedBesideTheReadingItChanged() {
        // An environment secret whose value happens to be the word Bundle, which no request used.
        VendorExchange exchange = answered(SentSecrets.builder().variable("tenantWord", "Bundle").build(), ANSWER);
        PasReading reading = new PasResponseMapper().read(exchange, SentClaim.unknown());

        assertThat(reading.detail().responseKind()).isEqualTo(PasResponseKind.OTHER_JSON);
        assertThat(reading.detail().responseNote()).isEqualTo("The body is JSON but is not a bundle, a ClaimResponse or an "
                + "OperationOutcome, so none of it could be interpreted. All of it is listed below. This answer was read with its masks "
                + "in place, and a value this tool reads it by repeats a secret this tool holds: resourceType was read as "
                + "[secret tenantWord]. What is said about it here was read from the mask, not from what the service sent.");
    }

    @Test
    void aMaskedNameOfAValueTheMapperReadsIsSaidWithoutTheNameItself() {
        VendorExchange exchange = answered(SentSecrets.builder().variable("pinCode", "status").build(), ANSWER);
        String note = new PasResponseMapper().read(exchange, SentClaim.unknown()).detail().responseNote();

        assertThat(note).isEqualTo("This answer was read with its masks in place, and a value this tool reads it by repeats a secret "
                + "this tool holds: the name of a value this tool reads by was read as [secret pinCode]. What is said about it here was "
                + "read from the mask, not from what the service sent.");
    }

    @Test
    void aMaskElsewhereOrNoMaskAtAllAddsNothingToTheNote() {
        // The disposition is only shown: the decision is read from review action codes. The preAuthRef is not such a place any
        // more, because it is held against the items' numbers and kept to match a notification to its submission.
        VendorExchange elsewhere = answered(SentSecrets.builder().variable("dispositionWord", "approved").build(), ANSWER);
        PasReading reading = new PasResponseMapper().read(elsewhere, SentClaim.unknown());
        assertThat(reading.detail().responseKind()).isEqualTo(PasResponseKind.BUNDLE);
        assertThat(reading.detail().responseNote()).isNull();
        assertThat(JsonSafe.mapper().writeValueAsString(reading.detail())).doesNotContain("\"approved\"");

        VendorExchange authorization = answered(SentSecrets.builder().variable("authCode", "AUTH-PLACEHOLDER-1").build(), ANSWER);
        assertThat(new PasResponseMapper().read(authorization, SentClaim.unknown()).detail().responseNote())
                .contains("preAuthRef was read as [secret authCode]");

        assertThat(new PasResponseMapper().read(answered(SentSecrets.none(), ANSWER), SentClaim.unknown()).detail().responseNote()).isNull();
    }
}
