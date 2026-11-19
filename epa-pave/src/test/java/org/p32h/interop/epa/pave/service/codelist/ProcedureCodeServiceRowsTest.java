package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;

/**
 * What becomes of a row: which cells are read and how, what is served as written, and what the
 * self-check says about everything that was odd. The rule under all of it: the list is loaded as
 * it is, and a value it does not state is never filled in.
 */
class ProcedureCodeServiceRowsTest {

    @TempDir
    Path directory;

    private ProcedureCode only(ProcedureCodeService service, String code) {
        List<ProcedureCode> rows = service.rowsFor(code, null, null);
        assertThat(rows).as("rows for " + code).hasSize(1);
        return rows.get(0);
    }

    // ---- the authorization flag ----

    @Test
    void everySpellingOfYesAndNoIsReadAndTheCellIsKeptBesideIt() {
        String[] yes = {"required", "yes", "y", "true", "t", "1", "pa", "pa required", "auth required", "authorization required",
                "prior auth required", "prior authorization required", "needed", "auth needed", "YES", "Y", "Required", "PA_REQUIRED",
                "prior-auth-required", "Prior Authorization Required"};
        String[] no = {"not required", "no", "n", "false", "f", "0", "no pa", "pa not required", "no auth", "auth not required",
                "authorization not required", "no prior auth", "prior auth not required", "prior authorization not required", "not needed",
                "none", "NO", "N", "Not Required", "NOT_REQUIRED", "No PA", "not-needed"};
        StringBuilder csv = new StringBuilder("Code,PA Required\n");
        for (int i = 0; i < yes.length; i++) {
            csv.append("Y").append(i).append(",\"").append(yes[i]).append("\"\n");
        }
        for (int i = 0; i < no.length; i++) {
            csv.append("N").append(i).append(",\"").append(no[i]).append("\"\n");
        }
        ProcedureCodeService service = TestCodeLists.csv(directory, csv.toString());

        for (int i = 0; i < yes.length; i++) {
            ProcedureCode row = only(service, "Y" + i);
            assertThat(row.authRequired()).as(yes[i]).isEqualTo(AuthRequirement.REQUIRED);
            assertThat(row.authRequiredSource()).isEqualTo(yes[i]);
        }
        for (int i = 0; i < no.length; i++) {
            ProcedureCode row = only(service, "N" + i);
            assertThat(row.authRequired()).as(no[i]).isEqualTo(AuthRequirement.NOT_REQUIRED);
            assertThat(row.authRequiredSource()).isEqualTo(no[i]);
        }
        CodeListSelfCheck check = service.selfCheck();
        assertThat(check.rowCount()).isEqualTo(yes.length + no.length);
        assertThat(check.statingAuthRequirement()).isEqualTo(yes.length + no.length);
        assertThat(check.warnings()).isEmpty();
    }

