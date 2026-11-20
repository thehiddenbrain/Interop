package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.crd.CrdConformanceChecker;
import org.p32h.interop.epa.pave.service.crd.CrdResponseMapper;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/**
 * The real conformance checker reading a real code list. The checker's own tests use a list held
 * in memory that compares lines by case alone and never filters by date, so this is the only place
 * where what the checker expects of a directory meets what the directory does: a row with no line
 * has a blank line of business, a cell that names no vendor is no vendor, and a row applies on the
 * date of service or it does not.
 */
class CodeListConformanceSeamTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    private static final String LIST = "Code,PA,Line of Business,Vendor,Effective Date,End Date\n"
            + "95810,Yes,Medicare,eviCore,1/1/2026,\n"
            + "95810,No,Commercial,,1/1/2026,\n"
            + "95811,Yes,Medicare/Medicaid,-,,\n"
            + "E0601,No,-,-,,\n"
            + "G0399,Yes,Medicare,eviCore,1/1/2025,12/31/2025\n"
            + "G0399,No,Medicare,,1/1/2026,\n"
            + "A7030,,Medicare,,,\n"
            + "E0470,Yes,Medicare,,soon,\n"
            + "E0470,No,Medicare,,1/1/2030,\n";

    private final CrdResponseMapper mapper = new CrdResponseMapper();

    @TempDir
    Path directory;

    /**
     * A response whose coverage information carries these sub-extensions beside the parts CRD 2.0.1
     * requires of every answer, so that the only findings are the ones about the code list.
     */
    private static String response(String... subExtensions) {
        return "{\"cards\":[],\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\","
                + "\"id\":\"service-request-1\",\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[" + String.join(",", subExtensions)
                + ",{\"url\":\"coverage\",\"valueReference\":{\"reference\":\"Coverage/coverage-1\"}},{\"url\":\"covered\",\"valueCode\":\"covered\"},"
                + "{\"url\":\"date\",\"valueDate\":\"2026-01-15\"},{\"url\":\"coverage-assertion-id\",\"valueString\":\"a-1\"}]}]}}]}";
    }

    private static String paNeeded(String value) {
        return "{\"url\":\"pa-needed\",\"valueCode\":\"" + value + "\"}";
    }

    private static String vendor(String name) {
        return "{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"delegated-vendor\"}]}},"
                + "{\"url\":\"value\",\"valueString\":\"" + name + "\"}]}";
    }

    private List<Finding> checkOn(String profile, ProcedureCodeService list, String body, String lineOfBusiness, LocalDate dateOfService,
                                  String code) {
        VendorExchange exchange = TestExchanges.crd(200, body);
        List<SentOrder> orders = new ArrayList<>(List.of(new SentOrder("ServiceRequest/service-request-1", code)));
        return new CrdConformanceChecker(list, EpaProperties.of(Map.of("mode", profile)))
                .check(mapper.read(exchange, orders), exchange, lineOfBusiness, dateOfService);
    }

    /** On the live profile, where a comparison is a statement about a real service. A list loaded from a file is compared there. */
    private List<Finding> check(ProcedureCodeService list, String body, String lineOfBusiness, LocalDate dateOfService, String code) {
        return checkOn("live", list, body, lineOfBusiness, dateOfService, code);
    }

    private List<Finding> check(String body, String lineOfBusiness, LocalDate dateOfService, String code) {
        return check(TestCodeLists.csv(directory, LIST), body, lineOfBusiness, dateOfService, code);
    }

    private static Finding only(List<Finding> findings, FindingKind kind) {
        assertThat(findings).extracting(Finding::kind).containsExactly(kind);
        return findings.get(0);
    }

    @Test
    void agreementOnTheFlagAndTheVendorIsNoFinding() {
        assertThat(check(response(paNeeded("auth-needed"), vendor("eviCore")), "Medicare", LocalDate.of(2026, 3, 20), "95810")).isEmpty();
        // The member's line and the code may be written in any case, with space around them.
        assertThat(check(response(paNeeded("auth-needed"), vendor("EVICORE")), " medicare ", LocalDate.of(2026, 3, 20), "95810")).isEmpty();
    }

    @Test
    void theRowForTheMembersOwnLineIsTheOneCompared() {
        // The same answer, auth-needed, agrees with the Medicare row and disagrees with the Commercial one.
        Finding finding = only(check(response(paNeeded("auth-needed")), "Commercial", LocalDate.of(2026, 3, 20), "95810"),
                FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        assertThat(finding.codeListSays()).isEqualTo("The code list says prior authorization is not required (rows for Commercial).");
        assertThat(finding.serviceSays()).isEqualTo("The service answered pa-needed=auth-needed.");
    }

    @Test
    void aMemberOfALineTheCodeIsNotListedUnderIsNotComparedWithAnotherLinesRow() {
        Finding finding = only(check(response(paNeeded("no-auth")), "Medicaid", null, "95810"), FindingKind.CODE_LIST_NOT_COMPARED);
        assertThat(finding.detail()).isEqualTo("The code list has no row for 95810 under Medicaid. It lists the code under: Medicare, "
                + "Commercial. A row for another line of business is not this member's answer.");
    }

    @Test
    void aRowForTwoLinesSpeaksForEachOfThem() {
        for (String line : new String[] {"Medicare", "Medicaid"}) {
            Finding finding = only(check(response(paNeeded("no-auth")), line, null, "95811"), FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
            // The line is quoted from the list as it is written there.
            assertThat(finding.codeListSays()).as(line).isEqualTo("The code list says prior authorization is required (rows for Medicare/Medicaid).");
        }
        assertThat(only(check(response(paNeeded("no-auth")), "Commercial", null, "95811"), FindingKind.CODE_LIST_NOT_COMPARED).detail())
                .contains("no row for 95811 under Commercial").contains("under: Medicare/Medicaid");
    }

    @Test
    void aDashInTheVendorColumnIsNoVendorAndIsNeverComparedAsOne() {
        // The list requires authorization for 95811 and its vendor cell holds a dash. The service
        // names no vendor either, so the two agree. Served as a vendor called "-", the dash would
        // have been reported as routing the code somewhere the service does not.
        assertThat(check(response(paNeeded("auth-needed")), "Medicare", null, "95811")).isEmpty();
        // When the service does name one, the list has taken no position, and the finding says so in those words.
        Finding finding = only(check(response(paNeeded("auth-needed"), vendor("eviCore")), "Medicare", null, "95811"),
                FindingKind.CODE_LIST_NOT_COMPARED);
        assertThat(finding.detail()).isEqualTo("The service routes 95811 to eviCore. The code list names no vendor for the code (rows for "
                + "Medicare/Medicaid), so the routing could not be compared.");
        assertThat(finding.detail()).doesNotContain("null");
    }

    @Test
    void aDashInTheLineColumnIsARowThatStatesNoLine() {
        // With the member's line not known, only rows that state no line may be used. The E0601 row
        // holds a dash in that column, which names no line, so it is such a row.
        Finding unknownLine = only(check(response(paNeeded("auth-needed")), null, null, "E0601"), FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        assertThat(unknownLine.codeListSays()).isEqualTo("The code list says prior authorization is not required (rows that state no line "
                + "of business).");
        // And it speaks for a member of any line.
        assertThat(only(check(response(paNeeded("auth-needed")), "Commercial", null, "E0601"), FindingKind.CODE_LIST_DISAGREES_ON_AUTH)
                .codeListSays()).contains("rows that state no line of business");
        assertThat(check(response(paNeeded("no-auth")), "Medicaid", null, "E0601")).isEmpty();
        // A code listed under named lines only is never guessed at for a member whose line is not known.
        assertThat(only(check(response(paNeeded("no-auth")), null, null, "95810"), FindingKind.CODE_LIST_NOT_COMPARED).detail())
                .contains("line of business is not known").contains("Medicare, Commercial");
    }

    @Test
    void theDateOfServiceDecidesWhichRowIsInForce() {
        // G0399 needed authorization through 2025 and has not needed it since.
        String noAuth = response(paNeeded("no-auth"));
        assertThat(check(noAuth, "Medicare", LocalDate.of(2026, 3, 20), "G0399")).isEmpty();
        assertThat(only(check(noAuth, "Medicare", LocalDate.of(2025, 6, 1), "G0399"), FindingKind.CODE_LIST_DISAGREES_ON_AUTH).codeListSays())
                .isEqualTo("The code list says prior authorization is required (rows for Medicare).");
        // The last day of the old row and the first day of the new one.
        assertThat(kinds(check(noAuth, "Medicare", LocalDate.of(2025, 12, 31), "G0399"))).containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        assertThat(check(noAuth, "Medicare", LocalDate.of(2026, 1, 1), "G0399")).isEmpty();
        // Before either row applies, the list has nothing for that day, and that is what is said.
        assertThat(only(check(noAuth, "Medicare", LocalDate.of(2024, 6, 1), "G0399"), FindingKind.CODE_LIST_NOT_COMPARED).detail())
                .isEqualTo("The code list has no row for G0399 on 2024-06-01.");
        // With no date of service both rows stand, and they contradict each other.
        assertThat(only(check(noAuth, "Medicare", null, "G0399"), FindingKind.CODE_LIST_NOT_COMPARED).detail())
                .contains("disagree with each other on prior authorization");
    }

    @Test
    void aRowWhoseDateCannotBeReadIsNotDroppedSoItCannotBeLeftOutOfTheComparison() {
        // E0470 has a row effective "soon" that says yes, and a row from 2030 that says no. In 2026
        // the dated row does not apply yet. The other cannot be ruled out, so it is the list's position.
        assertThat(only(check(response(paNeeded("no-auth")), "Medicare", LocalDate.of(2026, 3, 20), "E0470"),
                FindingKind.CODE_LIST_DISAGREES_ON_AUTH).codeListSays()).contains("is required");
        // In 2031 both apply, and the list no longer has one position.
        assertThat(only(check(response(paNeeded("no-auth")), "Medicare", LocalDate.of(2031, 1, 1), "E0470"),
                FindingKind.CODE_LIST_NOT_COMPARED).detail()).contains("disagree with each other");
    }

    @Test
    void aBlankFlagIsTheListNotSayingAndIsNeverComparedAsANo() {
        Finding finding = only(check(response(paNeeded("auth-needed")), "Medicare", null, "A7030"), FindingKind.CODE_LIST_NOT_COMPARED);
        assertThat(finding.detail()).isEqualTo("The code list does not say whether A7030 needs prior authorization (rows for Medicare).");
    }

    @Test
    void aCodeTheListDoesNotHaveIsSaidToBeMissing() {
        assertThat(only(check(response(paNeeded("no-auth")), "Medicare", null, "99999"), FindingKind.CODE_LIST_NOT_COMPARED).detail())
                .isEqualTo("The code list has no row for 99999.");
        // A longer or a shorter code is another code.
        assertThat(only(check(response(paNeeded("no-auth")), "Medicare", null, "9581"), FindingKind.CODE_LIST_NOT_COMPARED).detail())
                .isEqualTo("The code list has no row for 9581.");
    }

    @Test
    void anAliasLetsAMemberWhoseLineIsNamedDifferentlyBeCompared() {
        ProcedureCodeService aliased = TestCodeLists.csv(directory, LIST, Map.of("line-of-business-aliases.MCR[0]", "Medicare"));
        assertThat(check(aliased, response(paNeeded("auth-needed"), vendor("eviCore")), "MCR", LocalDate.of(2026, 3, 20), "95810")).isEmpty();
        // Without the alias the same member's line is one the list does not know.
        assertThat(only(check(response(paNeeded("auth-needed"), vendor("eviCore")), "MCR", LocalDate.of(2026, 3, 20), "95810"),
                FindingKind.CODE_LIST_NOT_COMPARED).detail()).contains("no row for 95810 under MCR");
    }

    @Test
    void aListLoadedFromAFileIsComparedTheSameWayOnBothProfiles() {
        // The list in this test is a file somebody configured, so it is not the placeholder. Where the requests go changes nothing.
        ProcedureCodeService list = TestCodeLists.csv(directory, LIST);
        assertThat(list.selfCheck().placeholder()).isFalse();
        for (String code : new String[] {"95810", "95811", "E0601", "G0399", "A7030", "E0470", "99999"}) {
            for (String line : new String[] {"Medicare", "Commercial", null}) {
                String body = response(paNeeded("no-auth"), vendor("Evolent"));
                assertThat(checkOn("mock", list, body, line, LocalDate.of(2026, 3, 20), code)).as(code + " for " + line)
                        .isEqualTo(checkOn("live", list, body, line, LocalDate.of(2026, 3, 20), code));
            }
        }
    }

    // ---- the bundled placeholder, which is what is loaded when codelist.file is blank ----

    @Test
    void onMockTheBundledPlaceholderAgreesWithTheVendorAndFlagOfItsOwnSleepStudyRow() {
        ProcedureCodeService bundled = new ProcedureCodeService(CodelistProperties.defaults());
        assertThat(checkOn("mock", bundled, response(paNeeded("auth-needed"), vendor("eviCore")), "Medicare", LocalDate.of(2026, 3, 20), "95810"))
                .isEmpty();
        // The scenario for delegated routing names Evolent, and the placeholder routes brain MRI there.
        assertThat(checkOn("mock", bundled, response(paNeeded("auth-needed"), vendor("Evolent")), "Medicare", LocalDate.of(2026, 3, 20), "70553"))
                .isEmpty();
    }

    @Test
    void onMockADisagreementWithTheBundledPlaceholderSaysThatThePlaceholderIsWhatItRestsOn() {
        ProcedureCodeService bundled = new ProcedureCodeService(CodelistProperties.defaults());
        Finding onAuth = only(checkOn("mock", bundled, response(paNeeded("no-auth")), "Medicare", LocalDate.of(2026, 3, 20), "95810"),
                FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        assertThat(onAuth.detail()).isEqualTo("One of them is wrong, and this tool cannot tell which. The code list here is the synthetic "
                + "placeholder that ships with the tool and not a P32H list.");
        Finding onVendor = only(checkOn("mock", bundled, response(paNeeded("auth-needed"), vendor("Carelon")), "Medicare",
                LocalDate.of(2026, 3, 20), "70553"), FindingKind.CODE_LIST_DISAGREES_ON_VENDOR);
        assertThat(onVendor.codeListSays()).isEqualTo("The code list routes this code to Evolent (rows for Medicare).");
        assertThat(onVendor.detail()).endsWith("The code list here is the synthetic placeholder that ships with the tool and not a P32H list.");
        Finding missing = only(checkOn("mock", bundled, response(paNeeded("no-auth")), "Medicare", LocalDate.of(2026, 3, 20), "99999"),
                FindingKind.CODE_LIST_NOT_COMPARED);
        assertThat(missing.detail()).isEqualTo("The code list has no row for 99999 on 2026-03-20. The code list here is the synthetic "
                + "placeholder that ships with the tool and not a P32H list.");
    }

    @Test
    void onLiveTheBundledPlaceholderIsNeverComparedWhateverTheServiceAnswers() {
        ProcedureCodeService bundled = new ProcedureCodeService(CodelistProperties.defaults());
        String[][] cases = {
                // What the placeholder happens to say too, what it contradicts on the flag, what it contradicts on the vendor, and a code it lacks.
                {"95810", response(paNeeded("auth-needed"), vendor("eviCore"))},
                {"95810", response(paNeeded("no-auth"))},
                {"70553", response(paNeeded("auth-needed"), vendor("Carelon"))},
                {"99999", response(paNeeded("no-auth"))}};
        for (String[] one : cases) {
            Finding finding = only(checkOn("live", bundled, one[1], "Medicare", LocalDate.of(2026, 3, 20), one[0]), FindingKind.CODE_LIST_NOT_COMPARED);
            assertThat(finding.title()).as(one[0]).isEqualTo("The code list was not compared with the service: no P32H code list is loaded");
            assertThat(finding.detail()).isEqualTo("No P32H code list is loaded. The tool is using the synthetic placeholder list that ships "
                    + "with it, which says nothing about real codes, so nothing was compared with the service's answer. Set codelist.file "
                    + "to the P32H export and restart the tool.");
            assertThat(finding.subject()).isNull();
        }
    }

    // ---- a list that says of itself that it is synthetic (F-SAMPLE-LIST) ----

    @Test
    void onLiveTheShippedSampleListIsNeverComparedBecauseItSaysItIsSynthetic() {
        // README: ./run.sh mock --codelist.file=samples/codelist/sample-code-list.csv. Its 95810 Medicare row says PA is required.
        ProcedureCodeService sample = new ProcedureCodeService(CodelistProperties.of(Map.of("file", "samples/codelist/sample-code-list.csv")));
        assertThat(sample.selfCheck().placeholder()).isTrue();
        List<Finding> findings = checkOn("live", sample, response(paNeeded("no-auth")), "Medicare", LocalDate.of(2026, 3, 20), "95810");
        assertThat(kinds(findings)).containsExactly(FindingKind.CODE_LIST_NOT_COMPARED);
        assertThat(findings.get(0).title()).isEqualTo("The code list was not compared with the service: no P32H code list is loaded");
        // On mock the comparison runs, and what it raises says the list is synthetic.
        Finding onMock = only(checkOn("mock", sample, response(paNeeded("no-auth")), "Medicare", LocalDate.of(2026, 3, 20), "95810"),
                FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        assertThat(onMock.detail()).endsWith("The code list here is the synthetic placeholder that ships with the tool and not a P32H list.");
    }

    @Test
    void oneRowCarryingTheMarkerMakesAListSyntheticAndAListWithoutItIsTakenAsReal() {
        String marked = LIST + "95999,Yes,Medicare,,1/1/2026,\n";
        ProcedureCodeService withNotes = TestCodeLists.csv(directory, "Code,PA,Line of Business,Notes\n"
                + "95810,Yes,Medicare,Typed by hand\n95811,No,Medicare,placeholder row. not from a p32h export.\n");
        assertThat(withNotes.selfCheck().placeholder()).isTrue();
        assertThat(withNotes.selfCheck().warnings()).first().asString().startsWith("This code list says of itself that it is synthetic: 1 of "
                + "its rows carries the note");
        assertThat(kinds(checkOn("live", withNotes, response(paNeeded("no-auth")), "Medicare", LocalDate.of(2026, 3, 20), "95810")))
                .containsExactly(FindingKind.CODE_LIST_NOT_COMPARED);
        ProcedureCodeService unmarked = TestCodeLists.csv(directory, marked);
        assertThat(unmarked.selfCheck().placeholder()).isFalse();
        assertThat(kinds(checkOn("live", unmarked, response(paNeeded("no-auth")), "Medicare", LocalDate.of(2026, 3, 20), "95810")))
                .contains(FindingKind.CODE_LIST_DISAGREES_ON_AUTH).doesNotContain(FindingKind.CODE_LIST_NOT_COMPARED);
    }

    @Test
    void theBundledListAndEveryListUnderSamplesCarryTheMarkerOnEveryRow() throws java.io.IOException {
        List<ProcedureCodeService> lists = new ArrayList<>(List.of(new ProcedureCodeService(CodelistProperties.defaults())));
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("samples/codelist"))) {
            for (java.nio.file.Path file : files.toList()) {
                lists.add(new ProcedureCodeService(CodelistProperties.of(Map.of("file", file.toString()))));
            }
        }
        assertThat(lists).hasSizeGreaterThanOrEqualTo(2);
        for (ProcedureCodeService list : lists) {
            assertThat(list.selfCheck().placeholder()).as(list.selfCheck().source()).isTrue();
            assertThat(list.search("", null, 100_000)).as(list.selfCheck().source())
                    .allSatisfy(row -> assertThat(row.notes()).contains(CodeListLoader.SYNTHETIC_MARKER));
        }
    }

    private static List<FindingKind> kinds(List<Finding> findings) {
        return findings.stream().map(Finding::kind).toList();
    }
}
