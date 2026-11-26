package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The loop that reads the JSON members of a body for literal credentials finds what the regular
 * expression it replaced found with find(), at the same places, and reads a megabyte that took
 * that expression hours in well under a second, on a stack of 256 KB.
 */
class JsonMembersTest {

    /** As it was written, before D-275. */
    private static final Pattern OLD = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The possessive form, which finds the same as the old one without recursion, so it can be compared on long texts too. */
    private static final Pattern POSSESSIVE = Pattern.compile("\"((?:[^\"\\\\]++|\\\\.)*+)\"\\s*:\\s*\"((?:[^\"\\\\]++|\\\\.)*+)\"");

    private static List<String> found(Pattern pattern, String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            out.add(matcher.start() + "-" + matcher.end() + " " + matcher.start(1) + "," + matcher.end(1) + " " + matcher.start(2) + ","
                    + matcher.end(2));
        }
        return out;
    }

    private static List<String> found(String text) {
        return LiteralCredentials.jsonMembers(text).stream().map(member -> member.start() + "-" + member.end() + " " + member.nameStart() + ","
                + member.nameEnd() + " " + member.valueStart() + "," + member.valueEnd()).toList();
    }

    @Test
    void theLoopFindsWhatTheExpressionFoundOnTheCasesOfTheTestsAndOnRandomTexts() {
        List<String> cases = List.of(
                "{\n  \"username\": \"tester\",\n  \"password\": \"ZZJSONPASSWORD-24\",\n  \"nested\": {\"client_secret\" : \"ZZ\\\"Q\"},\n"
                        + "  \"resourceType\": \"Claim\"\n}",
                "{\"resourceType\": \"Bundle\", \"type\": \"collection\"}",
                "[{\"token\": \"ZZA-1\"}, {\"token\": \"ZZB-2\"}]",
                "{\"resourceType\":\"ClaimResponse\",\"preAuthRef\":\"PA-PLACEHOLDER-0001\",\"access_token\":\"ZZBODYTOKEN-31\"}",
                "{\"preauthref\":\"x\",\"authRef\":\"ZZREF-32\"}",
                "{\"a\": \"unterminated, \"b\": \"x\\\\\", \"c\" :\"\\\"\"}",
                "{\"k\":\"v\\\\\" , \"x\" : \"y\"",
                "{\"payload\": \"{\\\"client_secret\\\": \\\"x\\\", \\\"a\\\": \\\"b\\\"}\", \"token\": \"t\"}",
                "{\"a\\\n\": \"b\"}", "{\"a\": \"b\\\u2028\"}", "{\"a\":\t\u000B\f\r\n\"b\"}", "{\"\": \"\"}", "\"", "\"\"", "\":\"\"",
                "{\"a\": \"\\" + new String(Character.toChars(0x1F600)) + "\"}");
        for (String text : cases) {
            assertThat(found(text)).as(text).isEqualTo(found(OLD, text));
        }
        List<String> pieces = List.of("\"", "\\", "\\\"", "\\\\", ":", " ", ",", "{", "}", "[", "]", "a", "password", "\n", "\r", "\t", "\u2028",
                "\u00e9", "\": \"", "\\\":\\\"");
        Random random = new Random(20261121L);
        for (int i = 0; i < 200_000; i++) {
            StringBuilder text = new StringBuilder();
            for (int n = random.nextInt(16); n > 0; n--) {
                text.append(pieces.get(random.nextInt(pieces.size())));
            }
            String written = text.toString();
            assertThat(found(written)).as(written).isEqualTo(found(OLD, written));
        }
    }

    @Test
    void onLongTextsTheLoopFindsWhatThePossessiveExpressionFinds() {
        String attachment = "QUJD".repeat(250_000);
        List<String> texts = List.of(
                "{\"resourceType\": \"Binary\", \"data\": \"" + attachment + "\", \"client_secret\": \"ZZ-1\"}",
                "{\"payload\": \"" + "{\\\"a\\\": \\\"b\\\"}, ".repeat(500) + "\", \"token\": \"t\"}",
                "[\"" + "a\\\"".repeat(5_000) + "\"]: \"x\"");
        for (String text : texts) {
            assertThat(found(text)).isEqualTo(found(POSSESSIVE, text));
        }
    }

    @Test
    void aMegabyteOfEscapedQuotesIsReadInUnderASecondOnASmallStack() throws InterruptedException {
        AtomicReference<Object> result = new AtomicReference<>();
        Thread small = new Thread(null, () -> {
            try {
                long start = System.nanoTime();
                int members = LiteralCredentials.jsonMembers("[\"" + "\\\"".repeat(500_000)).size()
                        + LiteralCredentials.jsonMembers("{\"a\": \"" + "x\\\"".repeat(333_333) + "\"}").size();
                result.set(new long[] {members, System.nanoTime() - start});
            } catch (Throwable e) {
                result.set(e);
            }
        }, "small-stack", 256 * 1024);
        small.start();
        small.join(30_000);
        assertThat(result.get()).isInstanceOf(long[].class);
        long[] outcome = (long[]) result.get();
        assertThat(outcome[0]).isEqualTo(1);
        assertThat(outcome[1]).isLessThan(1_000_000_000L);
    }
}
