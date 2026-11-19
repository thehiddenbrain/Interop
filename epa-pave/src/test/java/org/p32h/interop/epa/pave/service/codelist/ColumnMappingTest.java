package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.CodelistProperties;

/**
 * Finding the columns of a file whose header row nobody has seen yet. Each column is found
 * through its list of candidate names, and no header is ever left out without a word.
 */
class ColumnMappingTest {

    private static final CodelistProperties.Columns DEFAULTS = CodelistProperties.defaults().columns();

    private static ColumnMapping match(String... headers) {
        return ColumnMapping.match(List.of(headers), DEFAULTS);
    }

    @Test
    void everyCandidateSpellingOfEveryColumnIsRecognised() {
        int tried = 0;
        for (CodeListColumn column : CodeListColumn.values()) {
            assertThat(column.candidates(DEFAULTS)).as(column.field()).isNotEmpty();
            for (String candidate : column.candidates(DEFAULTS)) {
                // Each candidate on its own, beside one header that is nobody's.
                ColumnMapping mapping = match("Internal Ref", candidate);
                assertThat(mapping.position(column)).as(column.field() + " under " + candidate).isEqualTo(1);
                assertThat(mapping.recognised()).as(candidate).containsExactly(Map.entry(column.field(), candidate));
                assertThat(mapping.unrecognised()).containsExactly("Internal Ref");
                assertThat(mapping.warnings()).isEmpty();
                tried++;
            }
        }
        // All ten columns, and the lists are the ones the properties record declares.
        assertThat(CodeListColumn.values()).hasSize(10);
        assertThat(tried).isEqualTo(DEFAULTS.code().size() + DEFAULTS.description().size() + DEFAULTS.codeType().size()
                + DEFAULTS.system().size() + DEFAULTS.authRequired().size() + DEFAULTS.lineOfBusiness().size()
                + DEFAULTS.delegatedVendor().size() + DEFAULTS.effectiveDate().size() + DEFAULTS.endDate().size() + DEFAULTS.notes().size());
    }

    @Test
    void noCandidateBelongsToTwoColumnsOutOfTheBox() {
        // If one did, which column a header fills would depend on the order the columns are looked for.
        Map<String, CodeListColumn> owners = new HashMap<>();
        for (CodeListColumn column : CodeListColumn.values()) {
            for (String candidate : column.candidates(DEFAULTS)) {
                CodeListColumn other = owners.put(CodeListText.key(candidate), column);
                assertThat(other == null || other == column).as(candidate + " is listed for " + other + " and " + column).isTrue();
            }
        }
    }

