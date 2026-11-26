package org.p32h.interop.epa.pave.service.dtr;

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
 * A secret the DTR request carried and the service sends back is masked in what the mapper copies
 * into the designed view and the additional fields, as it is in the wire view, for a package and
 * for a step of the adaptive loop alike.
 */
class DtrReturnedSecretsTest {

    private static final String TOKEN = "placeholder-access-token-ABCDEF";
    private static final String KEY = "placeholder-api-key-123456";
    private static final String URL = "https://example.org/p32h-placeholder/fhir/Questionnaire/$questionnaire-package";

    private static VendorExchange answered(String body) {
        SentSecrets secrets = SentSecrets.builder().accessToken(TOKEN).header("X-Api-Key", KEY).build();
        return Exchanges.fromResponse(new WireRequest("POST", URL, URL, List.of(), "{}", true, null, secrets), 200, List.of(), body, 5L, true);
    }

    private static String questionnaire() {
        return "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/q1\",\"status\":\"active\","
                + "\"title\":\"made with " + TOKEN + "\",\"item\":[{\"linkId\":\"q1\",\"type\":\"string\",\"text\":\"Key " + KEY + "?\"}],"
                + "\"note-" + KEY + "\":\"unrecognised field quoting " + TOKEN + "\"}";
    }

    @Test
    void whatThePackageMapperCopiesOutOfTheBodyShowsTheMaskAndNotTheSecret() {
        String body = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + questionnaire() + "}]}";
        DtrReading reading = new DtrResponseMapper().readPackage(answered(body), List.of());

        String shown = JsonSafe.mapper().writeValueAsString(reading.detail()) + JsonSafe.mapper().writeValueAsString(reading.additionalFields());
        assertThat(shown).doesNotContain(TOKEN).doesNotContain(KEY).contains(Secrets.mask("access token"));
        assertThat(reading.detail().questionnaires().get(0).title().value()).isEqualTo("made with " + Secrets.mask("access token"));
        assertThat(reading.additionalFields()).anySatisfy(field -> assertThat(field.path()).contains("note-" + Secrets.mask("X-Api-Key header")));
    }

    @Test
    void whatTheLoopMapperCopiesOutOfTheBodyShowsTheMaskAndNotTheSecret() {
        String body = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[" + questionnaire().replace("\"url\":", "\"id\":\"q\",\"url\":")
                + "],\"questionnaire\":\"#q\",\"status\":\"in-progress\",\"x-" + KEY + "\":\"" + TOKEN + "\"}";
        DtrReading reading = new DtrResponseMapper().readNextQuestion(answered(body), null);

        String shown = JsonSafe.mapper().writeValueAsString(reading.detail()) + JsonSafe.mapper().writeValueAsString(reading.additionalFields());
        assertThat(shown).doesNotContain(TOKEN).doesNotContain(KEY).contains(Secrets.mask("X-Api-Key header"));
    }

    @Test
    void aBodyThatCannotBeParsedSaysThatAPlaceRepeatingASecretIsMasked() {
        DtrReading reading = new DtrResponseMapper().readPackage(answered("not json " + TOKEN), List.of());
        assertThat(reading.detail().responseNote()).startsWith("The body could not be read as JSON. It is shown under the fields that were "
                + "returned but not interpreted, and in the wire view, as received except that a place repeating a secret this tool "
                + "holds is masked.");
        assertThat(JsonSafe.mapper().writeValueAsString(reading.additionalFields())).doesNotContain(TOKEN);
    }
}
