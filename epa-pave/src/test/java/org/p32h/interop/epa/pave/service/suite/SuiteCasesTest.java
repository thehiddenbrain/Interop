package org.p32h.interop.epa.pave.service.suite;

import org.p32h.interop.epa.pave.service.codelist.CsvRowsAdapter;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;

class SuiteCasesTest {

    private static final OutcomeCatalogue OUTCOMES = new OutcomeCatalogue()
            .register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS);
    private final SuiteFileReader reader = new SuiteFileReader(new CsvRowsAdapter());

    private SuiteDefinition csv(String text) {
        return SuiteCases.define("cases.csv", reader.read("cases.csv", text), true, OUTCOMES);
    }

    private SuiteDefinition json(String text) {
        return SuiteCases.define("cases.json", reader.read("cases.json", text), true, OUTCOMES);
    }

    private SuiteRow one(String header, String row) {
        SuiteDefinition definition = csv(header + "\n" + row);
        assertThat(definition.problems()).isEmpty();
        assertThat(definition.rows()).hasSize(1);
        return definition.rows().get(0);
    }

    @Test
    void aRowBecomesACaseWithEveryCellItGave() {
        SuiteDefinition definition = csv("caseId,protocol,hook,memberId,npi,codes,dateOfService,expected,scenarioId,testerRef,note\n"
                + "TC-1,crd,order-select,S123456789,1234567893,95810|95811,2026-03-20,NO_AUTH_REQUIRED,crd-01,QA-17,Sleep study");
        assertThat(definition.problems()).isEmpty();
        assertThat(definition.runnable()).isTrue();
        assertThat(definition.format()).isEqualTo("CSV");
        SuiteRow row = definition.rows().get(0);
        assertThat(row.problems()).isEmpty();
        assertThat(row.what()).isEqualTo("CRD order-select");
        assertThat(row.write()).isFalse();
        assertThat(row.expected()).isEqualTo("NO_AUTH_REQUIRED");
        CaseSpec spec = row.spec();
        assertThat(spec.caseId()).isEqualTo("TC-1");
        assertThat(spec.chain()).isFalse();
        assertThat(spec.operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(spec.memberId()).isEqualTo("S123456789");
        assertThat(spec.npi()).isEqualTo("1234567893");
        assertThat(spec.procedureCodes()).containsExactly("95810", "95811");
        assertThat(spec.dateOfService()).isEqualTo("2026-03-20");
        assertThat(spec.expected()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(spec.scenarioId()).isEqualTo("crd-01");
        assertThat(spec.testerRef()).isEqualTo("QA-17");
        assertThat(spec.note()).isEqualTo("Sleep study");
        // The suite run id and the pinned configuration are set when the suite starts, not when the file is read.
        assertThat(spec.suiteRunId()).isNull();
        assertThat(spec.configEpoch()).isNull();
        assertThat(spec.unrecognised()).isEmpty();
    }

    @Test
    void columnNamesAreReadWithoutRegardToCaseSpacesOrPunctuation() {
        SuiteRow row = one("Case ID,Type,Member ID,Provider NPI,Procedure Codes,Service Date,Expected Outcome,Ticket,Comment",
                "TC-1,crd,S123456789,1234567893,95810,2026-03-20,NO_AUTH_REQUIRED,QA-17,text");
        assertThat(row.problems()).isEmpty();
        assertThat(row.spec().caseId()).isEqualTo("TC-1");
        assertThat(row.spec().npi()).isEqualTo("1234567893");
        assertThat(row.spec().procedureCodes()).containsExactly("95810");
        assertThat(row.spec().dateOfService()).isEqualTo("2026-03-20");
        assertThat(row.spec().expected()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(row.spec().testerRef()).isEqualTo("QA-17");
        assertThat(row.spec().note()).isEqualTo("text");
        assertThat(one("member_id,PROTOCOL,procedure-code", "S123456789,crd,95810").problems()).isEmpty();
    }

    @Test
    void aColumnThisToolDoesNotKnowStopsTheWholeFile() {
        // Dropped in silence, "expectd" would make every row unverified without a word.
        SuiteDefinition definition = csv("caseId,protocol,memberId,expectd\nTC-1,crd,S123456789,NO_AUTH_REQUIRED");
        assertThat(definition.runnable()).isFalse();
        assertThat(definition.rows()).isEmpty();
        assertThat(definition.problems()).singleElement().asString()
                .startsWith("This tool does not know the column \"expectd\".")
                .contains("caseId, protocol, hook, operation, chain, memberId, npi, procedureCodes, dateOfService, expected, "
                        + "scenarioId, testerRef, note, claimId, subscriptionId")
                .contains("starts with #");
    }

    @Test
    void aColumnOfTheTestersOwnIsLeftAloneWhenItsNameStartsWithAHash() {
        SuiteRow row = one("caseId,protocol,memberId,#requirement,# owner", "TC-1,crd,S123456789,REQ-12,someone");
        assertThat(row.problems()).isEmpty();
        assertThat(row.spec().caseId()).isEqualTo("TC-1");
        SuiteDefinition asJson = json("[{\"protocol\":\"crd\",\"memberId\":\"S123456789\",\"#requirement\":\"REQ-12\"}]");
        assertThat(asJson.rows().get(0).problems()).isEmpty();
    }

    @Test
    void twoColumnsThatMeanTheSameThingAreRefused() {
        assertThat(csv("member,memberId,protocol\nS123456789,S123456789,crd").problems()).singleElement().asString()
                .isEqualTo("The columns \"member\" and \"memberId\" both mean memberId. Keep one.");
        SuiteDefinition asJson = json("[{\"protocol\":\"crd\",\"member\":\"S123456789\",\"memberId\":\"S123456789\"}]");
        assertThat(asJson.problems()).isEmpty();
        assertThat(asJson.rows().get(0).problems()).containsExactly("\"member\" and \"memberId\" both mean memberId. Keep one.");
    }

    @Test
    void aFileWithNoMemberIdColumnOrNoCasesCannotBeRun() {
        assertThat(csv("caseId,protocol\nTC-1,crd").problems()).containsExactly(
                "The file has no memberId column. Every case needs a member ID.");
        assertThat(csv("caseId,protocol,memberId\n").problems()).containsExactly(
                "The file has no cases. The first row names the columns and each row after it is one case.");
        assertThat(json("[]").problems()).containsExactly("The file has no cases.");
        assertThat(json("{\"cases\":[]}").runnable()).isFalse();
    }

    @Test
    void aJsonKeyThisToolDoesNotKnowMakesThatCaseInvalidAndTheOthersStillRun() {
        SuiteDefinition definition = json("[{\"protocol\":\"crd\",\"memberId\":\"S123456789\",\"expectd\":\"NO_AUTH_REQUIRED\"},"
                + "{\"protocol\":\"crd\",\"memberId\":\"S223456789\"}]");
        assertThat(definition.problems()).isEmpty();
        assertThat(definition.runnable()).isTrue();
        assertThat(definition.rows().get(0).problems()).singleElement().asString().startsWith("This tool does not know the key \"expectd\".");
        assertThat(definition.rows().get(0).spec()).isNull();
        assertThat(definition.rows().get(1).runnable()).isTrue();
    }

    @Test
    void whatARowRunsIsReadFromItsProtocolHookOperationAndChainCells() {
        Object[][] cases = {
                // protocol, hook, operation, chain -> chain?, operation, what, write
                {"crd", "", "", "", false, EpaOperation.CRD_ORDER_SIGN, "CRD order-sign", false},
                {"CRD", "Order-Select", "", "", false, EpaOperation.CRD_ORDER_SELECT, "CRD order-select", false},
                {"", "order_sign", "", "", false, EpaOperation.CRD_ORDER_SIGN, "CRD order-sign", false},
                {"", "", "crd-order-select", "", false, EpaOperation.CRD_ORDER_SELECT, "CRD order-select", false},
                {"crd", "order-select", "CRD_ORDER_SELECT", "no", false, EpaOperation.CRD_ORDER_SELECT, "CRD order-select", false},
                {"pas", "", "", "", false, EpaOperation.PAS_SUBMIT, "PAS submit", true},
                {"pas", "", "PAS_INQUIRE", "", false, EpaOperation.PAS_INQUIRE, "PAS inquire", false},
                {"dtr", "", "", "", false, EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "DTR questionnaire package", false},
                {"", "", "SUBSCRIPTION_GET", "", false, EpaOperation.SUBSCRIPTION_GET, "Subscription get", false},
                {"chain", "", "", "", true, null, "Chain, starting with CRD order-sign", true},
                {"chain", "order-select", "", "", true, EpaOperation.CRD_ORDER_SELECT, "Chain, starting with CRD order-select", true},
                {"", "", "", "yes", true, null, "Chain, starting with CRD order-sign", true},
                {"", "order-sign", "", "TRUE", true, EpaOperation.CRD_ORDER_SIGN, "Chain, starting with CRD order-sign", true},
                {"chain", "", "CRD_ORDER_SELECT", "y", true, EpaOperation.CRD_ORDER_SELECT, "Chain, starting with CRD order-select", true},
        };
        for (Object[] c : cases) {
            String label = c[0] + "/" + c[1] + "/" + c[2] + "/" + c[3];
            SuiteRow row = one("memberId,protocol,hook,operation,chain", "S123456789," + c[0] + "," + c[1] + "," + c[2] + "," + c[3]);
            assertThat(row.problems()).as(label).isEmpty();
            assertThat(row.chain()).as(label).isEqualTo(c[4]);
            assertThat(row.spec().chain()).as(label).isEqualTo(c[4]);
            assertThat(row.spec().operation()).as(label).isEqualTo(c[5]);
            assertThat(row.what()).as(label).isEqualTo(c[6]);
            assertThat(row.write()).as(label).isEqualTo(c[7]);
        }
    }

    @Test
    void aRowThatDoesNotSayWhatToRunOrSaysTwoThingsIsNotRun() {
        Object[][] cases = {
                {"", "", "", "", "The row does not say what to run. Give a protocol (crd, dtr, pas or chain), or an operation."},
                {"", "", "", "no", "The row does not say what to run. Give a protocol (crd, dtr, pas or chain), or an operation."},
                {"x12", "", "", "", "The protocol must be crd, dtr, pas or chain."},
                {"crd", "order-dispatch", "", "", "The hook must be order-sign or order-select."},
                {"", "", "CRD_ORDER", "", "The operation is not one this tool knows."},
                {"", "", "", "maybe", "\"chain\" must be yes or no."},
                {"pas", "", "CRD_ORDER_SIGN", "", "The protocol and the operation of this row disagree."},
                {"crd", "", "PAS_SUBMIT", "", "The protocol and the operation of this row disagree."},
                {"crd", "order-sign", "CRD_ORDER_SELECT", "", "The hook and the operation of this row disagree."},
                {"pas", "order-sign", "", "", "A hook applies to a CRD row or a chain. This row runs PAS submit."},
                {"chain", "", "PAS_SUBMIT", "", "A chain starts with a CRD hook, order-sign or order-select. This row names PAS submit."},
                {"chain", "order-sign", "CRD_ORDER_SELECT", "", "The hook and the operation of this row disagree."},
                {"pas", "", "", "yes", "The row is marked as a chain and its protocol names a single step."},
                {"", "", "SUBSCRIPTION_NOTIFICATION", "", "Subscription notification is not something a suite can run"},
                {"", "", "DTR_RESPONSE", "", "DTR questionnaire response is not something a suite can run"},
        };
        for (Object[] c : cases) {
            String label = c[0] + "/" + c[1] + "/" + c[2] + "/" + c[3];
            SuiteRow row = one("memberId,protocol,hook,operation,chain", "S123456789," + c[0] + "," + c[1] + "," + c[2] + "," + c[3]);
            assertThat(row.problems()).as(label).singleElement().asString().startsWith((String) c[4]);
            assertThat(row.spec()).as(label).isNull();
            assertThat(row.runnable()).as(label).isFalse();
        }
    }

    @Test
    void aCaseWithNoIdIsNamedByItsPositionSoTwoRunsOfOneFileCanBeCompared() {
        SuiteDefinition definition = csv("caseId,protocol,memberId\n,crd,S123456789\nTC-2,crd,S223456789\n,crd,S323456789");
        assertThat(definition.rows()).extracting(SuiteRow::caseId).containsExactly("case-1", "TC-2", "case-3");
        assertThat(definition.rows()).allMatch(SuiteRow::runnable);
    }

    @Test
    void aCaseIdUsedTwiceOrOfAShapeTheLedgerShouldNotHoldIsRefused() {
        SuiteDefinition definition = csv("caseId,protocol,memberId\nTC-1,crd,S123456789\ntc-1,crd,S223456789\n=cmd(),crd,S323456789\n"
                + "TC (4) v1.2 #a/b:c,crd,S423456789");
        assertThat(definition.rows().get(0).problems()).isEmpty();
        assertThat(definition.rows().get(1).problems()).containsExactly(
                "The case id \"tc-1\" is already used by case 1 of the file. Each case needs its own id, because results are matched "
                        + "to cases by it.");
        assertThat(definition.rows().get(2).problems()).containsExactly(
                "The case id may hold letters, digits, spaces and . _ : / # ( ) - and at most 64 characters.");
        // The refused id is not carried anywhere. The row is named by its position.
        assertThat(definition.rows().get(2).caseId()).isEqualTo("case-3");
        assertThat(definition.rows().get(3).problems()).isEmpty();
        assertThat(one("caseId,protocol,memberId", "x".repeat(65) + ",crd,S123456789").problems()).hasSize(1);
    }

    @Test
    void cellsThatCanBeCheckedWithoutSendingAreCheckedBeforeAnythingRuns() {
        String header = "protocol,memberId,npi,dateOfService,expected";
        assertThat(one(header, "crd,,1234567893,,").problems()).containsExactly("The row has no member ID.");
        assertThat(one(header, "crd,S123456789,1234567890,,").problems()).singleElement().asString().startsWith("The NPI is not valid");
        assertThat(one(header, "crd,S123456789,123456789,,").problems()).singleElement().asString().startsWith("The NPI is not valid");
        // A spreadsheet rewrites dates. A date in another order is refused, never guessed.
        assertThat(one(header, "crd,S123456789,,3/20/2026,").problems()).singleElement().asString()
                .startsWith("The date of service must be written as YYYY-MM-DD");
        assertThat(one(header, "crd,S123456789,,2026-02-30,").problems()).hasSize(1);
        assertThat(one(header, "crd,S123456789,,,APPROVED").problems()).singleElement().asString()
                .startsWith("\"APPROVED\" is not an outcome this operation can produce.");
        assertThat(one(header, "crd,S123456789,,,pa-needed=").problems()).singleElement().asString().contains("not a complete code assertion");
        assertThat(one(header, "crd,S123456789,,,pa-needed=no-auth; NO_AUTH_REQUIRED").problems()).isEmpty();
        // A row can have several problems, and each is said.
        assertThat(one(header, "crd,,12,20260320,NOPE").problems()).hasSize(4);
        // An NPI is not required here: whether an operation needs one is for the service that runs it.
        assertThat(one(header, "crd,S123456789,,,").problems()).isEmpty();
    }

    @Test
    void anExpectationForAProtocolWhoseOutcomesAreNotRegisteredIsLeftToTheServiceThatRunsIt() {
        assertThat(one("protocol,memberId,expected", "pas,S123456789,APPROVED").problems()).isEmpty();
    }

    @Test
    void aChainRowSaysWhichStepEachPartOfItsExpectationIsAbout() {
        // A chain makes up to three calls, each with outcomes of its own.
        assertThat(one("protocol,memberId,expected", "chain,S123456789,crd: AUTH_REQUIRED; pas: APPROVED").problems()).isEmpty();
        assertThat(one("protocol,memberId,expected", "chain,S123456789,CRD: pa-needed=auth-needed").problems()).isEmpty();
        // With no step named, the tool would have to guess which call the outcome belongs to.
        assertThat(one("protocol,memberId,expected", "chain,S123456789,AUTH_REQUIRED").problems()).singleElement().asString()
                .startsWith("\"AUTH_REQUIRED\" does not say which step of the chain it is about. Start it with crd:, dtr: or pas:");
        // And each part is checked against the outcomes of its own step, before anything is sent.
        assertThat(one("protocol,memberId,expected", "chain,S123456789,crd: APPROVED").problems()).singleElement().asString()
                .startsWith("For the CRD step: \"APPROVED\" is not an outcome this operation can produce.");
        assertThat(one("protocol,memberId,expected", "chain,S123456789,crd:").problems()).hasSize(1);
    }

    @Test
    void aScenarioCanBeChosenOnMockOnly() {
        SuiteTable table = reader.read("cases.csv", "protocol,memberId,scenario\ncrd,S123456789,crd-03");
        assertThat(SuiteCases.define("cases.csv", table, true, OUTCOMES).rows().get(0).spec().scenarioId()).isEqualTo("crd-03");
        assertThat(SuiteCases.define("cases.csv", table, false, OUTCOMES).rows().get(0).problems()).containsExactly(
                "The row chooses a scenario, which is possible on the mock profile only. On live the answer comes from the service.");
    }

    @Test
    void procedureCodesAreSeparatedByABarASemicolonACommaOrSpace() {
        assertThat(one("protocol,memberId,codes", "crd,S123456789, 95810|95811;E0601  0001U ").spec().procedureCodes())
                .containsExactly("95810", "95811", "E0601", "0001U");
        assertThat(json("[{\"protocol\":\"crd\",\"memberId\":\"S123456789\",\"codes\":\"95810, 95811\"}]").rows().get(0).spec()
                .procedureCodes()).containsExactly("95810", "95811");
        assertThat(json("[{\"protocol\":\"crd\",\"memberId\":\"S123456789\",\"procedureCodes\":[\"95810\",\"95811\"]}]").rows().get(0)
                .spec().procedureCodes()).containsExactly("95810", "95811");
        assertThat(one("protocol,memberId,codes", "crd,S123456789,").spec().procedureCodes()).isEmpty();
    }

    @Test
    void identifiersForAFollowUpReadAreCarriedUnderTheLedgersOwnKeys() {
        SuiteRow row = one("operation,memberId,claimId", "PAS_INQUIRE,S123456789,claim-1");
        assertThat(row.spec().correlation()).containsOnly(Map.entry(RunRecord.CORR_CLAIM_ID, "claim-1"));
        assertThat(json("[{\"operation\":\"PAS_INQUIRE\",\"memberId\":\"S123456789\",\"correlation\":{\"claimId\":\"claim-1\"}}]")
                .rows().get(0).spec().correlation()).containsOnly(Map.entry(RunRecord.CORR_CLAIM_ID, "claim-1"));
        assertThat(one("operation,memberId,claimId", "PAS_INQUIRE,S123456789,a claim id with spaces").problems()).singleElement()
                .asString().startsWith("The claimId may hold");
    }

    @Test
    void aRowTheReaderCouldNotReadIsNotRunWhateverElseItSays() {
        SuiteDefinition definition = csv("protocol,memberId,npi\ncrd,S123456789\ncrd,S223456789,1234567893");
        assertThat(definition.rows().get(0).runnable()).isFalse();
        assertThat(definition.rows().get(0).problems().get(0)).startsWith("The row has 2 cells");
        assertThat(definition.rows().get(1).runnable()).isTrue();
        assertThat(definition.runnable()).isTrue();
    }

    @Test
    void aSuiteHasAnUpperSize() {
        List<SuiteTable.SourceRow> rows = new ArrayList<>();
        for (int i = 1; i <= SuiteCases.MAX_CASES + 1; i++) {
            rows.add(new SuiteTable.SourceRow(i, Map.of("protocol", "crd", "memberId", "S123456789"), List.of()));
        }
        SuiteDefinition tooMany = SuiteCases.define("big.json", new SuiteTable(SuiteTable.JSON, List.of("protocol", "memberId"), rows,
                List.of()), true, OUTCOMES);
        assertThat(tooMany.problems()).containsExactly("The file has 5001 cases and this tool runs at most 5000 in one suite. Split the file.");
        assertThat(SuiteCases.define("big.json", new SuiteTable(SuiteTable.JSON, List.of("protocol", "memberId"),
                rows.subList(0, SuiteCases.MAX_CASES), List.of()), true, OUTCOMES).problems()).isEmpty();
    }

    @Test
    void noProblemQuotesAMemberId() {
        SuiteDefinition definition = csv("caseId,protocol,memberId,npi,dateOfService,expected,claimId\n"
                + "bad id!,x12,S123456789,12,tomorrow,NOPE,bad claim\nTC-1,crd,S123456789,,,,\nTC-1,crd,S123456789,,,,");
        StringBuilder all = new StringBuilder(definition.toString());
        for (SuiteRow row : definition.rows()) {
            all.append(row.problems());
        }
        assertThat(definition.rows().get(0).problems()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(all.toString()).doesNotContain("S123456789");
        // A row and a table print without their cells.
        assertThat(reader.read("cases.csv", "protocol,memberId\ncrd,S123456789").rows().toString()).doesNotContain("S123456789");
    }

    @Test
    void aFileWithNoHeaderRowIsSaidToHaveNoneAndNoCellOfItsFirstRowIsRepeated() {
        // Found by the member data audit (m3): the first case was read as the header and its cells quoted back as columns.
        String file = "S123456789,1234567893,95810,Zzlastname-Placeholder,1961-07-23\nS222222222,1234567893,95810,Zzother-Placeholder,1970-01-01";
        SuiteDefinition definition = csv(file);

        assertThat(definition.runnable()).isFalse();
        assertThat(definition.columns()).isEmpty();
        assertThat(definition.problems()).hasSize(1);
        assertThat(definition.problems().get(0)).startsWith("The file has no header row: most of its first row is not a column this tool knows");
        String all = definition.problems() + " " + definition.columns() + " " + definition.rows();
        assertThat(all).doesNotContain("S123456789").doesNotContain("Zzlastname").doesNotContain("1961-07-23").doesNotContain("95810");
        // A header whose every column is the tester's own is still a header, and a misspelt column is still named.
        assertThat(csv("#owner,#requirement\nQA,R-1").problems()).noneMatch(p -> p.startsWith("The file has no header row"));
        assertThat(csv("protocol,memberID,expectd\ncrd,S123456789,NO_AUTH_REQUIRED").problems()).anyMatch(p -> p.contains("\"expectd\""));
    }

    @Test
    void aRowWhoseNoteTesterReferenceOrExpectationIsTooLongIsNotRunAndSaysWhichAndNeverQuotesIt() {
        // Found by the member data audit (M5): a suite row wrote its note and tester reference with no limit.
        String pasted = "{\"\"resourceType\"\":\"\"Patient\"\",\"\"family\"\":\"\"Zzlastname\"\"}";
        String note = "x".repeat(4000) + pasted;
        String testerRef = "Zzlastname " + "r".repeat(200);
        String expected = "Zzlastname Zzfirstname 1961-07-23: pa-needed=auth-needed";
        SuiteDefinition definition = csv("caseId,protocol,memberId,note,testerRef,expected\n"
                + "TC-1,crd,S123456789,\"" + note + "\",,\n"
                + "TC-2,crd,S123456789,,\"" + testerRef + "\",\n"
                + "TC-3,crd,S123456789,,,\"" + expected + "\"\n"
                + "TC-4,crd,S123456789,\"" + "x".repeat(4000) + "\",\"" + "r".repeat(200) + "\",95810: pa-needed=auth-needed");

        assertThat(definition.problems()).isEmpty();
        List<SuiteRow> rows = definition.rows();
        assertThat(rows.get(0).problems()).containsExactly("A note can be at most 4000 characters, and this one has 4048. A note is kept "
                + "as written, so do not paste a request or a response into it.");
        assertThat(rows.get(1).problems()).containsExactly("A tester reference can be at most 200 characters, and this one has 211. It names "
                + "a ticket or a test step, and is kept as written.");
        assertThat(rows.get(2).problems()).containsExactly("Term 1 of the expectation names what it is about with a space in it. That part, "
                + "before the colon, is a procedure code that was asked about or a place in the response, such as 95810 or "
                + "ServiceRequest/sr-1, and never holds a space.");
        for (SuiteRow row : rows.subList(0, 3)) {
            assertThat(row.runnable()).isFalse();
            assertThat(row.problems().toString()).doesNotContain("Zzlastname").doesNotContain("1961-07-23");
        }
        // At the limits the row runs.
        assertThat(rows.get(3).problems()).isEmpty();
        assertThat(rows.get(3).spec().note()).hasSize(4000);
    }
}
