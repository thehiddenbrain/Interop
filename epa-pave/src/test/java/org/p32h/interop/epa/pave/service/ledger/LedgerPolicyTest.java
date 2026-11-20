package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.configStore;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.dayFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.draft;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledgerDirectory;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.saltFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.text;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.tracking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.lookup.LookupFixtures;
import org.p32h.interop.epa.pave.client.lookup.MockMemberLookupClient;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;

/**
 * What the ledger decides that the record cannot decide for itself: which lines of business it
 * knows, which member IDs on mock are synthetic, and who may read what it creates on disk.
 */
class LedgerPolicyTest {

    private final LedgerFixtures.MovableClock CLOCK = LedgerFixtures.clock();

    @TempDir
    Path home;

    private JsonlRunLedger ledger(Path folder, Map<String, String> tracking, EpaProperties epa, ProcedureCodeDirectory codeList) {
        return new JsonlRunLedger(tracking(folder, tracking), configStore(folder), epa, CLOCK, codeList);
    }

    private static ProcedureCodeDirectory bundledList(Map<String, String> properties) {
        return new ProcedureCodeService(CodelistProperties.of(properties));
    }

    @Test
    void onlyALineOfBusinessTheToolKnowsIsWrittenAndAnyOtherTextIsWrittenAsItsLength() {
        // Found by the member data audit (M3): a name and a date of birth under the member API's lob field reached the line.
        JsonlRunLedger ledger = ledger(home, Map.of(), EpaProperties.of(Map.of("mode", "live")), bundledList(Map.of()));

        // A known line is written as the list spells it (V-8), so one line has one spelling in the file.
        assertThat(ledger.record(draft("run-1", CLOCK).lineOfBusiness(" medicare ").build()).record().lineOfBusiness()).isEqualTo("Medicare");
        assertThat(ledger.record(draft("run-1b", CLOCK).lineOfBusiness("MEDI-CARE").build()).record().lineOfBusiness()).isEqualTo("Medicare");
        assertThat(ledger.record(draft("run-1c", CLOCK).lineOfBusiness("Medicare.").build()).record().lineOfBusiness()).isEqualTo("Medicare");
        // Text in the shape of a statement is not one (V-3): only the exact statements the tool writes are left alone.
        String shaped = "(zzlastname zzfirstname zzaddress lane, 1 characters)";
        assertThat(ledger.record(draft("run-1d", CLOCK).lineOfBusiness(shaped).build()).record().lineOfBusiness())
                .isEqualTo("(line of business not on the known list, " + shaped.length() + " characters)");
        String person = "Zzlastname, Zzfirstname";
        RunRecord unknown = ledger.record(draft("run-2", CLOCK).lineOfBusiness(person).build()).record();
        assertThat(unknown.lineOfBusiness()).isEqualTo("(line of business not on the known list, " + person.length() + " characters)");
        String withBirth = "Zzlastname, Zzfirstname 1961-07-23";
        RunRecord dated = ledger.record(draft("run-3", CLOCK).lineOfBusiness(withBirth).build()).record();
        assertThat(dated.lineOfBusiness()).isEqualTo("(" + RunRecord.READS_LIKE_AN_IDENTIFIER + ", " + withBirth.length() + " characters)");
        // A line that sounds right and is not on the list is not on the list.
        assertThat(ledger.record(draft("run-4", CLOCK).lineOfBusiness("Medicare Advantage").build()).record().lineOfBusiness())
                .startsWith("(line of business not on the known list, ");

        String file = text(dayFile(home, CLOCK.today()));
        assertThat(file).doesNotContain("Zzlastname").doesNotContain("Zzfirstname").doesNotContain("1961-07-23").doesNotContain("Advantage")
                .doesNotContain("zzaddress").doesNotContain("MEDI-CARE").doesNotContain("\"medicare\"");
        // What was written reads back as the record the receipt carried.
        assertThat(ledger.find("run-2")).contains(unknown);
        assertThat(ledger.find("run-3")).contains(dated);
    }

    @Test
    void aLineTheMemberApiNamesThroughAnAliasIsKnown() {
        JsonlRunLedger ledger = ledger(home, Map.of(), EpaProperties.defaults(),
                bundledList(Map.of("line-of-business-aliases.MCR[0]", "Medicare")));

        assertThat(ledger.record(draft("run-1", CLOCK).lineOfBusiness("MCR").build()).record().lineOfBusiness()).isEqualTo("MCR");
        assertThat(ledger.record(draft("run-2", CLOCK).lineOfBusiness("MCD").build()).record().lineOfBusiness())
                .isEqualTo("(line of business not on the known list, 3 characters)");
    }