    @Test
    void aHeaderIsMatchedIgnoringCaseSpacesUnderscoresHyphensAndDots() {
        ColumnMapping mapping = match("PROCEDURE_CODE", "Short-Description", "code.type", " P A  Required ", "LineOfBusiness",
                "Delegated\u00A0Vendor", "EFF. DATE", "Term_Date", "notes");
        assertThat(mapping.recognised()).containsExactly(
                Map.entry("code", "PROCEDURE_CODE"),
                Map.entry("description", "Short-Description"),
                Map.entry("codeType", "code.type"),
                Map.entry("authRequired", "P A  Required"),
                Map.entry("lineOfBusiness", "LineOfBusiness"),
                Map.entry("delegatedVendor", "Delegated\u00A0Vendor"),
                Map.entry("effectiveDate", "EFF. DATE"),
                Map.entry("endDate", "Term_Date"),
                Map.entry("notes", "notes"));
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).isEmpty();
        assertThat(mapping.position(CodeListColumn.CODE)).isZero();
        assertThat(mapping.position(CodeListColumn.NOTES)).isEqualTo(8);
        // The file has no system column, and that is said by its absence, not by a made up position.
        assertThat(mapping.has(CodeListColumn.SYSTEM)).isFalse();
        assertThat(mapping.position(CodeListColumn.SYSTEM)).isEqualTo(-1);
        assertThat(mapping.recognised()).doesNotContainKey("system");
    }

    @Test
    void anythingElseInAHeaderStillTellsItApart() {
        // A question mark is not a space. This header is reported, so someone can add it to the candidates.
        ColumnMapping mapping = match("Code", "PA Required?", "Auth Required (Y/N)", "Code Description 2");
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"));
        assertThat(mapping.unrecognised()).containsExactly("PA Required?", "Auth Required (Y/N)", "Code Description 2");
    }

    @Test
    void theFirstCandidateInTheListWinsNotTheFirstHeaderInTheFile() {
        // "code" is listed before "cpt", so the Code column is read although CPT stands to its left.
        ColumnMapping mapping = match("CPT", "Long Description", "Code", "Description");
        assertThat(mapping.position(CodeListColumn.CODE)).isEqualTo(2);
        assertThat(mapping.position(CodeListColumn.DESCRIPTION)).isEqualTo(3);
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("description", "Description"));
        // The two that lost matched a candidate, so they are not among the unrecognised.
        // They were not read either, and each gets a warning that says which header was read in its place.
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).containsExactly(
                "The header \"CPT\" is a name this tool knows for code, but code was read from \"Code\", which comes first in "
                        + "codelist.columns.code. The column \"CPT\" was not read.",
                "The header \"Long Description\" is a name this tool knows for description, but description was read from "
                        + "\"Description\", which comes first in codelist.columns.description. The column \"Long Description\" was not read.");
    }

    @Test
    void theOrderOfTheCandidatesIsTheOrderConfigured() {
        CodelistProperties.Columns cptFirst = CodelistProperties.of(Map.of("columns.code[0]", "cpt", "columns.code[1]", "code")).columns();
        ColumnMapping mapping = ColumnMapping.match(List.of("Code", "CPT"), cptFirst);
        assertThat(mapping.position(CodeListColumn.CODE)).isEqualTo(1);
        assertThat(mapping.recognised()).containsEntry("code", "CPT");
        assertThat(mapping.warnings()).singleElement().asString().startsWith("The header \"Code\" is a name this tool knows for code");
    }

    @Test
    void aHeaderThatMatchesNoCandidateIsReportedAsWritten() {
        ColumnMapping mapping = match("Code", " Internal Ref ", "Reviewer\u00A0", "PA");
        assertThat(mapping.unrecognised()).containsExactly("Internal Ref", "Reviewer");
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("authRequired", "PA"));
        assertThat(mapping.warnings()).isEmpty();
    }

    @Test
    void withoutACodeColumnTheMappingSaysSoAndStillAccountsForEveryHeader() {
        ColumnMapping mapping = match("Proc", "Desc", "Needs Auth");
        assertThat(mapping.has(CodeListColumn.CODE)).isFalse();
        assertThat(mapping.recognised()).containsExactly(Map.entry("description", "Desc"));
        assertThat(mapping.unrecognised()).containsExactly("Proc", "Needs Auth");
    }

    @Test
    void aHeaderThatAppearsTwiceIsReadOnceAndTheOtherIsNamed() {
        ColumnMapping mapping = match("Code", "Notes", "PA", "notes");
        assertThat(mapping.position(CodeListColumn.NOTES)).isEqualTo(1);
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).containsExactly("The header \"notes\" appears more than once, in columns 2 and 4. Column 2 was "
                + "read as notes and column 4 was not read.");
    }

    @Test
    void aHeaderCellWithNoNameIsKeptApartSoTheLoaderCanCheckWhetherAnythingIsUnderIt() {
        ColumnMapping mapping = match("Code", "", "PA", "  ");
        assertThat(mapping.unnamed()).containsExactly(1, 3);
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).isEmpty();
    }

    @Test
    void aNameConfiguredForTwoColumnsFillsOneAndTheOtherIsWarnedAbout() {
        // An operator adds "Type" as a header for the coding system. It is already a candidate of the code type.
        CodelistProperties.Columns columns = CodelistProperties.of(Map.of("columns.system[0]", "type", "columns.system[1]", "system")).columns();
        ColumnMapping one = ColumnMapping.match(List.of("Code", "Type"), columns);
        assertThat(one.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("codeType", "Type"));
        assertThat(one.has(CodeListColumn.SYSTEM)).isFalse();
        assertThat(one.warnings()).containsExactly("The header \"Type\" is listed under both codelist.columns.code-type and "
                + "codelist.columns.system. It was read as codeType and not as system. Take it out of one of the two.");

        // The column that lost still finds its own header through its next candidate.
        ColumnMapping two = ColumnMapping.match(List.of("Code", "Type", "System"), columns);
        assertThat(two.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("codeType", "Type"), Map.entry("system", "System"));
    }

    @Test
    void everyHeaderEndsUpInExactlyOnePlace() {
        List<String> headers = List.of("HCPCS", "Code", "Desc", "Description", "Mystery", "", "Notes", "Notes", "Plan Type", "LOB");
        ColumnMapping mapping = ColumnMapping.match(headers, DEFAULTS);
        List<Integer> accounted = new ArrayList<>();
        for (CodeListColumn column : CodeListColumn.values()) {
            if (mapping.has(column)) {
                accounted.add(mapping.position(column));
            }
        }
        // Read: Code, Description, LOB, Notes. Not recognised: Mystery. Unnamed: one. Warned about: HCPCS, Desc, the second Notes, Plan Type.
        assertThat(accounted).containsExactlyInAnyOrder(1, 3, 9, 6);
        assertThat(mapping.unrecognised()).containsExactly("Mystery");
        assertThat(mapping.unnamed()).containsExactly(5);
        assertThat(mapping.warnings()).hasSize(4);
        assertThat(accounted.size() + mapping.unrecognised().size() + mapping.unnamed().size() + mapping.warnings().size()).isEqualTo(headers.size());
    }
}
