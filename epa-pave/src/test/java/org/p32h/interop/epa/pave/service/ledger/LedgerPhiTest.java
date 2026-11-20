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

    /** A service that answers every hook with one coverage information extension holding these sub-extensions. */
    private static OnyxCrdClient answering(String subExtensions) {
        String extension = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
        String body = "{\"cards\":[],\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\","
                + "\"id\":\"service-request-1\",\"extension\":[{\"url\":\"" + extension + "\",\"extension\":[" + subExtensions + "]}]}}]}";
        VendorExchange exchange = TestExchanges.crd(200, body);
        return new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText requestBody, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return exchange;
            }
        };
    }

    @Test
    void aQuestionnaireAddressWithAQueryOrWordsInPlaceOfOneIsNotKeptAndAPlainCanonicalIs() {
        // Found by the member data audit (M1): an address with a query, and words, went through as they came.
        String withQuery = "https://payer.example.invalid/Questionnaire/sleep?patient=" + MEMBER_ID + "&dob=" + BIRTH;
        String words = "https://payer.example.invalid/Questionnaire/q1 " + SURNAME + " " + GIVEN + " " + BIRTH;
        List<String> refused = List.of(withQuery, words, "https://payer.example.invalid/Questionnaire/q1#" + SURNAME,
                "https://" + SURNAME + "@payer.example.invalid/Questionnaire/q1", "https://payer.example.invalid/Questionnaire/" + MEMBER_ID,
                "https://payer.example.invalid/Questionnaire/" + SURNAME + "%20" + BIRTH);
        for (int i = 0; i < refused.size(); i++) {
            String canonical = refused.get(i);
            Path folder = home.resolve("q" + i);
            JsonlRunLedger ledger = recordingIds(folder, CLOCK);
            service(answering("{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"questionnaire\",\"valueCanonical\":\""
                    + canonical + "\"}"), ledger).run(request(MEMBER_ID).build(), RunOrigin.manual(), null);

            String line = lines(dayFile(folder, TODAY)).get(0);
            assertThat(line).as(canonical).doesNotContain(SURNAME).doesNotContain(BIRTH).doesNotContain("Questionnaire/sleep")
                    .contains("\"questionnaire\":\"(not recorded, " + canonical.length() + " characters)\"");
            // With member IDs recorded the ID is on the line once, in its own field, and nowhere else.
            assertThat(line.split(MEMBER_ID, -1)).as(canonical).hasSize(2);
            assertFlat(line);
        }

        String plain = "https://example.org/p32h-placeholder/Questionnaire/sleep-study|1.2.0";
        JsonlRunLedger ledger = ledger(home.resolve("plain"), CLOCK);
        service(answering("{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"questionnaire\",\"valueCanonical\":\"" + plain + "\"},"
                + "{\"url\":\"questionnaire\",\"valueCanonical\":\"urn:oid:2.16.840.1.113883.4.642\"}"), ledger)
                .run(request(MEMBER_ID).build(), RunOrigin.manual(), null);
        assertThat(lines(dayFile(home.resolve("plain"), TODAY)).get(0))
                .contains("\"questionnaire\":\"" + plain + " urn:oid:2.16.840.1.113883.4.642\"");
    }

    @Test
    void aValueInThePlaceOfACodeThatReadsLikeAnIdentifierOrADateIsNotKeptAndCodesOfTheVocabulariesAre() {
        // Found by the member data audit (M2): one unbroken token was kept as a code, whatever it was.
        JsonlRunLedger ledger = ledger(home, CLOCK);
        RunEnvelope<CrdDetail> result = service(answering("{\"url\":\"covered\",\"valueCode\":\"covered\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},"
                + "{\"url\":\"doc-needed\",\"valueCode\":\"" + BIRTH + "\"},"
                + "{\"url\":\"doc-purpose\",\"valueCode\":\"SUBZZ9988776\"},"
                + "{\"url\":\"info-needed\",\"valueCode\":\"" + MEMBER_ID + "\"},"
                + "{\"url\":\"info-needed\",\"valueCode\":\"01/31/1970\"}"), ledger).run(request(MEMBER_ID).build(), RunOrigin.manual(), null);

        // The screen still shows what came back.
        assertThat(result.detail().coverageInformation().get(0).docNeeded().code()).isEqualTo(BIRTH);
        String line = onlyLine();
        assertThat(line).doesNotContain(BIRTH).doesNotContain("SUBZZ9988776").doesNotContain(MEMBER_ID).doesNotContain("01/31/1970");
        assertThat(JsonSafe.parse(line).node().get("coverageCodes").toString()).isEqualTo("[\"covered=covered\",\"pa-needed=auth-needed\","
                + "\"doc-needed=(not recorded: reads like an identifier or a date, 10 characters)\","
                + "\"doc-purpose=(not recorded: reads like an identifier or a date, 12 characters)\","
                + "\"info-needed=(not recorded: reads like an identifier or a date, 10 characters)\","
                + "\"info-needed=(not recorded: reads like an identifier or a date, 10 characters)\"]");
        assertFlat(line);
    }

    @Test
    void theMemberIdOfTheRunIsNotKeptAnywhereAResponseCanReachEvenInTheShapeOfACode() {
        // A member ID with no long run of digits still reads as a code. The record knows the ID of its own run.
        String memberId = "P32H-ZZ-MEMBER";
        JsonlRunLedger ledger = ledger(home, CLOCK);
        service(answering("{\"url\":\"covered\",\"valueCode\":\"" + memberId.toLowerCase(java.util.Locale.ROOT) + "\"},"
                + "{\"url\":\"questionnaire\",\"valueCanonical\":\"https://example.org/Questionnaire/" + memberId + "\"}"), ledger)
                .run(request(memberId).build(), RunOrigin.manual(), null);

        String line = onlyLine();
        assertThat(line.toUpperCase(java.util.Locale.ROOT)).doesNotContain(memberId);
        assertThat(line).contains("covered=(not recorded: reads like an identifier or a date, 14 characters)")
                .contains("\"questionnaire\":\"(not recorded, ");
    }

    @Test
    void aQuestionnaireAddressWhosePathHoldsTheMemberIdWrittenWithPunctuationOrADateIsNotKept() {
        // Found by the verifier (V-1): the shape check let a path or a urn through that held the
        // member ID with separators, or a name and a date of birth. Member IDs are tokenised here.
        List<String> refused = List.of("https://payer.example.invalid/Questionnaire/S-123456789",
                "https://payer.example.invalid/Questionnaire/S12345-6789",
                "https://payer.example.invalid/Questionnaire/" + SURNAME + "." + GIVEN + "." + BIRTH,
                "https://payer.example.invalid/Questionnaire/q1|" + BIRTH,
                "urn:zz:" + SURNAME + "-" + GIVEN + "-19700131-1-Placeholder-Street",
                "https://payer.example.invalid/Questionnaire/q1 https://payer.example.invalid/Questionnaire/Jan-31-1970");
        for (int i = 0; i < refused.size(); i++) {
            String canonical = refused.get(i);
            Path folder = home.resolve("p" + i);
            JsonlRunLedger ledger = ledger(folder, CLOCK);
            service(answering("{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"questionnaire\",\"valueCanonical\":\""
                    + canonical + "\"}"), ledger).run(request(MEMBER_ID).build(), RunOrigin.manual(), null);

            String line = lines(dayFile(folder, TODAY)).get(0);
            assertThat(line).as(canonical).doesNotContain(SURNAME).doesNotContain(BIRTH).doesNotContain("19700131")
                    .doesNotContain("S-123456789").doesNotContain("S12345-6789").doesNotContain("Jan-31")
                    .contains("\"questionnaire\":\"(not recorded, " + canonical.length() + " characters)\"");
            assertThat(JsonSafe.parse(line).node().has("memberId")).isFalse();
        }

        // What the scenario files and real payers use is kept: words in the path, a version, an
        // OID whose arcs hold long runs of digits, and a urn:uuid.
        String kept = "https://example.org/p32h-placeholder/Questionnaire/genetic-testing-criteria|2.1.0 "
                + "urn:oid:2.16.840.1.113883.4.642 urn:uuid:00000000-0000-4000-8000-0000000000a1 "
                + "http://127.0.0.1:18061/fhir/Questionnaire/home-o2-r4|3.0";
        JsonlRunLedger ledger = ledger(home.resolve("kept"), CLOCK);
        service(answering("{\"url\":\"covered\",\"valueCode\":\"covered\"}," + java.util.Arrays.stream(kept.split(" "))
                .map(q -> "{\"url\":\"questionnaire\",\"valueCanonical\":\"" + q + "\"}").collect(java.util.stream.Collectors.joining(","))), ledger)
                .run(request(MEMBER_ID).build(), RunOrigin.manual(), null);
        assertThat(lines(dayFile(home.resolve("kept"), TODAY)).get(0)).contains("\"questionnaire\":\"" + kept + "\"");
    }

    @Test
    void theMemberIdWrittenWithPunctuationOrADateWrittenWithAMonthNameIsNotKeptAsACode() {
        // Found by the verifier (V-2): only the plain no-space ID and numeric dates were caught.
        JsonlRunLedger ledger = ledger(home, CLOCK);
        service(answering("{\"url\":\"covered\",\"valueCode\":\"S-123-456-789\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"Jan-31-1970\"},"
                + "{\"url\":\"doc-needed\",\"valueCode\":\"31Jan1970\"},"
                + "{\"url\":\"doc-purpose\",\"valueCode\":\"31-January-70\"},"
                + "{\"url\":\"info-needed\",\"valueCode\":\"S1234.5678.9\"}"), ledger).run(request(MEMBER_ID).build(), RunOrigin.manual(), null);

        String line = onlyLine();
        assertThat(line).doesNotContain("S-123-456-789").doesNotContain("Jan-31-1970").doesNotContain("31Jan1970")
                .doesNotContain("31-January-70").doesNotContain("S1234.5678.9");
        String statement = "(not recorded: reads like an identifier or a date, ";
        assertThat(JsonSafe.parse(line).node().get("coverageCodes").toString()).isEqualTo("[\"covered=" + statement + "13 characters)\","
                + "\"pa-needed=" + statement + "11 characters)\",\"doc-needed=" + statement + "9 characters)\","
                + "\"doc-purpose=" + statement + "13 characters)\",\"info-needed=" + statement + "12 characters)\"]");
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

    @Test
    void aRunWhoseFreeTextHoldsMemberDataTheRequestCarriesIsRefusedOnThatFieldBeforeAnythingIsSentOrWritten() {
        // Found by the second member data audit (F1): the note defeated the token on the line beside it.
        JsonlRunLedger ledger = ledger(home, CLOCK);
        CrdService service = service(mock, ledger);
        Map<String, List<String>> cases = new java.util.LinkedHashMap<>();
        cases.put("note", List.of("Called about s123456789 today", "ID S12345 6789", "xxS123456789yy"));
        cases.put("testerRef", List.of("QA " + SURNAME.toLowerCase(java.util.Locale.ROOT), "QA 01/31/1970", "QA 19700131"));
        cases.put("expected", List.of(MEMBER_ID + ": covered=covered", GIVEN + ": covered=covered"));
        cases.forEach((field, values) -> values.forEach(value -> {
            CrdRunRequest.Builder request = request(MEMBER_ID).scenarioId("crd-04-doc-dtr").note(null).testerRef(null);
            switch (field) {
                case "note" -> request.note(value);
                case "testerRef" -> request.testerRef(value);
                default -> request.expected(value);
            }
            org.p32h.interop.epa.pave.service.error.ValidationException refused = org.assertj.core.api.Assertions.catchThrowableOfType(
                    org.p32h.interop.epa.pave.service.error.ValidationException.class,
                    () -> service.run(request.build(), RunOrigin.manual(), null));
            assertThat(refused).as(value).isNotNull();
            assertThat(refused.fieldErrors()).as(value).anySatisfy(error -> {
                assertThat(error.field()).isEqualTo(field);
                assertThat(error.message()).contains("may not hold member data").contains("Nothing was sent.")
                        .doesNotContain(MEMBER_ID).doesNotContain(SURNAME).doesNotContain(GIVEN).doesNotContain("1970");
            });
        }));
        org.p32h.interop.epa.pave.service.error.ValidationException named = org.assertj.core.api.Assertions.catchThrowableOfType(
                org.p32h.interop.epa.pave.service.error.ValidationException.class,
                () -> service.run(request(MEMBER_ID).note("Spoke to " + GIVEN + " at " + STREET + ", born 1970-01-31, subscriber "
                        + SUBSCRIBER).build(), RunOrigin.manual(), null));
        // "Placeholder", one half of the surname, is in the street as well (the independent check, N5).
        assertThat(named.fieldErrors()).singleElement().satisfies(error -> assertThat(error.message()).isEqualTo("The note holds the "
                + "member's first name and the member's last name and the member's date of birth and the member's subscriber ID and the "
                + "member's address. The ledger "
                + "keeps it as written and may not hold member data, so leave them out. Nothing was sent."));
        assertThat(Files.exists(dayFile(home, TODAY))).isFalse();

        // Text that names nobody the request carries is kept as typed.
        RunEnvelope<CrdDetail> kept = service.run(request(MEMBER_ID).scenarioId("crd-04-doc-dtr").note("second try, ticket 6789")
                .testerRef("QA-17").build(), RunOrigin.manual(), null);
        assertThat(kept.ledger().recorded()).isTrue();
        assertThat(onlyLine()).contains("\"note\":\"second try, ticket 6789\"");
    }

    @Test
    void aBodyOfTheTestersOwnSentWithNoMemberHasItsNoteCheckedAgainstThePatientTheBodyNamesAndNothingIsWritten() {
        // Found by the independent check of the member data fixes (N1): the screen sends no member
        // with a body of the tester's own, so this note was checked against nothing.
        JsonlRunLedger ledger = ledger(home, CLOCK);
        CrdService service = service(mock, ledger);
        String body = "{\"hook\":\"order-sign\",\"hookInstance\":\"x\",\"context\":{\"patientId\":\"" + MEMBER_ID + "\"}}";

        org.p32h.interop.epa.pave.service.error.ValidationException refused = org.assertj.core.api.Assertions.catchThrowableOfType(
                org.p32h.interop.epa.pave.service.error.ValidationException.class,
                () -> service.run(CrdRunRequest.builder().rawBody(body).note("own body for " + MEMBER_ID).build(), RunOrigin.manual(), null));

        assertThat(refused).isNotNull();
        assertThat(refused.fieldErrors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("note");
            assertThat(error.message()).contains("a member ID the request body carries").doesNotContain(MEMBER_ID);
        });
        assertThat(Files.exists(dayFile(home, TODAY))).isFalse();

        service.run(CrdRunRequest.builder().rawBody(body).note("own body, ticket 4471").build(), RunOrigin.manual(), null);
        assertThat(onlyLine()).contains("\"note\":\"own body, ticket 4471\"").doesNotContain(MEMBER_ID);
    }

    @Test
    void theRunsOwnMemberIdTypedAsAProcedureCodeIsNotOnTheLineInTheCodesOrAsTheSubjectOfACoverageCode() {
        // Found by the second member data audit (F3).
        JsonlRunLedger ledger = ledger(home, CLOCK);
        service(mock, ledger).run(request(MEMBER_ID).scenarioId("crd-04-doc-dtr").procedureCodes(List.of(new CodeInput(MEMBER_ID, null, null),
                new CodeInput("95810", null, "Sleep study, attended"))).build(), RunOrigin.manual(), null);

        String line = onlyLine();
        assertThat(line).doesNotContain(MEMBER_ID).contains("(not a code, 10 characters)").contains("95810");
    }
}
