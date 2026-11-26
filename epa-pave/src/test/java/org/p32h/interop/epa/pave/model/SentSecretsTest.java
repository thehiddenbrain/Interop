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

    @Test
    void aRequestKnownToCarryASecretNobodyCouldWorkOutSaysSoAndIsNotTheSameAsOneThatCarriedNone() {
        // The secrets that are known are still searched for. What is added is that the set is known not to be whole.
        SentSecrets incomplete = SentSecrets.builder().variable("callbackToken", TOKEN).incomplete().build();
        assertThat(incomplete.incomplete()).isTrue();
        assertThat(incomplete.searched()).isEqualTo(1);
        assertThat(incomplete.mask("x " + TOKEN).text()).isEqualTo("x [secret callbackToken]");
        assertThat(incomplete).isNotEqualTo(one());
        assertThat(incomplete.toString()).isEqualTo("SentSecrets[1 searched for, 0 too short to search for, and at least one not known]");

        // With nothing else known, it is still not "no secret": a response to it cannot be called searched.
        SentSecrets nothingKnown = SentSecrets.builder().incomplete().build();
        assertThat(nothingKnown.incomplete()).isTrue();
        assertThat(nothingKnown.searched()).isZero();
        assertThat(nothingKnown).isNotEqualTo(SentSecrets.none());
        assertThat(nothingKnown.mask("anything").places()).isZero();

        assertThat(one().incomplete()).isFalse();
        assertThat(SentSecrets.none().incomplete()).isFalse();
    }

    @Test
    void aSecretHeldBesideTheRequestIsSearchedForUnderItsOwnMaskAndAValueTheRequestCarriedKeepsItsName() {
        SentSecrets held = SentSecrets.builder().variable("clientSecret", "placeholder-secret-value-7f3a").variable("onyxToken", TOKEN)
                .variable("pin", "12345").build();
        SentSecrets secrets = SentSecrets.builder().accessToken(TOKEN).variable("pin2", "12345").held(held).build();

        assertThat(secrets.searched()).isEqualTo(2);
        // The same short value carried and held is one secret, counted once.
        assertThat(secrets.tooShort()).isEqualTo(1);
        assertThat(secrets.mask("{\"error\":\"bad client placeholder-secret-value-7f3a\",\"t\":\"" + TOKEN + "\"}").text())
                .isEqualTo("{\"error\":\"bad client [secret clientSecret]\",\"t\":\"[secret access token]\"}");
        assertThat(secrets.toString()).isEqualTo("SentSecrets[2 searched for, 1 too short to search for]");
        assertThat(SentSecrets.builder().held(SentSecrets.none()).held(null).build()).isSameAs(SentSecrets.none());
    }

    @Test
    void aHeldSecretThatIsBlankMasksNothingAndOneThatIsShortIsCounted() {
        SentSecrets blank = SentSecrets.builder().held(SentSecrets.builder().variable("clientSecret", "  ").build()).build();
        assertThat(blank).isSameAs(SentSecrets.none());
        SentSecrets five = SentSecrets.builder().held(SentSecrets.builder().variable("clientSecret", "ab-12").build()).build();
        assertThat(five.searched()).isZero();
        assertThat(five.tooShort()).isEqualTo(1);
        assertThat(five.mask("ab-12").places()).isZero();
    }

    @Test
    void oneWalkFindsExactlyWhatASearchForEachSecretInTurnFinds() {
        // Small alphabets, with the backslash and the letters of escapes, so that secrets overlap,
        // nest, repeat themselves and meet escapes far more often than in any real answer.
        java.util.Random random = new java.util.Random(20261121L);
        String alphabet = "ab\\nu0\"/";
        for (int round = 0; round < 3000; round++) {
            SentSecrets.Builder builder = SentSecrets.builder();
            java.util.List<String> values = new java.util.ArrayList<>();
            int count = 1 + random.nextInt(6);
            for (int i = 0; i < count; i++) {
                String value = text(random, alphabet, 6 + random.nextInt(4));
                if (!values.contains(value)) {
                    values.add(value);
                }
                builder.variable("v" + i, value);
            }
            SentSecrets secrets = builder.build();
            String text = text(random, alphabet + "\\\\", random.nextInt(80));
            if (random.nextBoolean() && !values.isEmpty()) {
                text = text + JsonSafe.mapper().writeValueAsString(values.get(random.nextInt(values.size()))) + text;
            }
            assertThat(secrets.mask(text)).as("round %d", round).isEqualTo(Reference.mask(text, values));
        }
    }

    @Test
    void theAutomatonFindsExactlyWhatASearchForEachSecretInTurnFindsWhereBackslashesAreRareOrAbsent() {
        // Secrets of very different lengths that overlap, nest and share their beginnings and ends,
        // in texts where a backslash is rare or absent, so that most starts are read by the
        // automaton, some by the walk, and many lie on the border between the two.
        java.util.Random random = new java.util.Random(20261122L);
        for (int round = 0; round < 3000; round++) {
            String alphabet = round % 3 == 0 ? "ab" : round % 3 == 1 ? "abc" : "ab/u0";
            SentSecrets.Builder builder = SentSecrets.builder();
            java.util.List<String> values = new java.util.ArrayList<>();
            int count = 1 + random.nextInt(8);
            for (int i = 0; i < count; i++) {
                String value = text(random, alphabet, 6 + random.nextInt(random.nextBoolean() ? 4 : 30));
                if (!values.contains(value)) {
                    values.add(value);
                }
                builder.variable("v" + i, value);
            }
            SentSecrets secrets = builder.build();
            StringBuilder text = new StringBuilder(text(random, alphabet, random.nextInt(400)));
            if (round % 2 == 1) {
                for (int escapes = random.nextInt(3); escapes > 0 && text.length() > 0; escapes--) {
                    text.insert(random.nextInt(text.length()), random.nextBoolean() ? "\\/" : "\\u0061");
                }
            }
            if (random.nextBoolean() && !values.isEmpty()) {
                text.append(values.get(random.nextInt(values.size()))).append(text(random, alphabet, random.nextInt(40)));
            }
            String written = text.toString();
            assertThat(secrets.mask(written)).as("round %d", round).isEqualTo(Reference.mask(written, values));
        }
    }

    private static String text(java.util.Random random, String alphabet, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /** The search as it was before the single walk: each secret on its own, from each position. */
    private static final class Reference {

        static SentSecrets.Masked mask(String text, java.util.List<String> values) {
            record Hit(int start, int end, String mask) {
            }
            java.util.List<Hit> hits = new java.util.ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                String mask = Secrets.mask("v" + index);
                for (int start = 0; start < text.length(); start++) {
                    char here = text.charAt(start);
                    if (here != value.charAt(0) && here != '\\') {
                        continue;
                    }
                    int end = end(text, start, value, 0);
                    if (end > start) {
                        hits.add(new Hit(start, end, mask));
                        start = end - 1;
                    }
                }
            }
            hits.sort(java.util.Comparator.comparingInt(Hit::start).thenComparing(java.util.Comparator.comparingInt(Hit::end).reversed()));
            StringBuilder out = new StringBuilder();
            int places = 0;
            int covered = 0;
            for (Hit hit : hits) {
                if (hit.end() <= covered) {
                    continue;
                }
                if (hit.start() > covered) {
                    out.append(text, covered, hit.start());
                }
                out.append(hit.mask());
                places++;
                covered = hit.end();
            }
            if (places == 0) {
                return new SentSecrets.Masked(text, 0);
            }
            out.append(text, covered, text.length());
            return new SentSecrets.Masked(out.toString(), places);
        }

        private static int end(String text, int from, String secret, int done) {
            int at = from;
            for (int i = done; i < secret.length(); i++) {
                if (at >= text.length()) {
                    return -1;
                }
                char wanted = secret.charAt(i);
                int escaped = escape(text, at, wanted);
                boolean plain = text.charAt(at) == wanted;
                if (escaped > 0 && plain) {
                    int end = end(text, at + escaped, secret, i + 1);
                    if (end >= 0) {
                        return end;
                    }
                    at++;
                } else if (escaped > 0) {
                    at += escaped;
                } else if (plain) {
                    at++;
                } else {
                    return -1;
                }
            }
            return at;
        }

        private static int escape(String text, int at, char wanted) {
            if (text.charAt(at) != '\\' || at + 1 >= text.length()) {
                return 0;
            }
            char kind = text.charAt(at + 1);
            if (kind == 'u') {
                if (at + 6 > text.length()) {
                    return 0;
                }
                int code = 0;
                for (int i = at + 2; i < at + 6; i++) {
                    int digit = Character.digit(text.charAt(i), 16);
                    if (digit < 0) {
                        return 0;
                    }
                    code = code * 16 + digit;
                }
                return code == wanted ? 6 : 0;
            }
            int stands = switch (kind) {
                case '"', '\\', '/' -> kind;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                default -> -1;
            };
            return stands == wanted ? 2 : 0;
        }
    }
}
