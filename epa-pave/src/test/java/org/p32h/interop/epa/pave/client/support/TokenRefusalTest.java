package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.service.config.SignInSettings.ClientAuthentication;

/** The token endpoint's reason, made safe, and the advice read back from the sentence (D-305). */
class TokenRefusalTest {

    private static final String SECRET = "placeholder-client-secret";
    private static final SentSecrets HELD = SentSecrets.builder().variable("client secret", SECRET).build();

    private static String sentence(int status, String body) {
        return TokenRefusal.sentence(status, body, HELD, SECRET);
    }

    @Test
    void onlyATextErrorAndItsDescriptionAreSaid() {
        assertThat(sentence(401, "{\"error\":\"invalid_client\",\"error_description\":\"Bad client.\"}"))
                .isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client). It said: Bad client.");
        assertThat(sentence(401, "{\"error\":\"invalid_client\"}")).isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client).");
        // As before for anything else: no body, not JSON, not an object, an error that is not a text, an empty error.
        for (String body : new String[] {null, "", "<html>no</html>", "[\"invalid_client\"]", "{\"error\":{\"code\":\"x\"}}", "{\"error\":42}",
                "{\"error\":\"   \"}", "{\"message\":\"invalid_client\"}"}) {
            assertThat(sentence(401, body)).as(String.valueOf(body)).isEqualTo("No access token: the token endpoint answered HTTP 401.");
        }
        // A description that is not a text is left out, and the other fields are never read.
        assertThat(sentence(400, "{\"error\":\"invalid_request\",\"error_description\":[\"x\"],\"error_uri\":\"https://example.invalid\"}"))
                .isEqualTo("No access token: the token endpoint answered HTTP 400 (invalid_request).");
    }

    @Test
    void aSecretIsMaskedEvenWhereAControlCharacterSplitsItAndAShortOneHidesTheField() {
        assertThat(sentence(401, "{\"error\":\"invalid_client\",\"error_description\":\"placeholder-client\\u0000-secret was wrong\"}"))
                .isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client). It said: [secret client secret] was wrong.");
        SentSecrets shortOne = SentSecrets.builder().variable("client secret", "abc").build();
        assertThat(TokenRefusal.sentence(401, "{\"error\":\"invalid_client\",\"error_description\":\"secret abc refused\"}", shortOne, "abc"))
                .isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client). It said: (not shown: it holds the client "
                        + "secret)");
    }

    @Test
    void aLongFieldIsCutWithAMarkAndControlAndFormCharactersGo() {
        String said = sentence(401, "{\"error\":\"invalid_client\",\"error_description\":\"" + "y".repeat(5000) + "\"}");
        assertThat(said).endsWith("y".repeat(300) + " ... (cut at 300 characters)").hasSize(
                "No access token: the token endpoint answered HTTP 401 (invalid_client). It said: ".length() + 300
                        + " ... (cut at 300 characters)".length());
        assertThat(TokenRefusal.plain("a\r\nb\u0085c​d⁦e\ud800f \t g")).isEqualTo("a bcdef g");
    }

    @Test
    void theAdviceNamesTheSettingAndIsNeverReadFromTheServicesWords() {
        String basic = "The client id and secret were sent in a Basic header: if the service wants them in the form body, choose In the "
                + "form body under Client authentication in Configuration > Environments > Sign-in.";
        String body = "The client id and secret were sent in the form body: if the service wants them in a Basic header, choose Basic "
                + "header under Client authentication in Configuration > Environments > Sign-in.";
        String scope = "The token endpoint refused the scope: check Scope under Sign-in in Configuration > Environments, which PAVE "
                + "sends as it stands there, and not at all when it is empty.";
        assertThat(TokenRefusal.advice(sentence(401, "{\"error\":\"invalid_client\"}") + " The request was not sent.", ClientAuthentication.BASIC))
                .isEqualTo(basic);
        assertThat(TokenRefusal.advice(sentence(400, "{\"error\":\"invalid_client\"}"), ClientAuthentication.BODY)).isEqualTo(body);
        assertThat(TokenRefusal.advice(sentence(401, null), ClientAuthentication.BASIC)).isEqualTo(basic);
        assertThat(TokenRefusal.advice(sentence(400, "{\"error\":\"invalid_scope\"}"), ClientAuthentication.BASIC)).isEqualTo(scope);
        // Anything else points at no one setting.
        assertThat(TokenRefusal.advice(sentence(400, null), ClientAuthentication.BASIC)).isNull();
        assertThat(TokenRefusal.advice(sentence(403, "{\"error\":\"invalid_client\"}"), ClientAuthentication.BASIC)).isNull();
        assertThat(TokenRefusal.advice(sentence(401, "{\"error\":\"unauthorized_client\"}"), ClientAuthentication.BASIC)).isNull();
        assertThat(TokenRefusal.advice(sentence(400, "{\"error\":\"invalid_grant\",\"error_description\":\"invalid_scope (invalid_client)\"}"),
                ClientAuthentication.BASIC)).isNull();
        assertThat(TokenRefusal.advice("No access token: the token endpoint could not be reached (unavailable).", ClientAuthentication.BASIC))
                .isNull();
        assertThat(TokenRefusal.advice(null, ClientAuthentication.BASIC)).isNull();
    }
}
