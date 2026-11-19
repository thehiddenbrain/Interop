package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The secrets a request carried, and what masking a text with them does. Every value here is a
 * placeholder made up for the test.
 */
class SentSecretsTest {

    private static final String TOKEN = "placeholder-callback-token";
    private static final String KEY = "placeholder-api-key-0001";

    private static SentSecrets one() {
        return SentSecrets.builder().variable("callbackToken", TOKEN).build();
    }

    @Test
    void everyPlaceThatRepeatsASecretIsShownAsItsMaskAndCounted() {
        SentSecrets.Masked masked = one().mask("first " + TOKEN + ", then " + TOKEN + ".");
        assertThat(masked.text()).isEqualTo("first [secret callbackToken], then [secret callbackToken].");
        assertThat(masked.places()).isEqualTo(2);

        // A text that repeats none is handed back as it is, and so is no text at all.
        String untouched = "{\"status\":\"active\"}";
        assertThat(one().mask(untouched).text()).isSameAs(untouched);
        assertThat(one().mask(untouched).places()).isZero();
        assertThat(one().mask(null)).isEqualTo(new SentSecrets.Masked(null, 0));
        assertThat(one().mask("")).isEqualTo(new SentSecrets.Masked("", 0));
        // The secret alone, and the secret at either end.
        assertThat(one().mask(TOKEN)).isEqualTo(new SentSecrets.Masked("[secret callbackToken]", 1));
        assertThat(one().mask(TOKEN + "x" + TOKEN).text()).isEqualTo("[secret callbackToken]x[secret callbackToken]");
    }

    @Test
    void aMaskSaysWhatTheSecretIsKnownAs() {
        SentSecrets secrets = SentSecrets.builder().variable("apiKey", KEY).accessToken("placeholder-access-token")
                .header("X-Vendor-Key", "placeholder-literal-credential").build();
        SentSecrets.Masked masked = secrets.mask("key=" + KEY + " token=placeholder-access-token header=placeholder-literal-credential");
        // A variable is named exactly as the request side names it. The other two came from no variable.
        assertThat(masked.text()).isEqualTo("key=" + Secrets.mask("apiKey") + " token=[secret access token] header=[secret X-Vendor-Key header]");
        assertThat(masked.places()).isEqualTo(3);
        assertThat(secrets.searched()).isEqualTo(3);
    }

    @Test
    void aValueKnownUnderTwoNamesKeepsTheNameItWasFirstGivenUnder() {
        SentSecrets secrets = SentSecrets.builder().variable("onyxToken", TOKEN).accessToken(TOKEN).header("Authorization", TOKEN).build();
        assertThat(secrets.searched()).isEqualTo(1);
        assertThat(secrets.mask("Bearer " + TOKEN)).isEqualTo(new SentSecrets.Masked("Bearer [secret onyxToken]", 1));
    }

    @Test
    void aSecretShorterThanSixCharactersIsNotSearchedForAndIsCounted() {
        SentSecrets secrets = SentSecrets.builder().variable("pin", "12345").variable("pass", "1").variable("apiKey", "123456").build();
        assertThat(SentSecrets.SHORTEST_SEARCHED).isEqualTo(6);
        assertThat(secrets.searched()).isEqualTo(1);
        assertThat(secrets.tooShort()).isEqualTo(2);
        // Five characters would match the start of every longer number. Six are searched for.
        SentSecrets.Masked masked = secrets.mask("total 12345, member 1, key 123456");
        assertThat(masked.text()).isEqualTo("total 12345, member 1, key [secret apiKey]");
        assertThat(masked.places()).isEqualTo(1);

        SentSecrets onlyShort = SentSecrets.builder().variable("pin", "12345").build();
        assertThat(onlyShort.searched()).isZero();
        assertThat(onlyShort.tooShort()).isEqualTo(1);
        assertThat(onlyShort.mask("12345")).isEqualTo(new SentSecrets.Masked("12345", 0));
    }

