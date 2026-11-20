package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.ResolvedText;

/** M7: a credential typed into a url or a body is found by the name of its place, and marked so that it resolves as written and shows masked. */
class LiteralCredentialsTest {

    private static final VariableResolver RESOLVER = new VariableResolver(name -> null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "g");

    private static ResolvedText resolve(LiteralCredentials.Marked marked) {
        List<VariableLayer> layers = marked.before(List.of(VariableLayer.environment("sandbox", Map.of())));
        return RESOLVER.resolve(marked.template(), layers).text();
    }

    @Test
    void aQueryParameterNamedLikeACredentialIsMaskedAndSentAsWritten() {
        String url = "{{baseUrl}}/oauth2/token?client_secret=ZZURLSECRET-15&api_key=ZZURLKEY-16&access_token=ZZURLTOKEN-18&scope=read&x={{y}}";
        LiteralCredentials.Marked marked = LiteralCredentials.inUrl(url);
        assertThat(marked.found()).extracting(LiteralCredentials.Found::place).containsExactly(
                "the query parameter client_secret of its url", "the query parameter api_key of its url",
                "the query parameter access_token of its url");
        assertThat(marked.shown()).isEqualTo("{{baseUrl}}/oauth2/token?client_secret=[secret client_secret as typed]&api_key=[secret api_key "
                + "as typed]&access_token=[secret access_token as typed]&scope=read&x={{y}}");
        assertThat(marked.toString()).doesNotContain("ZZ");

        LiteralCredentials.Marked whole = LiteralCredentials.inUrl("https://api.example.invalid/t?client_secret=ZZURLSECRET-15&scope=read");
        ResolvedText text = resolve(whole);
        assertThat(text.value()).isEqualTo("https://api.example.invalid/t?client_secret=ZZURLSECRET-15&scope=read");
        assertThat(text.display()).isEqualTo("https://api.example.invalid/t?client_secret=[secret client_secret as typed]&scope=read");
    }

    @Test
    void aPasswordWrittenIntoTheUrlIsMaskedAndAPlaceholderIsLeftAlone() {
        LiteralCredentials.Marked marked = LiteralCredentials.inUrl("https://reader:ZZUSERPASS-3@api.example.invalid/fhir");
        assertThat(marked.shown()).isEqualTo("https://reader:[secret password as typed]@api.example.invalid/fhir");
        assertThat(LiteralCredentials.inUrl("{{baseUrl}}/t?client_secret={{clientSecret}}&token=").none()).isTrue();
        assertThat(LiteralCredentials.inUrl("{{baseUrl}}/Claim/$submit").none()).isTrue();
    }

    @Test
    void aBodyFieldNamedLikeACredentialIsMaskedAndTheBodyIsSentByteForByte() {
        String body = "{\n  \"username\": \"tester\",\n  \"password\": \"ZZJSONPASSWORD-24\",\n  \"nested\": {\"client_secret\" : \"ZZ\\\"Q\"},\n"
                + "  \"resourceType\": \"Claim\"\n}";
        LiteralCredentials.Marked marked = LiteralCredentials.inBody(body);
        assertThat(marked.found()).extracting(LiteralCredentials.Found::place)
                .containsExactly("the field password of its body", "the field client_secret of its body");
        ResolvedText text = resolve(marked);
        assertThat(text.value()).isEqualTo(body);
        assertThat(text.display()).doesNotContain("ZZJSONPASSWORD-24").doesNotContain("ZZ\\\"Q")
                .contains("\"password\": \"[secret password as typed]\"").contains("\"username\": \"tester\"");
        assertThat(marked.template()).contains("\"password\": \"{{password as typed}}\"");

        String form = "grant_type=client_credentials&client_secret=ZZFORMSECRET-9&client_id=abc";
        LiteralCredentials.Marked formMarked = LiteralCredentials.inBody(form);
        assertThat(resolve(formMarked).value()).isEqualTo(form);
        assertThat(formMarked.shown()).isEqualTo("grant_type=client_credentials&client_secret=[secret client_secret as typed]&client_id=abc");
        // A body that is XML, or names no credential, is left as it is.
        assertThat(LiteralCredentials.inBody("<a password=\"x\"/>").none()).isTrue();
        assertThat(LiteralCredentials.inBody("{\"resourceType\": \"Bundle\", \"type\": \"collection\"}").none()).isTrue();
    }

    @Test
    void twoFieldsOfTheSameNameEachGetAPlaceholderOfTheirOwn() {
        LiteralCredentials.Marked marked = LiteralCredentials.inBody("[{\"token\": \"ZZA-1\"}, {\"token\": \"ZZB-2\"}]");
        assertThat(marked.values()).containsOnlyKeys("token as typed", "token as typed 2");
        assertThat(resolve(marked).value()).isEqualTo("[{\"token\": \"ZZA-1\"}, {\"token\": \"ZZB-2\"}]");
    }
}