    @Test
    void everyLineOfBusinessTheMockLookupsServeStillArrivesOnTheLine() {
        // A suite row and a replay take the line of business from the member lookup. On mock that is these fixtures.
        LookupFixtures fixtures = new LookupFixtures();
        MockMemberLookupClient lookups = new MockMemberLookupClient(fixtures, LookupProperties.defaults(), CLOCK);
        JsonlRunLedger ledger = ledger(home, Map.of("record-member-id", "true"), EpaProperties.defaults(), bundledList(Map.of()));
        Set<String> lines = new LinkedHashSet<>();
        List<String> written = new ArrayList<>();
        int run = 0;
        for (LookupFixtures.Fixture fixture : fixtures.members()) {
            MemberLookupResult found = fixtures.member(fixture.identifier()) == null ? null
                    : lookups.lookup(MemberIds.forms(fixture.identifier()));
            if (found == null || found.member() == null || found.member().lineOfBusiness() == null) {
                continue;
            }
            String line = found.member().lineOfBusiness();
            lines.add(line);
            RunRecord record = ledger.record(draft("run-" + run++, CLOCK).memberId(fixture.identifier()).lineOfBusiness(line).build()).record();
            written.add(record.lineOfBusiness());
            assertThat(record.lineOfBusiness()).as(fixture.source()).isEqualTo(line.strip());
            assertThat(record.memberId()).as(fixture.source()).isEqualTo(MemberIds.noSpace(fixture.identifier()));
        }
        assertThat(lines).as("the lines the fixtures serve").contains("Medicare", "Medicaid", "Commercial");
        assertThat(written).hasSize(run).noneMatch(line -> line.startsWith("("));
    }

    @Test
    void onMockOnlyTheToolsOwnSentinelsAndPlaceholdersAreRecordedInTheClear() {
        // Found by the member data audit (m5): whatever was typed on mock was written as typed.
        JsonlRunLedger mock = ledger(home, Map.of("record-member-id", "true"), EpaProperties.defaults(), LedgerFixtures.codeList());

        for (String synthetic : List.of("P32H-NO-AUTH", "S123456789", "S12345678   01", "P32H-DTR-ADAPT")) {
            RunRecord kept = mock.record(draft("run-" + synthetic.hashCode(), CLOCK).memberId(synthetic).build()).record();
            assertThat(kept.memberId()).as(synthetic).isEqualTo(MemberIds.noSpace(synthetic));
            assertThat(kept.dateOfService()).as(synthetic).isEqualTo("2026-03-20");
            assertThat(kept.memberToken()).as(synthetic).isNull();
        }
        String typed = "Zzlastname Zzfirstname 1961-07-23 77 Zzaddress Lane";
        for (String other : List.of(typed, "ABC123456   01", "s123456789", "123456789")) {
            RunRecord tokenised = mock.record(draft("run-" + other.hashCode(), CLOCK).memberId(other)
                    .correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001")).build()).record();
            assertThat(tokenised.memberId()).as(other).isNull();
            assertThat(tokenised.dateOfService()).as(other).isNull();
            assertThat(tokenised.memberToken()).as(other).matches("[0-9a-f]{64}");
            assertThat(mock.matches(tokenised.memberToken(), other)).as(other).isTrue();
            // The authorization number follows the member ID.
            assertThat(tokenised.correlation().get(RunRecord.CORR_AUTHORIZATION_REF)).as(other).matches("[0-9a-f]{64}");
        }
        String file = text(dayFile(home, CLOCK.today()));
        assertThat(file).doesNotContain("Zzlastname").doesNotContain("ABC123456").doesNotContain("s123456789").doesNotContain("\"123456789\"")
                .contains("\"memberId\":\"P32H-NO-AUTH\"").contains("\"memberId\":\"S1234567801\"");

        // On live an operator who sets record-member-id is honoured, whatever the ID looks like.
        JsonlRunLedger live = ledger(home.resolve("live"), Map.of("record-member-id", "true"), EpaProperties.of(Map.of("mode", "live")),
                LedgerFixtures.codeList());
        assertThat(live.record(draft("run-live", CLOCK).memberId("ABC123456   01").build()).record().memberId()).isEqualTo("ABC12345601");
    }

    @Test
    void theLedgerFolderAndEveryFileItCreatesAreForTheirOwnerOnly() throws IOException {
        // Found by the member data audit (m4): the folder and the day's file were readable by every account.
        assumeTrue(OwnerOnly.supported(), "this file system has no owner permissions to check");
        Path folder = home.resolve("fresh");
        JsonlRunLedger ledger = ledger(folder, Map.of(), EpaProperties.defaults(), LedgerFixtures.codeList());
        ledger.record(draft("run-1", CLOCK).build());
        CLOCK.advance(java.time.Duration.ofDays(1));
        ledger.record(draft("run-2", CLOCK).build());

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(ledgerDirectory(folder)))).isEqualTo("rwx------");
        try (var files = Files.list(ledgerDirectory(folder))) {
            List<Path> all = files.toList();
            assertThat(all).hasSize(2);
            for (Path file : all) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).as(file.getFileName().toString())
                        .isEqualTo("rw-------");
            }
        }
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(saltFile(folder)))).isEqualTo("rw-------");
        assertThat(dayFile(folder, CLOCK.today())).exists();
    }
}