    @Test
    void whiteSpaceAroundASecretIsNotPartOfItAndABlankValueIsNoSecret() {
        SentSecrets padded = SentSecrets.builder().variable("clientSecret", "  placeholder-client-secret \n").build();
        assertThat(padded.mask("<placeholder-client-secret>").text()).isEqualTo("<[secret clientSecret]>");
        assertThat(padded.mask(" placeholder-client-secret \n").text()).isEqualTo(" [secret clientSecret] \n");

        assertThat(SentSecrets.builder().variable("clientSecret", "   ").accessToken(null).header("X-Api-Key", "").build())
                .isSameAs(SentSecrets.none());
        assertThat(SentSecrets.none().searched()).isZero();
        assertThat(SentSecrets.none().tooShort()).isZero();
        assertThat(SentSecrets.none().mask(TOKEN).text()).isEqualTo(TOKEN);
    }

    @Test
    void aSecretInsideALongerOneIsCoveredByTheLongerOnesMask() {
        SentSecrets secrets = SentSecrets.builder().variable("inner", "inner-part").variable("outer", "outer-with-inner-part-inside").build();
        SentSecrets.Masked masked = secrets.mask("a outer-with-inner-part-inside b inner-part c");
        assertThat(masked.text()).isEqualTo("a [secret outer] b [secret inner] c");
        assertThat(masked.places()).isEqualTo(2);
        // Whichever was given first.
        SentSecrets other = SentSecrets.builder().variable("outer", "outer-with-inner-part-inside").variable("inner", "inner-part").build();
        assertThat(other.mask("a outer-with-inner-part-inside b inner-part c")).isEqualTo(masked);
        // One that is the start of the other.
        SentSecrets prefix = SentSecrets.builder().variable("short", "abcdef").variable("long", "abcdefghij").build();
        assertThat(prefix.mask("abcdefghij abcdef abcdefg").text()).isEqualTo("[secret long] [secret short] [secret short]g");
    }

    @Test
    void twoSecretsThatOverlapPartWayLeaveNoPartOfEitherShowing() {
        SentSecrets secrets = SentSecrets.builder().variable("first", "alpha-bravo-charlie").variable("second", "charlie-delta-echo").build();
        SentSecrets.Masked masked = secrets.mask("x alpha-bravo-charlie-delta-echo y");
        assertThat(masked.text()).isEqualTo("x [secret first][secret second] y");
        assertThat(masked.places()).isEqualTo(2);
        assertThat(masked.text()).doesNotContain("alpha").doesNotContain("charlie").doesNotContain("echo");
    }

    @Test
    void aSecretThatRepeatsItselfIsMaskedFromTheLeftWithNoPartLeftOver() {
        SentSecrets secrets = SentSecrets.builder().variable("run", "aaaaaa").build();
        assertThat(secrets.mask("aaaaaaaaaaaa")).isEqualTo(new SentSecrets.Masked("[secret run][secret run]", 2));
        assertThat(secrets.mask("aaaaaaaa")).isEqualTo(new SentSecrets.Masked("[secret run]aa", 1));
    }

    @Test
    void aMaskIsNeverSearchedForASecret() {
        // The word "secret" is six characters and stands in every mask. Found there, it would be masked again and again.
        SentSecrets secrets = SentSecrets.builder().variable("word", "secret").variable("apiKey", KEY).build();
        SentSecrets.Masked masked = secrets.mask(KEY + " and secret");
        assertThat(masked.text()).isEqualTo("[secret apiKey] and [secret word]");
        assertThat(masked.places()).isEqualTo(2);
    }

    @Test
    void aPlaceWrittenWithJsonEscapesIsFoundAsWellAsOneWrittenPlainly() {
        // Standard base64 holds a plus sign, a slash and an equals sign. A password can hold a quote and a backslash.
        String secret = "cGxhY2Vob2xkZXI+/z8=\"p\\w";
        SentSecrets secrets = SentSecrets.builder().variable("clientSecret", secret).build();

        // As a JSON writer must write it: the quote and the backslash escaped.
        String minimal = JsonSafe.mapper().writeValueAsString(secret);
        assertThat(minimal).isEqualTo("\"cGxhY2Vob2xkZXI+/z8=\\\"p\\\\w\"");
        assertThat(secrets.mask("{\"header\":[" + minimal + "]}")).isEqualTo(new SentSecrets.Masked("{\"header\":[\"[secret clientSecret]\"]}", 1));

        // As some writers choose to write it: the slash, the plus sign and the equals sign escaped too, in either case of hex.
        String cautious = "\"cGxhY2Vob2xkZXI\\u002B\\/z8\\u003d\\\"p\\u005Cw\"";
        assertThat(secrets.mask("{\"header\":[" + cautious + "]}")).isEqualTo(new SentSecrets.Masked("{\"header\":[\"[secret clientSecret]\"]}", 1));

        // Every character as a unicode escape.
        StringBuilder every = new StringBuilder();
        for (char c : secret.toCharArray()) {
            every.append(String.format("\\u%04x", (int) c));
        }
        assertThat(secrets.mask("x" + every + "y")).isEqualTo(new SentSecrets.Masked("x[secret clientSecret]y", 1));

        // And as it stands, in a text that is not JSON at all.
        assertThat(secrets.mask("Authorization: Basic " + secret + " was refused"))
                .isEqualTo(new SentSecrets.Masked("Authorization: Basic [secret clientSecret] was refused", 1));
    }