    @Test
    void aBlankFlagIsUnknownAndIsNeitherANoNorAWarning() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA Required,Notes\nA1,,x\nA2,   ,x\nA3,\"\",x\nA4\nA5,unknown,x\n");
        for (String code : new String[] {"A1", "A2", "A3", "A4"}) {
            ProcedureCode row = only(service, code);
            assertThat(row.authRequired()).as(code).isEqualTo(AuthRequirement.UNKNOWN);
            // No cell text to show, and no empty string pretending to be one.
            assertThat(row.authRequiredSource()).as(code).isNull();
        }
        // A list that writes "unknown" has said so itself. It is kept as its source and is not reported as unreadable.
        assertThat(only(service, "A5").authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(only(service, "A5").authRequiredSource()).isEqualTo("unknown");
        assertThat(service.selfCheck().statingAuthRequirement()).isZero();
        // The only thing to say is that row A4 stops early. Nothing is said about the flags.
        assertThat(service.selfCheck().warnings()).singleElement().asString().startsWith("1 row has fewer cells than the header row");
    }

    @Test
    void textThatIsNotAYesOrANoStaysUnknownWithItsSourceAndIsListedForSomeoneToDecide() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA Required\n"
                + "A1,See policy\nA2,See policy\nA3,X\nA4,2\nA5,Yes*\nA6,No PA Required\nA7,-\nA8,Not\u00A0Required\nA9,See policy\nB1,Yes\nB2,No\n");

        Map<String, String> unread = Map.of("A1", "See policy", "A3", "X", "A4", "2", "A5", "Yes*", "A6", "No PA Required", "A7", "-",
                "A8", "Not\u00A0Required");
        unread.forEach((code, cell) -> {
            ProcedureCode row = only(service, code);
            // Never a guess: not "probably yes" for Yes*, and not "no" for a dash.
            assertThat(row.authRequired()).as(cell).isEqualTo(AuthRequirement.UNKNOWN);
            assertThat(row.authRequiredSource()).as(cell).isEqualTo(cell);
        });
        assertThat(service.selfCheck().statingAuthRequirement()).isEqualTo(2);
        // Each distinct value once, with its rows, the most frequent first. The cell that looks
        // like "Not Required" shows the no-break space that keeps it from being read as one.
        assertThat(service.selfCheck().warnings()).containsExactly("The prior authorization column holds text this tool does not read as a "
                + "yes or a no: \"See policy\" (3 rows), \"X\" (1 row), \"2\" (1 row), \"Yes*\" (1 row), \"No PA Required\" (1 row), \"-\" (1 row) "
                + "and \"Not<U+00A0>Required\" (1 row). Those rows are served as not stated, never as not required. Decide what each "
                + "value means, then correct the export or have the spelling added.");
    }

    @Test
    void aJsonFlagWrittenAsANumberOrABooleanIsReadByItsMeaning() {
        ProcedureCodeService service = TestCodeLists.json(directory, "["
                + "{\"code\":\"A1\",\"auth required\":1},"
                + "{\"code\":\"A2\",\"auth required\":0},"
                + "{\"code\":\"A3\",\"auth required\":true},"
                + "{\"code\":\"A4\",\"auth required\":false},"
                + "{\"code\":\"A5\",\"auth required\":\"Y\"},"
                + "{\"code\":\"A6\",\"auth required\":null},"
                + "{\"code\":\"A7\"},"
                + "{\"code\":\"A8\",\"auth required\":2},"
                + "{\"code\":\"A9\",\"auth required\":1.0}]");
        // 1 is yes and 0 is no. Read by position in the enum, 1 would have been "not required".
        assertThat(only(service, "A1").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(only(service, "A1").authRequiredSource()).isEqualTo("1");
        assertThat(only(service, "A2").authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(only(service, "A2").authRequiredSource()).isEqualTo("0");
        assertThat(only(service, "A3").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(only(service, "A3").authRequiredSource()).isEqualTo("true");
        assertThat(only(service, "A4").authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(only(service, "A5").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        // Null and absent are both "the list does not say".
        assertThat(only(service, "A6").authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(only(service, "A6").authRequiredSource()).isNull();
        assertThat(only(service, "A7").authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        // Any other number is not a yes or a no, and neither is a decimal that happens to equal one.
        assertThat(only(service, "A8").authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(only(service, "A9").authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(only(service, "A9").authRequiredSource()).isEqualTo("1.0");
        assertThat(service.selfCheck().statingAuthRequirement()).isEqualTo(5);
        assertThat(service.selfCheck().warnings()).singleElement().asString().contains("\"2\" (1 row) and \"1.0\" (1 row)");
    }

    @Test
    void aListWithNoFlagColumnSaysSoBecauseThenNoRowSaysAnything() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,Requires Authorization?\nE0601,CPAP device,Y\n");
        assertThat(only(service, "E0601").authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(service.selfCheck().statingAuthRequirement()).isZero();
        assertThat(service.selfCheck().recognisedColumns()).doesNotContainKey("authRequired");
        // The header that was probably meant is in the list of headers nobody recognised.
        assertThat(service.selfCheck().unrecognisedColumns()).containsExactly("Requires Authorization?");
        assertThat(service.selfCheck().warnings()).containsExactly("No column was recognised as the prior authorization flag, so no row says "
                + "whether prior authorization is required and nothing can be compared with the service on that point. If the file has "
                + "such a column, name its header under codelist.columns.auth-required.");
    }

    // ---- the vendor ----

    @Test
    void aVendorIsServedAsWrittenAndACellThatNamesNoneIsServedAsNone() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Vendor\n"
                + "A1,eviCore\nA2,EVICORE\nA3,Optum PostAcute\nA4,-\nA5,--\nA6,\nA7,NIA Magellan\nA8,NIA Magellan\nA9,n/a\nB1,?\n");

        // As written. The list is not tidied towards the spelling this tool prefers.
        assertThat(only(service, "A1").delegatedVendor()).isEqualTo("eviCore");
        assertThat(only(service, "A2").delegatedVendor()).isEqualTo("EVICORE");
        assertThat(only(service, "A3").delegatedVendor()).isEqualTo("Optum PostAcute");
        // A dash is not a vendor called "-". Left in the field it would be shown as one and compared as one.
        for (String code : new String[] {"A4", "A5", "A6", "B1"}) {
            assertThat(only(service, code).delegatedVendor()).as(code).isNull();
        }
        // A name this tool does not know is exactly what a tester needs to see, so it stays.
        assertThat(only(service, "A7").delegatedVendor()).isEqualTo("NIA Magellan");
        assertThat(only(service, "A9").delegatedVendor()).isEqualTo("n/a");

        assertThat(service.selfCheck().namingVendor()).isEqualTo(6);
        assertThat(service.selfCheck().warnings()).containsExactly(
                "No column was recognised as the prior authorization flag, so no row says whether prior authorization is required and "
                        + "nothing can be compared with the service on that point. If the file has such a column, name its header under "
                        + "codelist.columns.auth-required.",
                "The delegated vendor column holds cells that name no vendor: \"-\" (1 row), \"--\" (1 row) and \"?\" (1 row). "
                        + "Those rows are served as naming no vendor.",
                "The list names vendors that are not among the current delegated vendors (eviCore, Evolent, Carelon, Optum Post Acute): "
                        + "\"NIA Magellan\" (2 rows) and \"n/a\" (1 row). They are served as written, and a service that names a current "
                        + "vendor will be reported as disagreeing with them.");
    }

    // ---- the line of business ----

    @Test
    void theLineOfBusinessIsServedAsWrittenAndTheSelfCheckCountsEachLineTheListNames() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA,Line of Business\n"
                + "A1,Y,Medicare\nA2,Y,MEDICARE\nA3,Y,\"Medicare, Medicaid\"\nA4,Y,Medicare/Medicaid;Commercial | One Care\n"
                + "A5,Y,\nA6,Y,-\nA7,Y,\" / \"\nA8,Y,Medicare Advantage\nA9,Y,\"Medicare, medicare\"\n");

        assertThat(only(service, "A1").lineOfBusiness()).isEqualTo("Medicare");
        assertThat(only(service, "A2").lineOfBusiness()).isEqualTo("MEDICARE");
        // The cell as written, not a list this tool made of it.
        assertThat(only(service, "A3").lineOfBusiness()).isEqualTo("Medicare, Medicaid");
        assertThat(only(service, "A4").lineOfBusiness()).isEqualTo("Medicare/Medicaid;Commercial | One Care");
        // A row that names no line has no line. The conformance checks tell the two apart by this field being blank.
        for (String code : new String[] {"A5", "A6", "A7"}) {
            assertThat(only(service, code).lineOfBusiness()).as(code).isNull();
        }

        // Each line once, under the spelling first seen, with the rows that name it. A cell naming
        // several lines counts for each, and one that names a line twice counts for it once.
        assertThat(service.selfCheck().linesOfBusiness()).containsExactly(Map.entry("Medicare", 5), Map.entry("Medicaid", 2),
                Map.entry("Commercial", 1), Map.entry("One Care", 1), Map.entry("Medicare Advantage", 1));
        assertThat(service.selfCheck().warnings()).containsExactly("The line of business column holds cells that name no line: \"-\" (1 row) "
                + "and \"/\" (1 row). Those rows are served as stating no line of business, so they are offered for every line.");
    }

    @Test
    void aListWithNoLineOfBusinessColumnNamesNoLines() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA\nA1,Y\n");
        assertThat(service.selfCheck().linesOfBusiness()).isEmpty();
        assertThat(only(service, "A1").lineOfBusiness()).isNull();
        assertThat(service.selfCheck().warnings()).isEmpty();
    }

    // ---- dates ----

    @Test
    void aDateThatCanBeReadIsServedInIsoFormAndOneThatCannotIsServedAsWritten() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA,Effective Date,End Date\n"
                + "A1,Y,1/5/2026,12/31/2026\nA2,Y,2026-01-05,20261231\nA3,Y,01/05/2026,\nA4,Y,,\n"
                + "A5,Y,13/45/2026,TBD\nA6,Y,1/5/26,TBD\nA7,Y,2026-01-05 00:00:00,12/31/9999\n");

        assertThat(only(service, "A1").effectiveDate()).isEqualTo("2026-01-05");
        assertThat(only(service, "A1").endDate()).isEqualTo("2026-12-31");
        assertThat(only(service, "A2").effectiveDate()).isEqualTo("2026-01-05");
        assertThat(only(service, "A2").endDate()).isEqualTo("2026-12-31");
        assertThat(only(service, "A3").effectiveDate()).isEqualTo("2026-01-05");
        assertThat(only(service, "A3").endDate()).isNull();
        assertThat(only(service, "A4").effectiveDate()).isNull();
        // Exactly as written: not blanked, not corrected, not turned into the nearest real date.
        assertThat(only(service, "A5").effectiveDate()).isEqualTo("13/45/2026");
        assertThat(only(service, "A5").endDate()).isEqualTo("TBD");
        assertThat(only(service, "A6").effectiveDate()).isEqualTo("1/5/26");
        assertThat(only(service, "A7").effectiveDate()).isEqualTo("2026-01-05 00:00:00");
        assertThat(only(service, "A7").endDate()).isEqualTo("9999-12-31");

        assertThat(service.selfCheck().warnings()).containsExactly(
                "3 cells in the effective date column could not be read as a date and are served as written: \"13/45/2026\" (1 row), "
                        + "\"1/5/26\" (1 row) and \"2026-01-05 00:00:00\" (1 row). Such a row is not left out for any date of service. "
                        + "Dates are read when written as 2026-01-31, 1/31/2026 or 20260131.",
                "2 cells in the end date column could not be read as a date and are served as written: \"TBD\" (2 rows). Such a row is "
                        + "not left out for any date of service. Dates are read when written as 2026-01-31, 1/31/2026 or 20260131.");
    }

    @Test
    void aRowThatEndsBeforeItStartsIsReportedBecauseItAppliesOnNoDate() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA,Effective Date,End Date\nA1,Y,1/1/2026,12/31/2026\nA2,Y,6/1/2026,1/1/2026\n");
        assertThat(service.selfCheck().warnings()).containsExactly("1 row has an end date before the effective date: line 3. Such a row "
                + "applies on no date of service.");
        assertThat(service.rowsFor("A2", null, null)).hasSize(1);
        assertThat(service.rowsFor("A2", null, java.time.LocalDate.of(2026, 3, 1))).isEmpty();
    }

    // ---- rows that are kept, and the one kind that is not ----

    @Test
    void aRowWithNoCodeIsLeftOutAndCounted() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,PA\nA1,first,Y\n,no code here,Y\n  ,nor here,N\nA2,second,N\n\"\",nor here,N\n");
        assertThat(service.selfCheck().rowCount()).isEqualTo(2);
        assertThat(service.search("", null, 10)).extracting(ProcedureCode::code).containsExactly("A1", "A2");
        assertThat(service.selfCheck().warnings()).containsExactly("3 rows have no code and were left out: lines 3, 4 and 6.");
        // One such row reads as a sentence too.
        assertThat(TestCodeLists.csv(directory, "Code,PA\n,Y\nA1,Y\n").selfCheck().warnings())
                .containsExactly("1 row has no code and was left out: line 2.");
    }

    @Test
    void severalRowsMayShareACodeAndAllOfThemAreKeptInTheOrderOfTheFile() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA,Line of Business,Effective Date,End Date\n"
                + "95810,Y,Medicare,1/1/2025,12/31/2025\n95810,N,Medicare,1/1/2026,\n95810,N,Commercial,,\n95810,Y,Medicare,1/1/2025,12/31/2025\n");
        assertThat(service.selfCheck().rowCount()).isEqualTo(4);
        // The last row repeats the first exactly. It is still a row of the list, so it is still served.
        assertThat(service.rowsFor("95810", null, null)).extracting(ProcedureCode::authRequired, ProcedureCode::lineOfBusiness, ProcedureCode::effectiveDate)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(AuthRequirement.REQUIRED, "Medicare", "2025-01-01"),
                        org.assertj.core.groups.Tuple.tuple(AuthRequirement.NOT_REQUIRED, "Medicare", "2026-01-01"),
                        org.assertj.core.groups.Tuple.tuple(AuthRequirement.NOT_REQUIRED, "Commercial", null),
                        org.assertj.core.groups.Tuple.tuple(AuthRequirement.REQUIRED, "Medicare", "2025-01-01"));
    }

    @Test
    void everyColumnIsServedAsWrittenAndABlankCellIsAbsent() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,Code Type,System,PA,LOB,Vendor,Eff Date,Term Date,Comments\n"
                + " e0260 ,\"Hospital bed, semi-electric, with \"\"gatch\"\" springs\",HCPCS,urn:example:hcpcs,Y,Medicare,Evolent,1/1/2026,12/31/2026,\"keep, as is\"\n"
                + "J3490,,,,,,,,,\n");

        ProcedureCode full = only(service, "E0260");
        // The code keeps the case it was written in. Only the white space around it is gone.
        assertThat(full.code()).isEqualTo("e0260");
        assertThat(full.description()).isEqualTo("Hospital bed, semi-electric, with \"gatch\" springs");
        assertThat(full.codeType()).isEqualTo("HCPCS");
        assertThat(full.system()).isEqualTo("urn:example:hcpcs");
        assertThat(full.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(full.authRequiredSource()).isEqualTo("Y");
        assertThat(full.lineOfBusiness()).isEqualTo("Medicare");
        assertThat(full.delegatedVendor()).isEqualTo("Evolent");
        assertThat(full.effectiveDate()).isEqualTo("2026-01-01");
        assertThat(full.endDate()).isEqualTo("2026-12-31");
        assertThat(full.notes()).isEqualTo("keep, as is");

        // Only the code is guaranteed. Everything else is null, never an empty string and never a default.
        assertThat(only(service, "J3490")).isEqualTo(ProcedureCode.builder().code("J3490").build());
        assertThat(only(service, "J3490").authRequired()).isEqualTo(AuthRequirement.UNKNOWN);

        assertThat(service.selfCheck().recognisedColumns()).containsExactly(Map.entry("code", "Code"), Map.entry("description", "Description"),
                Map.entry("codeType", "Code Type"), Map.entry("system", "System"), Map.entry("authRequired", "PA"),
                Map.entry("lineOfBusiness", "LOB"), Map.entry("delegatedVendor", "Vendor"), Map.entry("effectiveDate", "Eff Date"),
                Map.entry("endDate", "Term Date"), Map.entry("notes", "Comments"));
        assertThat(service.selfCheck().statingAuthRequirement()).isEqualTo(1);
        assertThat(service.selfCheck().namingVendor()).isEqualTo(1);
        assertThat(service.selfCheck().warnings()).isEmpty();
    }

    @Test
    void aCodeWithANoBreakSpaceBesideItIsStillTheCode() {
        // Invisible in a spreadsheet, and enough to make an exact match on the code fail.
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA\n\u00A095810\u00A0,Yes\u00A0\n");
        ProcedureCode row = only(service, "95810");
        assertThat(row.code()).isEqualTo("95810");
        assertThat(row.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(row.authRequiredSource()).isEqualTo("Yes");
    }

    @Test
    void aCodeThatMayHaveLostItsLeadingZerosIsReportedAndServedAsWritten() {
        // A spreadsheet that takes 00100 for a number saves it as 100. Which code was meant is not
        // this tool's to decide, so nothing is put back. It is said, with the codes.
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA\n100,Y\n1999,N\n00100,Y\n99213,N\nE0601,N\n7,Y\n100,N\n0450,N\n");
        assertThat(service.rowsFor("100", null, null)).hasSize(2);
        assertThat(service.rowsFor("00100", null, null)).singleElement().satisfies(row -> assertThat(row.code()).isEqualTo("00100"));
        assertThat(service.selfCheck().rowCount()).isEqualTo(8);
        assertThat(service.selfCheck().warnings()).containsExactly("The code column holds codes that are all digits and shorter than five "
                + "digits: \"100\" (2 rows), \"1999\" (1 row), \"7\" (1 row) and \"0450\" (1 row). If they are meant to be five digit codes, "
                + "their leading zeros were lost on the way, which is what a spreadsheet does to a code it takes for a number. They are "
                + "served as written.");
        // Five digits, or a letter anywhere, and nothing is said.
        assertThat(TestCodeLists.csv(directory, "Code,PA\n00100,Y\n99213,N\nE0601,N\n0001F,N\nA1,Y\n").selfCheck().warnings()).isEmpty();
    }

    @Test
    void aCodeWithASpaceOrAnUnseenCharacterInsideItIsReportedBecauseNobodyWillTypeIt() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,PA,Notes\nE 0601,Y,r1\nE06\u200B01,N,r2\nE0601,N,r3\n");
        // Served as written: the two odd codes are their own codes, and only the clean one is found by typing it.
        assertThat(service.search("E0601", null, 10)).extracting(ProcedureCode::notes).containsExactly("r3");
        assertThat(service.rowsFor("E0601", null, null)).extracting(ProcedureCode::notes).containsExactly("r3");
        assertThat(service.rowsFor("E 0601", null, null)).extracting(ProcedureCode::notes).containsExactly("r1");
        assertThat(service.selfCheck().warnings()).containsExactly("The code column holds codes with a space or an unseen character inside "
                + "them: \"E 0601\" (1 row) and \"E06<U+200B>01\" (1 row). They are served as written, so a tester who types the code without "
                + "it will not find the row.");
    }

    // ---- rows of the wrong width, and quoting that went wrong ----

    @Test
    void aShortRowKeepsItsCellsInPlaceAndALongRowIsReportedAsPossiblyMisaligned() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,PA,LOB\n"
                + "A1,complete,Y,Medicare\nA2,stops early\nA3,Hospital bed, semi-electric,Y,Medicare\nA4,complete,N,Commercial\n");

        assertThat(service.selfCheck().rowCount()).isEqualTo(4);
        ProcedureCode shortRow = only(service, "A2");
        assertThat(shortRow.description()).isEqualTo("stops early");
        // The cells that are missing are missing. Nothing was pulled in from elsewhere to fill them.
        assertThat(shortRow.authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(shortRow.lineOfBusiness()).isNull();

        // The unquoted comma pushed every later cell one place to the right. The row is served as
        // the file has it, which is wrong, and the warning says that it may be.
        ProcedureCode longRow = only(service, "A3");
        assertThat(longRow.description()).isEqualTo("Hospital bed");
        assertThat(longRow.authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(longRow.authRequiredSource()).isEqualTo("semi-electric");
        assertThat(longRow.lineOfBusiness()).isEqualTo("Y");

        assertThat(service.selfCheck().warnings()).containsExactly(
                "1 row has fewer cells than the header row, which names 4 columns: line 3. The cells that are there were read in place "
                        + "and the missing ones as blank.",
                "1 row has more cells than the header row, which names 4 columns: line 4. The cells beyond the last header were not read. "
                        + "A cell that holds the delimiter must be in double quotes, so the cells of these rows may sit under the wrong columns.",
                "The prior authorization column holds text this tool does not read as a yes or a no: \"semi-electric\" (1 row). Those rows "
                        + "are served as not stated, never as not required. Decide what each value means, then correct the export or have "
                        + "the spelling added.");
        assertThat(service.selfCheck().linesOfBusiness()).containsExactly(Map.entry("Medicare", 1), Map.entry("Y", 1), Map.entry("Commercial", 1));
    }

    @Test
    void aQuoteThatIsNeverClosedIsReportedAndTheRowsAfterItAreStillLoaded() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,PA\nA1,\"quoted, properly\",Y\nA2,\"never closed,N\n"
                + "A3,fine,Y\nA4,\"quoted, again\",N\nA5,fine,Y\n");

        // All five rows. Without the rule in the reader, A3 would be inside the description of A2.
        assertThat(service.search("", null, 10)).extracting(ProcedureCode::code).containsExactly("A1", "A2", "A3", "A4", "A5");
        assertThat(only(service, "A3").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(only(service, "A4").description()).isEqualTo("quoted, again");
        assertThat(only(service, "A2").description()).isEqualTo("\"never closed");
        assertThat(service.selfCheck().warnings()).containsExactly("1 double quote opens a cell and has no closing quote that ends it: "
                + "line 3. It was read as an ordinary character, so that row may not be what the file meant. A cell that starts with a "
                + "double quote must end with one, and a double quote inside it is written twice.");
    }

    @Test
    void theSameMistakeOnManyLinesIsOneWarningThatListsTheLines() {
        // An export written by joining strings gets its quoting wrong on every row that needs any.
        // Seven thousand warnings would bury everything else the self-check has to say.
        StringBuilder csv = new StringBuilder("Code,Description,PA\n");
        for (int i = 1; i <= 15; i++) {
            csv.append("U").append(i).append(",\"never closed,Y\n");
        }
        for (int i = 1; i <= 12; i++) {
            csv.append("S").append(i).append(",\"a \"marked\" word\",N\n");
        }
        ProcedureCodeService service = TestCodeLists.csv(directory, csv.toString());

        assertThat(service.selfCheck().rowCount()).isEqualTo(27);
        assertThat(service.selfCheck().warnings()).containsExactly(
                "15 double quotes open a cell and have no closing quote that ends it: lines 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 and 5 more. "
                        + "Each was read as an ordinary character, so those rows may not be what the file meant. A cell that starts "
                        + "with a double quote must end with one, and a double quote inside it is written twice.",
                "On 12 lines text follows the closing double quote of a cell: lines 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 and 2 more. "
                        + "The cells were kept as written, but the rows may not be what the file meant. Inside a quoted cell a double "
                        + "quote is written twice.");
        // Every row is still its own row, with its own flag.
        assertThat(only(service, "U15").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(only(service, "S1").authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(only(service, "S12").description()).isEqualTo("a \"marked\" word\"");
    }

    @Test
    void textAfterAClosingQuoteIsReportedAndTheRowIsKept() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,PA\nE2601,\"Cushion, 22\" wide\",Y\nA2,fine,N\n");
        assertThat(only(service, "E2601").description()).isEqualTo("Cushion, 22\" wide\"");
        assertThat(only(service, "E2601").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(service.selfCheck().rowCount()).isEqualTo(2);
        assertThat(service.selfCheck().warnings()).containsExactly("On 1 line text follows the closing double quote of a cell: line 2. "
                + "The cell was kept as written, but the row may not be what the file meant. Inside a quoted cell a double quote is "
                + "written twice.");
    }

    @Test
    void aCellThatHoldsALineBreakIsNamedBecauseItMayBeRowsThatWereSwallowed() {
        // Read as the file has it, the second and third lines are one row. Nothing about the text is
        // wrong, so the only thing that can be done is to point at it.
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,PA\nA1,\"not closed,Y\nA2,pipe 5\",N\nA3,plain,Y\n");
        assertThat(service.search("", null, 10)).extracting(ProcedureCode::code).containsExactly("A1", "A3");
        assertThat(service.selfCheck().warnings()).containsExactly("1 row has a cell that holds a line break: line 2. If that was not meant, "
                + "a double quote on that line is not closed and the lines after it were read into the cell.");
        // A line break that was meant is served as part of the value.
        assertThat(only(service, "A1").description()).isEqualTo("not closed,Y\nA2,pipe 5");
    }

    @Test
    void aColumnWithNoHeaderIsReportedWhenSomethingIsInIt() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,,PA,\nA1,stray text,Y,\nA2,,N,\nA3,more,Y,\n");
        assertThat(service.selfCheck().unrecognisedColumns()).isEmpty();
        // The second column holds something on two rows. The fourth is empty all the way down, and nothing is said about it.
        assertThat(service.selfCheck().warnings()).containsExactly("Column 2 has no name in the header row and holds something on lines 2 "
                + "and 4. It was not read.");
    }

    @Test
    void aHeaderNobodyRecognisedIsReportedAndItsColumnIsNotRead() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Internal Ref,PA,Reviewer\nA1,r-1,Y,someone\n");
        assertThat(service.selfCheck().unrecognisedColumns()).containsExactly("Internal Ref", "Reviewer");
        assertThat(service.selfCheck().recognisedColumns()).containsExactly(Map.entry("code", "Code"), Map.entry("authRequired", "PA"));
    }
}
