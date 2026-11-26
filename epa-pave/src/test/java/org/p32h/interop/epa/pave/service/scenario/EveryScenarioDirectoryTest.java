package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.lookup.LookupFixtures;
import org.p32h.interop.epa.pave.client.lookup.MockMemberLookupClient;
import org.p32h.interop.epa.pave.client.lookup.MockProviderLookupClient;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;

/**
 * Reads every bundled scenario directory, all protocols together, for the two things no single
 * protocol's tests can see: an identifier that could be real, and a sentinel the lookups do not
 * know.
 */
class EveryScenarioDirectoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));

    /**
     * Anything shaped like a member ID or an NPI: eight or more digits, with up to three letters
     * before them, anywhere in the file, inside a longer text or a longer run of letters and
     * digits too. What stands before the digits is not looked at, so a long letter prefix or a
     * mix of letters and digits cannot hide one. And the spaced Medicare form, nine characters,
     * three spaces and two digits.
     */
    private static final Pattern LOOKS_LIKE_AN_IDENTIFIER = Pattern.compile("[A-Za-z]{0,3}\\d{8,}(?![0-9])");
    /**
     * The Medicare Beneficiary Identifier shape: eleven letters and digits, with digits in the
     * first, fourth, seventh, tenth and eleventh places, with or without its two dashes. It holds
     * at most five digits in a row, so the pattern above cannot see it.
     */
    private static final Pattern LOOKS_LIKE_AN_MBI = Pattern.compile(
            "(?<![A-Za-z0-9])\\d[A-Za-z][A-Za-z0-9]\\d-?[A-Za-z][A-Za-z0-9]\\d-?[A-Za-z]{2}\\d{2}(?![A-Za-z0-9])");
    private static final Pattern LOOKS_LIKE_A_SPACED_MEDICARE_ID = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z0-9]{9} {3}\\d{2}(?![0-9])");
    /** A UUID, as a FHIR bundle's fullUrl carries one. Its groups of digits identify nobody. */
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** The one place the placeholder NPI may stand in a scenario file: its sample case, which one click on the mock table fills in. */
    static final String SAMPLE_PLACEHOLDER_NPI = "\"sampleCase\":{\"npi\":\"1234567893\"";

    private final ScenarioRepository bundled = new ScenarioRepository(AppProperties.defaults());
    private final LookupFixtures fixtures = new LookupFixtures();
    private final MockMemberLookupClient members = new MockMemberLookupClient(fixtures, LookupProperties.defaults(), CLOCK);
    private final MockProviderLookupClient providers = new MockProviderLookupClient(fixtures, LookupProperties.defaults());

    private List<Scenario> every() {
        List<Scenario> all = new ArrayList<>();
        for (String protocol : ScenarioRepository.PROTOCOLS) {
            ScenarioRepository.Loaded loaded = bundled.load(protocol);
            assertThat(loaded.problems()).as(protocol).isEmpty();
            assertThat(loaded.scenarios()).as(protocol).isNotEmpty();
            all.addAll(loaded.scenarios());
        }
        return all;
    }

    /** What in a file's text looks like an identifier, once its own sentinel NPIs are set aside. */
    static List<String> identifiersIn(Scenario scenario) {
        String text = UUID.matcher(JsonSafe.compact(scenario.raw())).replaceAll("uuid");
        for (String npi : scenario.sentinelNpis()) {
            text = text.replace("\"" + npi + "\"", "\"\"");
        }
        // The sample case's NPI is the known placeholder 1234567893 (or the file's own sentinel NPI,
        // set aside above), and is set aside only in that one place: anywhere else it still counts.
        text = text.replace(SAMPLE_PLACEHOLDER_NPI, "\"sampleCase\":{\"npi\":\"\"");
        List<String> found = new ArrayList<>();
        for (Pattern pattern : List.of(LOOKS_LIKE_AN_IDENTIFIER, LOOKS_LIKE_AN_MBI, LOOKS_LIKE_A_SPACED_MEDICARE_ID)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    @Test
    void nothingInAnyScenarioFileLooksLikeAMemberIdOrAnNpiApartFromItsSentinelNpi() {
        List<Scenario> all = every();
        assertThat(all).extracting(Scenario::source).as("every protocol directory was read")
                .anyMatch(s -> s.startsWith("crd-")).anyMatch(s -> s.startsWith("dtr-")).anyMatch(s -> s.startsWith("pas-"))
                .anyMatch(s -> s.startsWith("raw-"));
        for (Scenario scenario : all) {
            assertThat(identifiersIn(scenario)).as(scenario.source() + " holds something that looks like an identifier").isEmpty();
        }
    }

    @Test
    void theScanFindsAnIdentifierPlacedAnywhereInAFile() {
        // The two places the audit put one by hand, and the other shapes the scan must see.
        for (String planted : List.of("\"summary\":\"W123456789\"", "\"disposition\":\"Member W123456789 was not found\"",
                "\"note\":\"NPI 1234567893 sent\"", "\"memberId\":\"ABC123456   01\"", "\"x\":\"S1234567801\"",
                // A long letter prefix and a letter-digit mix before the digits, which the verification planted in labels.
                "\"label\":\"Prior authorization required (ref MBRX123456789)\"", "\"label\":\"Approved for subscriber A1B123456789\"",
                // The Medicare Beneficiary Identifier shape, invented, with and without its dashes.
                "\"note\":\"MBI 9ZZ9ZZ9ZZ99\"", "\"value\":\"9ZZ9-ZZ9-ZZ99\"")) {
            Scenario scenario = ScenarioRepository.parse("planted.json",
                    "{\"id\":\"p\",\"number\":1,\"label\":\"L\",\"httpStatus\":200,\"response\":{" + planted + "}}");
            assertThat(identifiersIn(scenario)).as(planted).isNotEmpty();
        }
        // A sentinel NPI is the one identifier a file is meant to hold, and dates and short codes are not identifiers.
        Scenario clean = ScenarioRepository.parse("clean.json", "{\"id\":\"p\",\"number\":1,\"label\":\"L\",\"sentinelNpi\":\"9999999995\","
                + "\"httpStatus\":200,\"response\":{\"date\":\"2026-03-20\",\"code\":\"95810\",\"auth\":\"PA-PLACEHOLDER-0008\","
                + "\"fullUrl\":\"urn:uuid:00000000-0000-4000-8000-0000000000a1\"}}");
        assertThat(identifiersIn(clean)).isEmpty();
    }

    @Test
    void everySentinelMemberIdHasALookupFixtureOrBelongsToAMemberNotFoundScenario() {
        Map<String, String> notFoundIn = new LinkedHashMap<>();
        Map<String, String> foundIn = new LinkedHashMap<>();
        int sentinels = 0;
        for (Scenario scenario : every()) {
            boolean memberNotFound = scenario.expect() != null && "MEMBER_NOT_FOUND".equals(scenario.expect().outcome());
            for (String sentinel : scenario.sentinelMemberIds()) {
                sentinels++;
                String key = MemberIds.noSpace(sentinel).toUpperCase(Locale.ROOT);
                (memberNotFound ? notFoundIn : foundIn).putIfAbsent(key, scenario.source());
                LookupStatus status = members.lookup(MemberIds.forms(sentinel)).status();
                if (memberNotFound) {
                    // A member the scenario says does not exist must not be found by the lookup a tester runs first.
                    assertThat(fixtures.member(sentinel)).as(scenario.source() + " " + sentinel + " has a fixture").isNull();
                    assertThat(status).as(scenario.source() + " " + sentinel).isEqualTo(LookupStatus.NOT_FOUND);
                } else {
                    assertThat(status).as(scenario.source() + " " + sentinel + " is not known to the member lookup").isEqualTo(LookupStatus.FOUND);
                }
            }
        }
        assertThat(sentinels).as("the sentinels were read").isGreaterThanOrEqualTo(40);
        // One sentinel cannot mean a missing member in one protocol and a known member in another.
        assertThat(notFoundIn.keySet()).as("sentinels used both ways").doesNotContainAnyElementsOf(foundIn.keySet());
    }

    @Test
    void everySentinelNpiPassesTheCheckAndIsFoundUnlessItsScenarioIsProviderNotFound() {
        int sentinels = 0;
        for (Scenario scenario : every()) {
            boolean providerNotFound = scenario.expect() != null && "PROVIDER_NOT_FOUND".equals(scenario.expect().outcome());
            for (String npi : scenario.sentinelNpis()) {
                sentinels++;
                assertThat(Npi.isValid(npi)).as(scenario.source() + " " + npi).isTrue();
                assertThat(providers.lookup(npi).status()).as(scenario.source() + " " + npi)
                        .isEqualTo(providerNotFound ? LookupStatus.NOT_FOUND : LookupStatus.FOUND);
            }
        }
        assertThat(sentinels).as("the sentinel NPIs were read").isGreaterThanOrEqualTo(2);
    }
}