    @Test
    void aBackslashInASecretIsReadBothAsItselfAndAsTheStartOfAnEscape() {
        SentSecrets one = SentSecrets.builder().variable("path", "abc\\ndef").build();
        // Plainly: a backslash and the letter n. Read only as an escape, that pair would be a line break and the place would be missed.
        assertThat(one.mask("<abc\\ndef>").text()).isEqualTo("<[secret path]>");
        // In JSON, where the backslash itself is escaped.
        assertThat(one.mask("<abc\\\\ndef>").text()).isEqualTo("<[secret path]>");

        SentSecrets two = SentSecrets.builder().variable("share", "abc\\\\def").build();
        assertThat(two.mask("<abc\\\\def>").text()).isEqualTo("<[secret share]>");
        assertThat(two.mask("<abc\\\\\\\\def>").text()).isEqualTo("<[secret share]>");

        SentSecrets ending = SentSecrets.builder().variable("tail", "abcde\\").build();
        assertThat(ending.mask("abcde\\").text()).isEqualTo("[secret tail]");
        assertThat(ending.mask("abcde\\\\").text()).isEqualTo("[secret tail]");
        assertThat(ending.mask("abcde").places()).isZero();
    }

    @Test
    void whatOnlyLooksLikeTheSecretIsLeftAlone() {
        SentSecrets secrets = SentSecrets.builder().variable("apiKey", "abc/def+gh").build();
        for (String other : new String[] {"abc/def+g", "ABC/DEF+GH", "abc/def gh", "abc\\ndef+gh", "abc%2Fdef%2Bgh", "abc\\/def\\u002", "abc\\/def\\u00zzgh",
                "abc\\", "abc\\u", "\\", "abc/def+\\"}) {
            SentSecrets.Masked masked = secrets.mask(other);
            assertThat(masked.text()).as(other).isEqualTo(other);
            assertThat(masked.places()).as(other).isZero();
        }
    }

    @Test
    void aValueIsNeverPrintedAndNeverSerialised() {
        record Holder(String label, SentSecrets secrets) {
        }
        SentSecrets secrets = SentSecrets.builder().variable("callbackToken", TOKEN).variable("pin", "12345").build();
        SentSecrets.Builder builder = SentSecrets.builder().variable("callbackToken", TOKEN);

        assertThat(secrets.toString()).isEqualTo("SentSecrets[1 searched for, 1 too short to search for]");
        assertThat(builder.toString()).doesNotContain(TOKEN);
        assertThat(secrets.mask("x " + TOKEN).toString()).isEqualTo("Masked[1 places]");
        // Even where nobody thought to leave it out, it is not written.
        assertThat(JsonSafe.mapper().writeValueAsString(new Holder("kept", secrets))).isEqualTo("{\"label\":\"kept\"}");
        assertThat(JsonSafe.mapper().writeValueAsString(java.util.Map.of("secrets", java.util.List.of(secrets)))).doesNotContain(TOKEN)
                .doesNotContain("12345");
    }

    @Test
    void twoSetsOfTheSameSecretsAreEqual() {
        assertThat(one()).isEqualTo(one()).hasSameHashCodeAs(one());
        assertThat(one()).isNotEqualTo(SentSecrets.builder().variable("callbackToken", KEY).build());
        assertThat(one()).isNotEqualTo(SentSecrets.builder().variable("otherName", TOKEN).build());
        assertThat(one()).isNotEqualTo(SentSecrets.none());
    }
}
