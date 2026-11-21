package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The parts of a questionnaire address a service supplies are held to the rule codes are held to,
 * and a part longer than any identifier is not handed to the pattern that looks for one.
 *
 * <p>The pattern that finds an identifier or a date ran over the whole of each part of the
 * address, which nothing cuts: a DTR package whose Questionnaire url was five million characters
 * of "0-" took 5.0 seconds in it (2.7 for "Jan"), and the url, which holds neither, was written
 * to the ledger whole. A date is at most 18 characters and an identifier, in this record, at most
 * 128, so a longer part is neither; the address is then not recorded, as one that holds an
 * identifier is not. Every address whose parts are of an ordinary length is judged exactly as
 * before: a copy of the rule as it was is run beside the record on the cases of the other tests
 * and on a fixed run of random addresses.
 */
class QuestionnaireAddressPartTest {

    private static RunRecord.Builder minimal() {
        return RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.UNRECOGNISED_CODE).result(TestResult.UNVERIFIED);
    }

    private static String kept(String questionnaires) {
        return minimal().correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE, questionnaires)).build().correlation()
                .get(RunRecord.CORR_QUESTIONNAIRE);
    }

    private static Pattern pattern(String name) throws ReflectiveOperationException {
        Field field = RunRecord.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Pattern) field.get(null);
    }

    /** The rule as it stood before this change, on the record's own patterns: what it kept. */
    private static String keptBefore(String questionnaires) throws ReflectiveOperationException {
        Pattern whole = pattern("QUESTIONNAIRES");
        boolean identifying = false;
        if (whole.matcher(questionnaires).matches()) {
            Pattern identifierOrDate = pattern("IDENTIFIER_OR_DATE");
            Pattern uuid = pattern("UUID_SHAPE");
            Pattern oid = pattern("OID_SHAPE");
            outer:
            for (String canonical : questionnaires.split(" ")) {
                if (canonical.matches("\\(not a url, \\d{1,9} characters\\)")) {
                    continue;
                }
                int bar = canonical.indexOf('|');
                String address = bar < 0 ? canonical : canonical.substring(0, bar);
                if (bar >= 0 && identifierOrDate.matcher(canonical.substring(bar + 1)).find()) {
                    identifying = true;
                    break;
                }
                List<String> parts = new ArrayList<>();
                String lower = address.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("urn:")) {
                    int colon = address.indexOf(':', 4);
                    String namespace = lower.substring(4, colon);
                    String name = address.substring(colon + 1);
                    if (namespace.equals("oid") && oid.matcher(name).matches() || namespace.equals("uuid") && uuid.matcher(name).matches()) {
                        continue;
                    }
                    parts.add(name);
                } else {
                    int path = address.indexOf('/', address.indexOf("//") + 2);
                    if (path >= 0) {
                        parts.addAll(List.of(address.substring(path + 1).split("/")));
                    }
                }
                for (String part : parts) {
                    if (!uuid.matcher(part).matches() && identifierOrDate.matcher(part).find()) {
                        identifying = true;
                        break outer;
                    }
                }
            }
        } else {
            identifying = true;
        }
        return identifying ? "(not recorded, " + questionnaires.length() + " characters)" : questionnaires;
    }

    @Test
    void anAddressWithAPartOfFiveMillionCharactersIsNotHandedToThePatternAndNotRecorded() {
        for (String unit : List.of("0-", "Jan")) {
            String address = "http://example.invalid/Questionnaire/" + unit.repeat(5_000_000 / unit.length());
            long start = System.nanoTime();
            String kept = kept(address);
            long took = System.nanoTime() - start;
            assertThat(kept).isEqualTo("(not recorded, " + address.length() + " characters)");
            // Before, the pattern alone took 5.0 seconds on the "0-" address and 2.7 on "Jan". The
            // bound leaves room for a loaded build machine and still tells the two apart.
            assertThat(took).as(unit).isLessThan(2_000_000_000L);
        }
        // The longest part this record keeps, and one longer.
        String longest = "http://example.invalid/Questionnaire/" + "q".repeat(128);
        assertThat(kept(longest)).isEqualTo(longest);
        assertThat(kept(longest + "q")).isEqualTo("(not recorded, " + (longest.length() + 1) + " characters)");
        String urn = "urn:zz:" + "q".repeat(129);
        assertThat(kept(urn)).isEqualTo("(not recorded, " + urn.length() + " characters)");
    }

    @Test
    void everyAddressWhosePartsAreOfAnOrdinaryLengthIsJudgedAsBefore() throws ReflectiveOperationException {
        List<String> cases = new ArrayList<>(List.of(
                "https://payer.example.invalid/Questionnaire/S12345678-01",
                "https://payer.example.invalid/Questionnaire/Zzlastname.Zzfirstname.1961-07-23",
                "urn:zz:Zzlastname-Zzfirstname-19610723-77-Zzaddress-Lane", "https://payer.example.invalid/Questionnaire/q1|23Jul1961",
                "https://payer.example.invalid/Questionnaire/q1 https://payer.example.invalid/S123456789/Questionnaire/q2",
                "http://10.10.10.10:8080/fhir/Questionnaire/home-o2|1.0.0",
                "https://example.org/Questionnaire/q1 https://example.org/Questionnaire/q2",
                "https://example.org/Questionnaire/q1?patient=x", "two\nlines",
                "https://example.org/Questionnaire/q1 (not a url, 12 characters)",
                "urn:oid:2.16.840.1.113883.4.642.3.1", "urn:uuid:0f8fad5b-d9cb-469f-a165-70867728950e",
                "https://example.org/Questionnaire/0f8fad5b-d9cb-469f-a165-70867728950e|2026-01-05"));
        Random random = new Random(20261121L);
        String[] pieces = {"0", "7", "12", "1961", "-", ".", "/", "_", "~", ":", "q", "Q", "jan", "July", "sept", " ", "Questionnaire", "x"};
        for (int i = 0; i < 20_000; i++) {
            StringBuilder address = new StringBuilder(random.nextBoolean() ? "https://payer.example.invalid/" : "urn:zz:");
            int count = random.nextInt(12);
            for (int j = 0; j < count; j++) {
                address.append(pieces[random.nextInt(pieces.length)]);
            }
            if (random.nextInt(4) == 0) {
                address.append('|').append(pieces[random.nextInt(pieces.length)]).append(pieces[random.nextInt(pieces.length)]);
            }
            cases.add(address.toString());
        }
        int compared = 0;
        for (String each : cases) {
            boolean ordinary = List.of(each.split("[/ |:]")).stream().allMatch(part -> part.length() <= 128);
            assertThat(ordinary).as(each).isTrue();
            assertThat(kept(each)).as(each).isEqualTo(keptBefore(each));
            compared++;
        }
        assertThat(compared).isGreaterThan(20_000);
        // Both ways of being judged turn up among the random addresses, so the comparison says something.
        assertThat(cases.stream().filter(each -> {
            try {
                return keptBefore(each).equals(each);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }).count()).isBetween(1_000L, 19_000L);
    }
}
