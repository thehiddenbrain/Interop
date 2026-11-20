package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the ledger tests share: a clock that can be moved, a ledger built over a temporary folder
 * the way the application builds one, and a draft with the fields a real run fills in.
 */
public final class LedgerFixtures {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");
    public static final String MEMBER_ID = "S123456789";
    public static final String NPI = "1234567893";

    private LedgerFixtures() {
    }

    /** A clock that stands still until it is told to move, so a test decides which side of midnight a run is on. */
    public static final class MovableClock extends Clock {

        private Instant now;
        private final ZoneId zone;

        public MovableClock(String instant) {
            this(Instant.parse(instant), ZONE);
        }

        private MovableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        public void set(String instant) {
            this.now = Instant.parse(instant);
        }

        public void advance(Duration by) {
            this.now = now.plus(by);
        }

        public LocalDate today() {
            return LocalDate.now(this);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new MovableClock(now, other);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Late morning in New York on 2026-03-10, well away from midnight. */
    public static MovableClock clock() {
        return new MovableClock("2026-03-10T15:00:00Z");
    }

    public static Path ledgerDirectory(Path home) {
        return home.resolve("run-history");
    }

    public static Path saltFile(Path home) {
        return home.resolve("config-store").resolve(LedgerTokens.DEFAULT_FILE_NAME);
    }

    public static Path dayFile(Path home, LocalDate day) {
        return ledgerDirectory(home).resolve(DayFiles.nameFor(day));
    }

    /** The tracking properties for a ledger under {@code home}, with the given overrides in the form application.yml uses. */
    public static TrackingProperties tracking(Path home, Map<String, String> overrides) {
        Map<String, String> properties = new HashMap<>();
        properties.put("directory", ledgerDirectory(home).toString());
        properties.putAll(overrides);
        return TrackingProperties.of(properties);
    }

    public static ConfigStoreProperties configStore(Path home) {
        return ConfigStoreProperties.of(Map.of("directory", home.resolve("config-store").toString()));
    }

    /** The lines of business the ledger knows in these tests: the three the bundled code list names. */
    public static ProcedureCodeDirectory codeList() {
        return new FakeCodeList().row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("95810", AuthRequirement.REQUIRED, "Medicaid", null)
                .row("E0601", AuthRequirement.NOT_REQUIRED, "Commercial", null);
    }

    /** A ledger on the mock profile, with the configuration defaults: member IDs are not recorded, NPIs are. */
    public static JsonlRunLedger ledger(Path home, Clock clock, Map<String, String> overrides) {
        return new JsonlRunLedger(tracking(home, overrides), configStore(home), EpaProperties.defaults(), clock, codeList());
    }

    public static JsonlRunLedger ledger(Path home, Clock clock) {
        return ledger(home, clock, Map.of());
    }

    /** A ledger that records member IDs, as the mock profile does. */
    public static JsonlRunLedger recordingIds(Path home, Clock clock) {
        return ledger(home, clock, Map.of("record-member-id", "true"));
    }

    /** A draft as a CRD run hands it to the ledger: the real member ID, the NPI and the date of service are on it. */
    public static RunRecord.Builder draft(String runId, Clock clock) {
        return RunRecord.builder().runId(runId).timestamp(RunStamps.timestamp(clock)).protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).target("onyx").profile("mock").environment("Onyx Sandbox", EnvironmentKind.SANDBOX)
                .scenarioId("crd-02-no-auth").procedureCodes(List.of("95810")).lineOfBusiness("Medicare")
                .outcome(CrdOutcome.NO_AUTH_REQUIRED).coverageCodes(List.of("covered=covered", "pa-needed=no-auth")).httpStatus(200)
                .elapsedMillis(42).expected("NO_AUTH_REQUIRED").result(TestResult.PASS).hook("order-sign")
                .nextAction(NextAction.FLOW_ENDS).memberId(MEMBER_ID).npi(NPI).dateOfService("2026-03-20");
    }

    /** The lines of a file as text, without the line breaks. */
    public static List<String> lines(Path file) {
        return List.of(text(file).split("\n", -1)).stream().filter(line -> !line.isEmpty()).toList();
    }

    public static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
