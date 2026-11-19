package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;

/** The code list written as JSON: the two shapes it may have, and how a value that is not text is read. */
class JsonCodeListTest {

    private static final String ROWS = "["
            + "{\"Procedure Code\":\"95810\",\"Description\":\"Sleep study, attended\",\"PA Required\":\"Yes\",\"Line of Business\":\"Medicare\","
            + "\"Delegated Vendor\":\"eviCore\",\"Effective Date\":\"1/1/2026\",\"Internal Ref\":\"r-1\"},"
            + "{\"Procedure Code\":\"E0601\",\"Description\":\"CPAP device\",\"PA Required\":\"No\"}"
            + "]";

    @TempDir
    Path directory;

    private ProcedureCode only(ProcedureCodeService service, String code) {
        assertThat(service.rowsFor(code, null, null)).as("rows for " + code).hasSize(1);
        return service.rowsFor(code, null, null).get(0);
    }

    @Test
    void aListOfObjectsIsReadWithItsKeysAsTheHeaders() {
        ProcedureCodeService service = TestCodeLists.json(directory, ROWS);

        CodeListSelfCheck check = service.selfCheck();
        assertThat(check.format()).isEqualTo("json");
        assertThat(check.placeholder()).isFalse();
        assertThat(check.rowCount()).isEqualTo(2);
        assertThat(check.statingAuthRequirement()).isEqualTo(2);
        assertThat(check.namingVendor()).isEqualTo(1);
        assertThat(check.linesOfBusiness()).containsExactly(Map.entry("Medicare", 1));
        // The same candidates and the same comparison as for a CSV header row.
        assertThat(check.recognisedColumns()).containsExactly(Map.entry("code", "Procedure Code"), Map.entry("description", "Description"),
                Map.entry("authRequired", "PA Required"), Map.entry("lineOfBusiness", "Line of Business"),
                Map.entry("delegatedVendor", "Delegated Vendor"), Map.entry("effectiveDate", "Effective Date"));
        assertThat(check.unrecognisedColumns()).containsExactly("Internal Ref");
        assertThat(check.warnings()).isEmpty();

        ProcedureCode sleep = only(service, "95810");
        assertThat(sleep.description()).isEqualTo("Sleep study, attended");
        assertThat(sleep.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(sleep.lineOfBusiness()).isEqualTo("Medicare");
        assertThat(sleep.delegatedVendor()).isEqualTo("eviCore");
        assertThat(sleep.effectiveDate()).isEqualTo("2026-01-01");
        // A key the second row does not have is a cell it does not have.
        ProcedureCode cpap = only(service, "E0601");
        assertThat(cpap.authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(cpap.lineOfBusiness()).isNull();
        assertThat(cpap.delegatedVendor()).isNull();
        assertThat(cpap.effectiveDate()).isNull();
    }

    @Test
    void anObjectWithExactlyOneListInItIsReadFromThatList() {
        ProcedureCodeService wrapped = TestCodeLists.json(directory, "{\"codes\":" + ROWS + "}");
        assertThat(wrapped.selfCheck().rowCount()).isEqualTo(2);
        assertThat(wrapped.selfCheck().warnings()).isEmpty();
        assertThat(only(wrapped, "95810").delegatedVendor()).isEqualTo("eviCore");

        // Whatever the property is called. What stands beside it was not read, and that is said.
        ProcedureCodeService withMore = TestCodeLists.json(directory, "{\"exported\":\"2026-01-05\",\"rows\":" + ROWS + ",\"count\":2,\"source\":{\"system\":\"x\"}}");
        assertThat(withMore.selfCheck().rowCount()).isEqualTo(2);
        assertThat(withMore.selfCheck().warnings()).containsExactly("Beside the list of rows under \"rows\", the top of the file also holds "
                + "\"exported\", \"count\" and \"source\". That was not read.");
    }

    @Test
    void anObjectWithNoListOrWithTwoIsRefusedBecauseTheRowsCannotBeTold() {
        Path none = TestCodeLists.write(directory, "none.json", "{\"code\":\"95810\",\"description\":\"Sleep study\"}");
        assertThatThrownBy(() -> TestCodeLists.load(none)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The code list file " + none + " is a JSON object with no list in it. A JSON code list is a list of rows, or "
                        + "an object with exactly one property that holds the list.");
        Path two = TestCodeLists.write(directory, "two.json", "{\"medicare\":" + ROWS + ",\"commercial\":" + ROWS + "}");
        assertThatThrownBy(() -> TestCodeLists.load(two)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The code list file " + two + " is a JSON object with 2 lists in it (\"medicare\" and \"commercial\"), so this "
                        + "tool cannot tell which one holds the rows. A JSON code list is a list of rows, or an object with exactly one "
                        + "property that holds the list.");
    }

    @Test
    void jsonThatIsNeitherAListNorAnObjectIsRefused() {
        for (String text : new String[] {"\"95810\"", "42", "true", "null"}) {
            Path file = TestCodeLists.write(directory, "scalar.json", text);
            assertThatThrownBy(() -> TestCodeLists.load(file)).as(text).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(file.toString());
        }
        Path file = TestCodeLists.write(directory, "scalar.json", "\"95810\"");
        assertThatThrownBy(() -> TestCodeLists.load(file)).hasMessageContaining("is JSON, but it is neither a list nor an object.");
    }

    @Test
    void textThatIsNotJsonIsRefusedWithWhereItStopsBeingJsonAndNeverWithItsContent() {
        // The code is not all digits, so it cannot turn up by chance in the name of the temporary directory.
        Path file = TestCodeLists.write(directory, "broken.json", "[{\"code\":\"QX958\",\n\"description\":\"Sleep study\"\n{\"code\":\"E0601\"}]");
        assertThatThrownBy(() -> TestCodeLists.load(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The code list file " + file + " is not valid JSON (line 3, column ")
                .hasMessageNotContaining("QX958").hasMessageNotContaining("Sleep study");
        Path empty = TestCodeLists.write(directory, "empty.json", " \n ");
        assertThatThrownBy(() -> TestCodeLists.load(empty)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The code list file " + empty + " is empty.");
    }

    @Test
    void aListWithNoObjectInItHasNoRowsAndIsRefused() {
        for (String text : new String[] {"[]", "{\"codes\":[]}", "[{}]", "[\"95810\",\"E0601\"]"}) {
            Path file = TestCodeLists.write(directory, "norows.json", text);
            assertThatThrownBy(() -> TestCodeLists.load(file)).as(text).isInstanceOf(IllegalStateException.class)
                    .hasMessage("The code list file " + file + " has no rows: the list in it holds no object with keys.");
        }
    }

    @Test
    void aListWhoseRowsHoldNothingLoadsEmptyLikeACsvFileThatIsOnlyAHeaderRow() {
        // The key for the code is there, so this is a code list. It has no row with anything in it.
        ProcedureCodeService service = TestCodeLists.json(directory, "[{},{\"code\":null,\"auth required\":\"\"}]");
        assertThat(service.selfCheck().rowCount()).isZero();
        assertThat(service.selfCheck().recognisedColumns()).containsExactly(Map.entry("code", "code"), Map.entry("authRequired", "auth required"));
        assertThat(service.selfCheck().warnings()).containsExactly("The list has no rows with a code. The picker will offer nothing, and no "
                + "code can be compared with the service.");
    }

    @Test
    void aListWithNoKeyForTheCodeIsRefusedWithTheKeysItDidFind() {
        Path file = TestCodeLists.write(directory, "nocode.json", "[{\"proc\":\"95810\",\"desc\":\"Sleep study\"},{\"proc\":\"E0601\",\"needs auth\":\"N\"}]");
        assertThatThrownBy(() -> TestCodeLists.load(file)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The code list file " + file + " has no column this tool recognises as the procedure code. "
                        + "Keys found: \"proc\", \"desc\", \"needs auth\". "
                        + "If one of these holds the code, name it under codelist.columns.code in application.yml. A list set there replaces the built-in names for the column.");
    }

    @Test
    void aValueThatIsNotTextIsReadAsText() {
        ProcedureCodeService service = TestCodeLists.json(directory, "["
                + "{\"code\":99213,\"description\":\"Office visit\",\"auth required\":0,\"effective date\":20260105},"
                + "{\"code\":\"A1\",\"auth required\":true,\"notes\":12.50},"
                + "{\"code\":\"A2\",\"line of business\":[\"Medicare\",\"Medicaid\"],\"auth required\":1},"
                + "{\"code\":\"A3\",\"line of business\":[],\"notes\":[null,\"kept\",3]}"
                + "]");
        // A code written as a number is that number as text.
        ProcedureCode visit = only(service, "99213");
        assertThat(visit.code()).isEqualTo("99213");
        assertThat(visit.authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(visit.effectiveDate()).isEqualTo("2026-01-05");
        assertThat(only(service, "A1").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        // Exactly as written, not rounded to 12.5.
        assertThat(only(service, "A1").notes()).isEqualTo("12.50");
        // A list of plain values is those values, which is how a row names two lines of business.
        ProcedureCode both = only(service, "A2");
        assertThat(both.lineOfBusiness()).isEqualTo("Medicare, Medicaid");
        assertThat(service.rowsFor("A2", "Medicaid", null)).hasSize(1);
        assertThat(service.rowsFor("A2", "Commercial", null)).isEmpty();
        assertThat(service.selfCheck().linesOfBusiness()).containsExactly(Map.entry("Medicare", 1), Map.entry("Medicaid", 1));
        // An empty list names nothing, and a null inside a list is not written as the word null.
        assertThat(only(service, "A3").lineOfBusiness()).isNull();
        assertThat(only(service, "A3").notes()).isEqualTo("kept, 3");
        assertThat(service.selfCheck().warnings()).isEmpty();
    }

    @Test
    void aValueThatIsAnObjectIsServedAsItsJsonTextAndReported() {
        ProcedureCodeService service = TestCodeLists.json(directory, "["
                + "{\"code\":\"A1\",\"notes\":{\"reviewed\":true}},"
                + "{\"code\":\"A2\",\"notes\":\"plain\"},"
                + "{\"code\":\"A3\",\"line of business\":[{\"name\":\"Medicare\"}]}]");
        assertThat(only(service, "A1").notes()).isEqualTo("{\"reviewed\":true}");
        assertThat(only(service, "A2").notes()).isEqualTo("plain");
        assertThat(service.selfCheck().warnings()).contains("2 entries have a value that is an object, or a list holding one: entries 1 and 3. "
                + "Such a value was read as its JSON text.");
    }

    @Test
    void keysThatDifferOnlyInCaseOrSpacingAreOneHeader() {
        ProcedureCodeService service = TestCodeLists.json(directory, "[{\"Code\":\"A1\",\"PA Required\":\"Y\"},{\"code\":\"A2\",\"pa_required\":\"N\"},"
                + "{\"CODE\":\"A3\",\"PA-Required\":\"Y\",\"pa required\":\"N\"}]");
        assertThat(service.selfCheck().rowCount()).isEqualTo(3);
        // Reported under the spelling first seen.
        assertThat(service.selfCheck().recognisedColumns()).containsExactly(Map.entry("code", "Code"), Map.entry("authRequired", "PA Required"));
        assertThat(only(service, "A2").authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
        // The third entry says yes and no under what is one header. The first is read, and the entry is named.
        assertThat(only(service, "A3").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(service.selfCheck().warnings()).containsExactly("1 entry has two keys that differ only in case, spacing or punctuation, "
                + "which this tool reads as one header: entry 3. The first of the two was read.");
    }

    @Test
    void keysAreFoundTheWayHeadersAreWithAQuestionMarkASlashOrABracketedEnding() {
        ProcedureCodeService service = TestCodeLists.json(directory, "[{\"CPT/HCPCS Code\":\"A1\",\"PA Required?\":\"Y\",\"Vendor (if any)\":\"eviCore\"},"
                // The same two keys written without the punctuation are the same two headers, so this row is read as well.
                + "{\"cpt hcpcs code\":\"A2\",\"PA Required\":\"N\"}]");
        assertThat(service.selfCheck().rowCount()).isEqualTo(2);
        assertThat(service.selfCheck().recognisedColumns()).containsExactly(Map.entry("code", "CPT/HCPCS Code"),
                Map.entry("authRequired", "PA Required?"), Map.entry("delegatedVendor", "Vendor (if any)"));
        assertThat(service.selfCheck().unrecognisedColumns()).isEmpty();
        assertThat(service.selfCheck().warnings()).isEmpty();
        assertThat(only(service, "A1").authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(only(service, "A1").delegatedVendor()).isEqualTo("eviCore");
        assertThat(only(service, "A2").authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
    }

    @Test
    void keysThatDifferInABracketedEndingStayTwoKeysSoNeitherIsLost() {
        // Two rates for two years are two columns. Neither is one this tool reads, and both are reported.
        ProcedureCodeService service = TestCodeLists.json(directory, "[{\"code\":\"A1\",\"Rate (2025)\":\"1\",\"Rate (2026)\":\"2\"}]");
        assertThat(service.selfCheck().unrecognisedColumns()).containsExactly("Rate (2025)", "Rate (2026)");
    }

    @Test
    void whatIsNotARowIsReportedAndWhatIsEmptyIsNot() {
        ProcedureCodeService service = TestCodeLists.json(directory, "[{\"code\":\"A1\"},\"A2\",[\"A3\"],{},{\"code\":null,\"notes\":\"\"},"
                + "{\"code\":\"\",\"notes\":\"no code\"},{\"code\":\"A4\"},7]");
        assertThat(service.search("", null, 10)).extracting(ProcedureCode::code).containsExactly("A1", "A4");
        assertThat(service.selfCheck().warnings()).containsExactly(
                "3 entries of the list are not objects and were not read: entries 2, 3 and 8.",
                "No column was recognised as the prior authorization flag, so no row says whether prior authorization is required and "
                        + "nothing can be compared with the service on that point. If the file has such a column, name its header under "
                        + "codelist.columns.auth-required.",
                // Entry 6 holds something and has no code. Entries 4 and 5 hold nothing, like a blank line.
                "1 row has no code and was left out: entry 6.");
    }

    @Test
    void aValueUnderABlankKeyIsReportedBecauseNoColumnCanBeFoundForIt() {
        ProcedureCodeService service = TestCodeLists.json(directory, "[{\"code\":\"A1\",\"pa\":\"Y\",\"\":\"stray\"},{\"code\":\"A2\",\"pa\":\"N\",\" \":null},"
                + "{\"code\":\"A3\",\"pa\":\"N\",\"\":\"again\"}]");
        assertThat(service.selfCheck().rowCount()).isEqualTo(3);
        assertThat(service.selfCheck().unrecognisedColumns()).isEmpty();
        assertThat(service.selfCheck().warnings()).containsExactly("A key that is blank holds something on entries 1 and 3. It was not read.");
    }

    @Test
    void aKeyWrittenTwiceInOneObjectIsReportedBecauseOnlyOneValueSurvives() {
        ProcedureCodeService service = TestCodeLists.json(directory, "[{\"code\":\"A1\",\"auth required\":\"Y\",\"auth required\":\"N\"}]");
        assertThat(service.selfCheck().warnings()).containsExactly("An object in the file has the same key more than once. Only the last value "
                + "of such a key was read.");
        assertThat(only(service, "A1").authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
    }

    @Test
    void theRowsOfAJsonListAreSearchedAndFilteredLikeAnyOther() {
        ProcedureCodeService service = TestCodeLists.json(directory, "[{\"code\":\"95810\",\"lob\":\"Medicare\",\"effective date\":\"2026-01-01\","
                + "\"end date\":\"2026-06-30\"},{\"code\":\"95811\",\"lob\":\"Commercial\"}]");
        assertThat(service.search("958", "Medicare", 10)).extracting(ProcedureCode::code).containsExactly("95810");
        assertThat(service.rowsFor("95810", "Medicare", LocalDate.of(2026, 7, 1))).isEmpty();
        assertThat(service.rowsFor("95810", "Medicare", LocalDate.of(2026, 6, 30))).hasSize(1);
        assertThat(service.statesLine("commercial")).isTrue();
    }
}
