package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MEMBER_ID;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.NPI;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.dayFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledger;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.lines;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.recordingIds;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.text;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.PostalAddress;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * What is on disk after a real run.
 *
 * <p>Each test drives a whole CRD run, through the real service, the real mock client and scenario
 * files and the real ledger, with a member who has a name, a date of birth, an address and a
 * subscriber ID. It then reads the ledger file back as raw text. Nothing here looks at an object:
 * the question is what a person who opens the file would find in it.
 */
class LedgerPhiTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), LedgerFixtures.ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));

    private static final String GIVEN = "Placeholder-Given";
    private static final String SURNAME = "Placeholder-Surname";
    private static final String BIRTH = "1970-01-31";
    private static final String GENDER = "female";
    private static final String STREET = "1 Placeholder Street";
    private static final String CITY = "Sampletown";
    private static final String SUBSCRIBER = "SUB-PLACEHOLDER-1";
    private static final String DATE_OF_SERVICE = "2026-03-20";
    private static final String AUTHORIZATION = "PA-PLACEHOLDER-0001";

    private static final List<String> MEMBER_DATA = List.of(GIVEN, SURNAME, BIRTH, GENDER, STREET, CITY, SUBSCRIBER);

    /** Member data, and words that only a request or a response body would bring with it. */
    private static final List<String> NEVER_ON_A_LINE = List.of(GIVEN, SURNAME, BIRTH, GENDER, STREET, CITY, SUBSCRIBER, "Provider-Placeholder",
            "resourceType", "hookInstance", "systemActions", "prefetch", "valueCoding", "Clinical documentation is required",
            "Complete the payer questionnaire");

    @TempDir
    Path home;

    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final OnyxCrdClient mock = new MockOnyxCrdClient(scenarios, new MockExchanges(APP));

    private static CrdService service(OnyxCrdClient client, RunLedger ledger) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.defaults();
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class),
                CLOCK);
        return new CrdService(epa, FakeGateway.unconfigured(), client, new FakeCodeList(), support);
    }

    private static CrdRunRequest.Builder request(String memberId) {
        return CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId(memberId).firstName(GIVEN).lastName(SURNAME).dateOfBirth(BIRTH).gender(GENDER)
                        .lineOfBusiness("Medicare").subscriberId(SUBSCRIBER).address(new PostalAddress(STREET, null, CITY, "MA", "00000")).build())
                .provider(ProviderDetail.builder().npi(NPI).firstName("Pat").lastName("Provider-Placeholder").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended")))
                .dateOfService(DATE_OF_SERVICE).testerRef("QA-17").note("first run of the day");
    }

    private String onlyLine() {
        List<String> written = lines(dayFile(home, TODAY));
        assertThat(written).hasSize(1);
        return written.get(0);
    }

    /** A ledger line is flat: text, numbers, lists of text and one map of text. Wire JSON would be a structure inside it. */
    private static void assertFlat(String line) {
        JsonNode json = JsonSafe.parse(line).node();
        assertThat(json.isObject()).isTrue();
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(json)) {
            JsonNode value = property.getValue();
            if (value.isArray()) {
                assertThat(JsonSafe.elements(value)).as(property.getKey()).allMatch(JsonNode::isString);
            } else if (value.isObject()) {
                assertThat(property.getKey()).isEqualTo("correlation");
                assertThat(JsonSafe.properties(value)).allMatch(entry -> entry.getValue().isString());
            } else {
                assertThat(value.isContainer()).as(property.getKey()).isFalse();
            }
        }
        assertThat(line.chars().filter(c -> c == '{').count()).as("objects in the line").isLessThanOrEqualTo(2);
    }

    @Test
    void aRealRunLeavesNoMemberDataAndNoWireJsonOnTheLineAndWithIdsNotRecordedNoMemberIdEither() {
        JsonlRunLedger ledger = ledger(home, CLOCK);

        RunEnvelope<CrdDetail> result = service(mock, ledger).run(request(MEMBER_ID).scenarioId("crd-04-doc-dtr").build(), RunOrigin.manual(), null);

        // The run really did carry the member: the screen shows the request that held all of it.
        assertThat(result.wire().request().body()).contains(SURNAME).contains(BIRTH).contains(MEMBER_ID);
        assertThat(result.verdict().outcomeCode()).isEqualTo("DOCUMENTATION_REQUIRED");
        assertThat(result.ledger().recorded()).isTrue();

        String line = onlyLine();
        assertThat(line).doesNotContain(NEVER_ON_A_LINE);
        assertThat(line).doesNotContain(MEMBER_ID).doesNotContain(DATE_OF_SERVICE);
        assertFlat(line);
        // In place of the ID: a token, the same one every run of this member gets, and the last four characters.
        JsonNode written = JsonSafe.parse(line).node();
        assertThat(written.get("memberToken").asString()).matches("[0-9a-f]{64}").isEqualTo(ledger.tokenFor(MEMBER_ID));
        assertThat(written.get("memberIdLast4").asString()).isEqualTo("6789");
        assertThat(written.has("memberId")).isFalse();
        assertThat(written.has("dateOfService")).isFalse();
        // What the line is for is all there.
        assertThat(written.get("runId").asString()).isEqualTo(result.runId());
        assertThat(written.get("outcome").asString()).isEqualTo("DOCUMENTATION_REQUIRED");
        assertThat(written.get("scenarioId").asString()).isEqualTo("crd-04-doc-dtr");
        assertThat(written.get("procedureCodes").toString()).isEqualTo("[\"95810\"]");
        assertThat(written.get("lineOfBusiness").asString()).isEqualTo("Medicare");
        assertThat(written.get("testerRef").asString()).isEqualTo("QA-17");
        assertThat(written.get("npi").asString()).isEqualTo(NPI);
        // The receipt the browser is sent is the same record, so it holds no member ID either.
        assertThat(JsonSafe.mapper().writeValueAsString(result.ledger())).doesNotContain(MEMBER_ID).doesNotContain(DATE_OF_SERVICE)
                .doesNotContain(NEVER_ON_A_LINE);
    }

    @Test
    void withIdsRecordedTheLineGainsTheMemberIdAndTheDateOfServiceAndNothingElseAboutTheMember() {
        JsonlRunLedger ledger = recordingIds(home, CLOCK);

        service(mock, ledger).run(request("S1234 56789").scenarioId("crd-04-doc-dtr").build(), RunOrigin.manual(), null);

        String line = onlyLine();
        assertThat(line).contains("\"memberId\":\"" + MEMBER_ID + "\"").contains("\"dateOfService\":\"" + DATE_OF_SERVICE + "\"");
        assertThat(line).doesNotContain(NEVER_ON_A_LINE);
        assertFlat(line);
    }

    @Test
    void anAuthorizationNumberTheServiceReturnedIsOnTheLineOnlyAsATokenAndCanStillBeLookedUp() {
        JsonlRunLedger ledger = ledger(home, CLOCK);

        RunEnvelope<CrdDetail> result = service(mock, ledger).run(request("P32H-PA-SAT").build(), RunOrigin.manual(), null);

        assertThat(result.verdict().outcomeCode()).isEqualTo("AUTH_SATISFIED");
        String line = onlyLine();
        assertThat(line).doesNotContain(AUTHORIZATION).doesNotContain("P32H-PA-SAT").doesNotContain(NEVER_ON_A_LINE);
        assertFlat(line);
        assertThat(JsonSafe.parse(line).node().get("correlation").get(RunRecord.CORR_AUTHORIZATION_REF).asString())
                .matches("[0-9a-f]{64}").isEqualTo(ledger.tokenFor(AUTHORIZATION));
        // A notification that names the number days later still finds the run.
        assertThat(ledger.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, AUTHORIZATION))).extracting(RunRecord::runId)
                .containsExactly(result.runId());

        // On an installation that records member IDs the number is kept as it is.
        JsonlRunLedger recording = recordingIds(home.resolve("recording"), CLOCK);
        service(mock, recording).run(request("P32H-PA-SAT").build(), RunOrigin.manual(), null);
        assertThat(text(dayFile(home.resolve("recording"), TODAY))).contains("\"authorizationRef\":\"" + AUTHORIZATION + "\"");
    }

    @Test
    void aResponseThatPutsMemberDataWhereCodesBelongLeavesNoneOfItOnTheLine() {
        // A sentence in place of a code, an object in place of a code, a person in place of a vendor
        // and a fragment of JSON in place of a questionnaire url.
        String extension = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
        String body = "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\","
                + "\"extension\":[{\"url\":\"" + extension + "\",\"extension\":["
                + "{\"url\":\"pa-needed\",\"valueString\":\"Auth needed for " + SURNAME + " born " + BIRTH + "\"},"
                + "{\"url\":\"doc-needed\",\"valueCoding\":{\"code\":{\"patient\":\"" + SURNAME + "\",\"subscriberId\":\"" + SUBSCRIBER + "\"}}},"
                + "{\"url\":\"questionnaire\",\"valueCanonical\":\"see {\\\"patient\\\":\\\"" + SURNAME + "\\\"}\"},"
                + "{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"delegated-vendor\"}]}},"
                + "{\"url\":\"value\",\"valueString\":\"" + SURNAME + ", authorized representative, " + STREET + "\"}]}]}]}}]}";
        VendorExchange exchange = TestExchanges.crd(200, body);
        OnyxCrdClient answeringWithThat = new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText requestBody, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return exchange;
            }
        };
        JsonlRunLedger ledger = ledger(home, CLOCK);

        RunEnvelope<CrdDetail> result = service(answeringWithThat, ledger).run(request(MEMBER_ID).build(), RunOrigin.manual(), null);

        // On screen the tester sees what came back, because that is the evidence.
        assertThat(result.detail().coverageInformation().get(0).paNeeded().code()).contains(SURNAME);
        String line = onlyLine();
        assertThat(line).doesNotContain(NEVER_ON_A_LINE).doesNotContain("authorized representative").doesNotContain("subscriberId")
                .doesNotContain(MEMBER_ID);
        assertFlat(line);
        // The line still says that something was there.
        assertThat(line).contains("pa-needed=(not a code, ").contains("(vendor not on the known list, ").contains("(not recorded, ");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void nothingTheLedgerLogsHoldsMemberDataWhetherTheWriteWorksOrFails(CapturedOutput output) throws IOException {
        JsonlRunLedger ledger = ledger(home, CLOCK);
        CrdService service = service(mock, ledger);
        service.run(request(MEMBER_ID).scenarioId("crd-04-doc-dtr").build(), RunOrigin.manual(), null);
        // Then a write that fails, which is the one thing the ledger logs about a run.
        JsonlRunLedger failing = ledger(home.resolve("failing"), CLOCK);
        Files.createDirectory(dayFile(home.resolve("failing"), TODAY));
        RunEnvelope<CrdDetail> unkept = service(mock, failing).run(request("P32H-PA-SAT").build(), RunOrigin.manual(), null);

        assertThat(unkept.ledger().recorded()).isFalse();
        assertThat(output.getAll()).contains("A ledger write failed for " + dayFile(home.resolve("failing"), TODAY));
        assertThat(output.getAll()).doesNotContain(MEMBER_DATA);
        assertThat(output.getAll()).doesNotContain(MEMBER_ID).doesNotContain("P32H-PA-SAT").doesNotContain(AUTHORIZATION)
                .doesNotContain(ledger.tokenFor(MEMBER_ID));
        // The message the tester is shown names the file and nothing about the member.
        assertThat(unkept.ledger().message()).contains("NOT recorded").doesNotContain("P32H-PA-SAT").doesNotContain(SURNAME);
        assertThat(JsonSafe.mapper().writeValueAsString(unkept.ledger())).doesNotContain("P32H-PA-SAT").doesNotContain(AUTHORIZATION);
    }
}
