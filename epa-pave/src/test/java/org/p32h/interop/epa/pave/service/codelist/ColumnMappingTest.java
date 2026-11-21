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
                CodeListColumn other = owners.put(CodeListText.headerKey(candidate), column);
                assertThat(other == null || other == column).as(candidate + " is listed for " + other + " and " + column).isTrue();
                // None is written with a bracketed ending, so each can also find a header that has one.
                assertThat(CodeListText.headerBase(candidate)).as(candidate).isEqualTo(CodeListText.headerKey(candidate));
            }
        }
    }

    @Test
    void theThreeNamesAddedForTheCodeColumnComeAfterTheOnesThatWereThereBefore() {
        // Tried last, so a file that was read one way before these were added is read the same way now.
        assertThat(DEFAULTS.code()).containsExactly("code", "procedure code", "proc code", "cpt", "hcpcs", "cpt/hcpcs", "cpt hcpcs code",
                "service code", "billing code", "cpt code", "hcpcs code", "procedure");
        ColumnMapping mapping = match("Procedure", "HCPCS Code", "CPT Code", "Billing Code");
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Billing Code"));
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).hasSize(3);
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
    void aHeaderIsAlsoMatchedIgnoringASlashAQuestionMarkAndAColon() {
        // How a person titles a column: a question, a label with a colon, two code sets with a slash between them.
        ColumnMapping mapping = match("CPT/HCPCS Code", "Description:", "PA Required?", "Line of Business:", "Eff. Date:", "Vendor ?");
        // Each is found, and each is reported as it is written in the file, punctuation and all.
        assertThat(mapping.recognised()).containsExactly(
                Map.entry("code", "CPT/HCPCS Code"),
                Map.entry("description", "Description:"),
                Map.entry("authRequired", "PA Required?"),
                Map.entry("lineOfBusiness", "Line of Business:"),
                Map.entry("delegatedVendor", "Vendor ?"),
                Map.entry("effectiveDate", "Eff. Date:"));
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).isEmpty();
        // The slash is ignored on both sides: the candidate written with one finds the header written without.
        assertThat(match("CPT HCPCS", "PA").recognised()).containsExactly(Map.entry("code", "CPT HCPCS"), Map.entry("authRequired", "PA"));
        assertThat(match("cpt/hcpcs").recognised()).containsExactly(Map.entry("code", "cpt/hcpcs"));
    }

    @Test
    void aBracketedPartAtTheEndOfAHeaderIsIgnored() {
        // What a spreadsheet author adds to say which values a column takes, or in which unit.
        ColumnMapping mapping = match("Code (CPT or HCPCS)", "Auth Required (Y/N)", "Description [short]", "Effective Date (MM/DD/YYYY)",
                "Notes (internal) (2026)");
        assertThat(mapping.recognised()).containsExactly(
                Map.entry("code", "Code (CPT or HCPCS)"),
                Map.entry("description", "Description [short]"),
                Map.entry("authRequired", "Auth Required (Y/N)"),
                Map.entry("effectiveDate", "Effective Date (MM/DD/YYYY)"),
                Map.entry("notes", "Notes (internal) (2026)"));
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).isEmpty();
        assertThat(mapping.position(CodeListColumn.AUTH_REQUIRED)).isEqualTo(1);
        // With a question mark or a colon after the bracket as well.
        assertThat(match("Code", "PA Required (Y/N)?").recognised()).containsEntry("authRequired", "PA Required (Y/N)?");
        assertThat(match("Code", "PA Required? (Y/N):").recognised()).containsEntry("authRequired", "PA Required? (Y/N):");
    }

    @Test
    void onlyABracketedPartAtTheEndIsIgnoredAndAHeaderThatIsNothingElseIsNotAName() {
        // In the middle of a header the bracket is part of the name, and a header that is only a
        // bracket has no name left to match. An unclosed bracket is not a bracketed part at all.
        ColumnMapping mapping = match("Code", "PA (prior) Required", "(Y/N)", "Description (short", "Notes) more");
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"));
        assertThat(mapping.unrecognised()).containsExactly("PA (prior) Required", "(Y/N)", "Description (short", "Notes) more");
        assertThat(mapping.warnings()).isEmpty();
    }

    @Test
    void aHeaderThatMatchesAsWrittenIsReadBeforeOneThatMatchesOnlyWithoutItsBracketedEnding() {
        // "Description" is a name of the column as it stands. "Description (Long)" is one only
        // once its ending is left out, so it does not win by standing further to the left.
        ColumnMapping mapping = match("Code", "Description (Long)", "Description", "PA");
        assertThat(mapping.position(CodeListColumn.DESCRIPTION)).isEqualTo(2);
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("description", "Description"),
                Map.entry("authRequired", "PA"));
        // The one that was not read matched a name, so it is not among the unrecognised. It is named, with the reason.
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).containsExactly("The header \"Description (Long)\" reads as a name this tool knows for description once "
                + "its bracketed ending is left out, but description was read from \"Description\". The column \"Description (Long)\" was "
                + "not read.");

        // The same holds across the names of one column. "code" is listed before "procedure code",
        // and still the header that is a name as written is the one read.
        ColumnMapping across = match("Code (retired)", "Procedure Code");
        assertThat(across.recognised()).containsExactly(Map.entry("code", "Procedure Code"));
        assertThat(across.warnings()).containsExactly("The header \"Code (retired)\" reads as a name this tool knows for code once its "
                + "bracketed ending is left out, but code was read from \"Procedure Code\". The column \"Code (retired)\" was not read.");
    }

    @Test
    void twoHeadersThatDifferOnlyInTheirBracketedEndingsAreNeverChosenBetween() {
        // One flag for each line of business, told apart by what stands in the brackets. Reading
        // the first as the flag would answer for a Commercial member with the Medicare column.
        ColumnMapping mapping = match("Code", "PA Required (Medicare)", "PA Required (Commercial)", "Notes");
        assertThat(mapping.has(CodeListColumn.AUTH_REQUIRED)).isFalse();
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("notes", "Notes"));
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).containsExactly("The headers \"PA Required (Medicare)\" and \"PA Required (Commercial)\" read as the "
                + "same name once their bracketed endings are left out, a name this tool knows for authRequired. Which of them is meant "
                + "cannot be told, so neither was read. Name the one to read, exactly as written, under codelist.columns.auth-required.");

        // Three of them read as a sentence too.
        assertThat(match("Code", "Description (short)", "Description (long)", "Description [web]").warnings()).singleElement().asString()
                .startsWith("The headers \"Description (short)\", \"Description (long)\" and \"Description [web]\" read as the same name")
                .contains("so none of them was read. Name the one to read, exactly as written, under codelist.columns.description.");
    }

    @Test
    void namingOneOfThemAsWrittenIsWhatReadsIt() {
        // What the warning says to do. A name configured with a bracketed ending is matched as written and never without it.
        CodelistProperties.Columns medicare = CodelistProperties.of(Map.of("columns.auth-required[0]", "PA Required (Medicare)")).columns();
        ColumnMapping mapping = ColumnMapping.match(List.of("Code", "PA Required (Medicare)", "PA Required (Commercial)"), medicare);
        assertThat(mapping.position(CodeListColumn.AUTH_REQUIRED)).isEqualTo(1);
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("authRequired", "PA Required (Medicare)"));
        // The other one is a column nobody named, and is reported as that.
        assertThat(mapping.unrecognised()).containsExactly("PA Required (Commercial)");
        assertThat(mapping.warnings()).isEmpty();

        // In a file that has only the other column, the name configured for Medicare does not find it.
        ColumnMapping other = ColumnMapping.match(List.of("Code", "PA Required (Commercial)", "PA Required"), medicare);
        assertThat(other.has(CodeListColumn.AUTH_REQUIRED)).isFalse();
        assertThat(other.unrecognised()).containsExactly("PA Required (Commercial)", "PA Required");
    }

    @Test
    void aHeaderReadAsWrittenIsNotReadASecondTimeWithoutItsEnding() {
        // The description column of this file was named in full, ending and all. Without its
        // ending the same header reads as the code, and one header is one column at most.
        CodelistProperties.Columns columns = CodelistProperties.of(Map.of("columns.description[0]", "Code (description)")).columns();
        ColumnMapping mapping = ColumnMapping.match(List.of("Code (description)", "PA"), columns);
        assertThat(mapping.recognised()).containsExactly(Map.entry("description", "Code (description)"), Map.entry("authRequired", "PA"));
        assertThat(mapping.has(CodeListColumn.CODE)).isFalse();
        assertThat(mapping.unrecognised()).isEmpty();
        assertThat(mapping.warnings()).isEmpty();
    }

    @Test
    void whatElseIsInAHeaderStillTellsItApart() {
        // A digit, a hash, a star, an ampersand: none of these is ignored, so each of these is
        // another name and is reported for someone to add to the candidates.
        ColumnMapping mapping = match("Code", "Code Description 2", "PA Required*", "Code #", "Vendor & Delegate", "Requires Authorization?");
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"));
        assertThat(mapping.unrecognised()).containsExactly("Code Description 2", "PA Required*", "Code #", "Vendor & Delegate",
                "Requires Authorization?");
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

        // The same when the header carries a bracketed ending and is found without it.
        ColumnMapping bracketed = ColumnMapping.match(List.of("Code", "Type (CPT or HCPCS)"), columns);
        assertThat(bracketed.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("codeType", "Type (CPT or HCPCS)"));
        assertThat(bracketed.has(CodeListColumn.SYSTEM)).isFalse();
        assertThat(bracketed.warnings()).containsExactly("The header \"Type (CPT or HCPCS)\" reads as a name that is listed under both "
                + "codelist.columns.code-type and codelist.columns.system. It was read as codeType and not as system. Take the name out "
                + "of one of the two.");
    }

    @Test
    void theColumnsAreReportedInTheOrderTheyAreLookedForHoweverEachWasFound() {
        // The description is found only without its bracketed ending, after every column that matches as written.
        ColumnMapping mapping = match("Notes", "PA", "Description (short)", "Code");
        assertThat(mapping.recognised()).containsExactly(Map.entry("code", "Code"), Map.entry("description", "Description (short)"),
                Map.entry("authRequired", "PA"), Map.entry("notes", "Notes"));
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
