package org.p32h.interop.epa.pave.service.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.service.error.BadInputException;

class SuiteFileReaderTest {

    private final SuiteFileReader reader = new SuiteFileReader(new TestCsv());

    @Test
    void aCsvFileIsRowsOfCellsNamedByItsFirstRow() {
        SuiteTable table = reader.read("cases.csv", "caseId,memberId,codes\nTC-1,S123456789,95810\nTC-2, S223456789 ,\n");
        assertThat(table.format()).isEqualTo("CSV");
        assertThat(table.problems()).isEmpty();
        assertThat(table.columns()).containsExactly("caseId", "memberId", "codes");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).number()).isEqualTo(1);
        assertThat(table.rows().get(0).cells()).containsExactly(Map.entry("caseId", "TC-1"), Map.entry("memberId", "S123456789"),
                Map.entry("codes", "95810"));
        // A blank cell is absent, and a cell is stripped of the spaces around it.
        assertThat(table.rows().get(1).cells()).containsExactly(Map.entry("caseId", "TC-2"), Map.entry("memberId", "S223456789"));
    }

    @Test
    void aBlankRowIsNotACaseAndDoesNotTakeANumber() {
        SuiteTable table = reader.read("cases.csv", "caseId,memberId\nTC-1,S123456789\n,\n\nTC-2,S223456789");
        assertThat(table.rows()).extracting(SuiteTable.SourceRow::number).containsExactly(1, 2);
        assertThat(table.rows().get(1).cells()).containsEntry("caseId", "TC-2");
    }

    @Test
    void aRowWithADifferentNumberOfCellsIsReportedAndNeverShifted() {
        SuiteTable table = reader.read("cases.csv", "caseId,memberId,npi\nTC-1,S123456789\nTC-2,S223456789,1234567893,extra\n");
        assertThat(table.rows()).hasSize(2);
        for (SuiteTable.SourceRow row : table.rows()) {
            // No cell is read from such a row: a value under the wrong column is worse than no value.
            assertThat(row.cells()).isEmpty();
            assertThat(row.problems()).singleElement().asString().contains("cells").contains("3 columns");
        }
        assertThat(table.rows().get(0).problems().get(0)).startsWith("The row has 2 cells");
        assertThat(table.rows().get(1).problems().get(0)).startsWith("The row has 4 cells");
    }

    @Test
    void aHeaderThatNamesNothingIsAProblemWithTheFile() {
        assertThat(reader.read("cases.csv", ",,\nTC-1,S123456789,95810").problems()).singleElement().asString()
                .contains("must name the columns");
        assertThat(reader.read("cases.csv", "caseId,,codes\nTC-1,S123456789,95810").problems()).singleElement().asString()
                .contains("Column 2 has no name");
    }

    @Test
    void whatTheCsvReaderReportsIsAProblemWithTheFile() {
        SuiteFileReader failing = new SuiteFileReader(text -> new CsvRows.Table(List.of("caseId"), List.of(),
                List.of("A quote opened on line 3 is never closed.")));
        SuiteTable table = failing.read("cases.csv", "anything");
        assertThat(table.problems()).containsExactly("A quote opened on line 3 is never closed.");
        assertThat(table.rows()).isEmpty();
    }

    @Test
    void anEmptyFileIsAProblemAndNotAnEmptySuite() {
        for (String text : new String[] {null, "", "  \n ", "﻿"}) {
            assertThat(reader.read("cases.csv", text).problems()).containsExactly("The file is empty.");
            assertThat(reader.read("cases.json", text).problems()).containsExactly("The file is empty.");
        }
    }

    @Test
    void aJsonFileIsAListOfCasesOrAnObjectHoldingOne() {
        String cases = "[{\"caseId\":\"TC-1\",\"memberId\":\"S123456789\",\"npi\":1234567893,\"chain\":true,"
                + "\"procedureCodes\":[\"95810\",\" 95811 \"],\"note\":null,\"expected\":\"\"}]";
        for (String text : new String[] {cases, "{\"name\":\"Sleep studies\",\"cases\":" + cases + "}", "﻿" + cases}) {
            SuiteTable table = reader.read("cases.json", text);
            assertThat(table.format()).isEqualTo("JSON");
            assertThat(table.problems()).isEmpty();
            assertThat(table.rows()).singleElement().satisfies(row -> {
                assertThat(row.problems()).isEmpty();
                // A whole number and a boolean are carried as their text, a list as its values joined with a bar.
                // A null and a blank string are absent.
                assertThat(row.cells()).containsExactly(Map.entry("caseId", "TC-1"), Map.entry("memberId", "S123456789"),
                        Map.entry("npi", "1234567893"), Map.entry("chain", "true"), Map.entry("procedureCodes", "95810|95811"));
            });
            assertThat(table.columns()).containsExactly("caseId", "memberId", "npi", "chain", "procedureCodes", "note", "expected");
        }
    }

    @Test
    void theCorrelationObjectIsReadAsItsOwnKeys() {
        SuiteTable table = reader.read("cases.json",
                "[{\"memberId\":\"S123456789\",\"correlation\":{\"claimId\":\"claim-1\",\"subscriptionId\":null}}]");
        assertThat(table.rows().get(0).problems()).isEmpty();
        assertThat(table.rows().get(0).cells()).containsExactly(Map.entry("memberId", "S123456789"), Map.entry("claimId", "claim-1"));
    }

    @Test
    void aJsonValueThatIsNotPlainIsReportedAndNeverQuoted() {
        SuiteTable table = reader.read("cases.json", "[{\"memberId\":{\"value\":\"S123456789\"},\"npi\":1234567893.0,"
                + "\"procedureCodes\":[\"95810\",{\"code\":\"95811\"}],\"correlation\":{\"claimId\":[\"c\"]}}, \"S223456789\"]");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).cells()).isEmpty();
        assertThat(table.rows().get(0).problems()).containsExactly(
                "\"memberId\" holds an object. It must be text.",
                "\"npi\" is a number with a decimal point. Write it as text, in double quotes.",
                "\"procedureCodes\" holds a list with something in it that is not text.",
                "\"correlation.claimId\" must be text.");
        assertThat(table.rows().get(1).problems()).containsExactly("The case is not a JSON object.");
        // No message repeats a value: a value can be a member ID.
        assertThat(table.rows().toString() + table.rows().get(0).problems() + table.rows().get(1).problems())
                .doesNotContain("S123456789").doesNotContain("S223456789");
    }

    @Test
    void aJsonFileOfAnotherShapeIsRefusedWithWhatTheShapeMustBe() {
        String expected = "A JSON suite file is a list of cases, or an object with \"name\" and \"cases\".";
        assertThat(reader.read("cases.json", "\"text\"").problems()).containsExactly(expected);
        assertThat(reader.read("cases.json", "{\"name\":\"x\"}").problems()).containsExactly(expected);
        assertThat(reader.read("cases.json", "{\"cases\":{}}").problems()).containsExactly(expected);
        assertThat(reader.read("cases.json", "{\"cases\":[],\"owner\":\"qa\"}").problems())
                .containsExactly(expected + " This object also has: owner.");
        assertThat(reader.read("cases.json", "[{\"memberId\":\"S123456789\"").problems()).singleElement().asString()
                .startsWith("The file is not valid JSON").doesNotContain("S123456789");
        // The second value of a repeated key would silently replace the first.
        assertThat(reader.read("cases.json", "[{\"expected\":\"A\",\"expected\":\"B\"}]").problems()).singleElement().asString()
                .contains("the same key twice");
    }

    @Test
    void theFormatIsTakenFromTheNameAndOtherwiseFromHowTheTextStarts() {
        assertThat(SuiteFileReader.looksLikeJson("cases.JSON", "caseId")).isTrue();
        assertThat(SuiteFileReader.looksLikeJson("cases.csv", "[")).isFalse();
        assertThat(SuiteFileReader.looksLikeJson("cases", " \n[{}]")).isTrue();
        assertThat(SuiteFileReader.looksLikeJson(null, "﻿{\"cases\":[]}")).isTrue();
        assertThat(SuiteFileReader.looksLikeJson("cases", "caseId,memberId")).isFalse();
        assertThat(SuiteFileReader.looksLikeJson(null, null)).isFalse();
    }

    @Test
    void theUploadMustBeUtf8AndOfAReasonableSize() {
        assertThat(SuiteFileReader.decode("caseId,note\nTC-1,café".getBytes(StandardCharsets.UTF_8))).endsWith("café");
        assertThat(SuiteFileReader.decode(new byte[0])).isEmpty();
        assertThat(SuiteFileReader.decode(null)).isEmpty();
        // The same text saved by a spreadsheet as a plain CSV: one byte for the accented letter.
        byte[] windows = "caseId,note\nTC-1,café".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> SuiteFileReader.decode(windows)).isInstanceOf(BadInputException.class)
                .hasMessage("The suite file is not UTF-8 text.")
                .satisfies(e -> assertThat(((BadInputException) e).action()).contains("CSV UTF-8"));
        assertThatThrownBy(() -> SuiteFileReader.decode(new byte[SuiteFileReader.MAX_BYTES + 1])).isInstanceOf(BadInputException.class)
                .hasMessageContaining("larger than 5 MB");
    }
}
