package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.DelegatedVendors;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * The list bundled with the tool. It is synthetic, it says so on every row, and it has to obey
 * the rules about which vendor serves which line of business, because a fixture that breaks them
 * teaches a tester something false.
 */
class PlaceholderCodeListTest {

    private static final Set<String> PUBLIC_PLANS = Set.of("Medicare", "Medicaid");
    private static final Set<String> ALL_LINES = Set.of("Medicare", "Medicaid", "Commercial");

    private final ProcedureCodeService service = new ProcedureCodeService(CodelistProperties.defaults());
    private final List<ProcedureCode> rows = service.search("", null, 10_000);

    private static String text() throws IOException {
        try (InputStream in = PlaceholderCodeListTest.class.getClassLoader().getResourceAsStream(CodeListLoader.PLACEHOLDER)) {
            assertThat(in).as("the placeholder list is on the class path").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void itLoadsAndTheSelfCheckHasNothingToReportButThatItIsAPlaceholder() {
        CodeListSelfCheck check = service.selfCheck();
        assertThat(check.placeholder()).isTrue();
        assertThat(check.source()).isEqualTo("classpath:codelist/placeholder-codes.csv");
        assertThat(check.format()).isEqualTo("csv");
        assertThat(check.unrecognisedColumns()).isEmpty();
        assertThat(check.recognisedColumns()).containsExactly(Map.entry("code", "Procedure Code"), Map.entry("description", "Description"),
                Map.entry("codeType", "Code Type"), Map.entry("authRequired", "PA Required"), Map.entry("lineOfBusiness", "Line of Business"),
                Map.entry("delegatedVendor", "Delegated Vendor"), Map.entry("effectiveDate", "Effective Date"),
                Map.entry("endDate", "End Date"), Map.entry("notes", "Notes"));
        // No ragged row, no unread flag, no unread date, no cell that names nothing: one warning, and it is the notice.
        assertThat(check.warnings()).containsExactly("The bundled placeholder list is in use, not a P32H export. Every row in it is "
                + "synthetic. Set codelist.file to the real export and restart.");
    }

    @Test
    void theSelfCheckCountsAreTheOnesTheFileHas() {
        // Pinned on purpose: a quote lost in an edit would merge two rows, and only a count would show it.
        CodeListSelfCheck check = service.selfCheck();
        assertThat(check.rowCount()).isEqualTo(43);
        assertThat(rows).hasSize(43);
        assertThat(check.statingAuthRequirement()).isEqualTo(39);
        assertThat(check.namingVendor()).isEqualTo(22);
        assertThat(check.linesOfBusiness()).containsExactly(Map.entry("Medicare", 21), Map.entry("Medicaid", 7), Map.entry("Commercial", 10));
    }

    @Test
    void everyRowSaysThatItIsAPlaceholder() {
        assertThat(rows).allSatisfy(row -> assertThat(row.notes()).as(row.code()).startsWith("Placeholder row. Not from a P32H export."));
    }

    @Test
    void noRowBreaksTheRulesAboutWhichVendorServesWhichLineOfBusiness() {
        int withVendor = 0;
        for (ProcedureCode row : rows) {
            Set<String> lines = new LinkedHashSet<>(LineMatcher.names(row.lineOfBusiness()));
            String where = row.code() + " for " + row.lineOfBusiness();
            // Only the three lines: THP Medicare and Medicaid, and HPHC Commercial.
            assertThat(ALL_LINES).as(where).containsAll(lines);
            if (Texts.blank(row.delegatedVendor())) {
                continue;
            }
            withVendor++;
            // One of the four current vendors, in its own spelling.
            assertThat(DelegatedVendors.CURRENT).as(where).contains(row.delegatedVendor());
            // A vendor that serves only some lines must be on a row that names its lines. A row that
            // states no line is offered for every line, Commercial included.
            assertThat(lines).as(where + " names a vendor and must name its lines").isNotEmpty();
            switch (row.delegatedVendor()) {
                case "eviCore", "Carelon" -> assertThat(PUBLIC_PLANS).as(where + ": public plans only").containsAll(lines);
                case "Optum Post Acute" -> assertThat(lines).as(where + ": Medicare only").containsExactly("Medicare");
                case "Evolent" -> assertThat(ALL_LINES).as(where).containsAll(lines);
                default -> throw new AssertionError("unexpected vendor on " + where);
            }
        }
        assertThat(withVendor).isEqualTo(22);
        // Each of the four is there, so each rule above was applied to something.
        assertThat(rows.stream().map(ProcedureCode::delegatedVendor).filter(Texts::present).distinct())
                .containsExactlyInAnyOrderElementsOf(DelegatedVendors.CURRENT);
    }

    @Test
    void eachVendorIsOnTheCodesOfItsOwnSpecialty() {
        // Sleep to eviCore, imaging, cardiology and musculoskeletal to Evolent, genetic testing to
        // Carelon, post-acute care to Optum Post Acute. The codes are listed here by specialty, so
        // a row moved to the wrong vendor fails.
        Map<String, Set<String>> codesByVendor = Map.of(
                "eviCore", Set.of("95810", "95811", "G0398", "G0399", "G0400"),
                "Evolent", Set.of("70553", "C8909", "S8037", "G0235", "78452", "C8929", "27447", "G0260"),
                "Carelon", Set.of("81162", "S3870"),
                "Optum Post Acute", Set.of("G0299", "G0151", "G0152"));
        for (ProcedureCode row : rows) {
            if (Texts.present(row.delegatedVendor())) {
                assertThat(codesByVendor.get(row.delegatedVendor())).as(row.delegatedVendor() + " on " + row.code()).contains(row.code());
            }
        }
    }

    @Test
    void itHoldsEveryKindOfRowThePickerAndTheChecksHaveToCopeWith() {
        // Says yes, says no, and does not say.
        assertThat(rows).extracting(ProcedureCode::authRequired).contains(AuthRequirement.REQUIRED, AuthRequirement.NOT_REQUIRED,
                AuthRequirement.UNKNOWN);
        // The rows that do not say are blank cells, so they have no source text and are never a "no".
        assertThat(rows.stream().filter(row -> row.authRequired() == AuthRequirement.UNKNOWN)).hasSize(4)
                .allSatisfy(row -> assertThat(row.authRequiredSource()).isNull());
        // Rows that state no line of business, which are offered for every line.
        assertThat(rows.stream().filter(row -> row.lineOfBusiness() == null)).hasSize(6);
        // One row for two lines.
        assertThat(rows).extracting(ProcedureCode::lineOfBusiness).contains("Medicare/Medicaid");

        // The same code under two lines of business with different answers.
        assertThat(service.rowsFor("95810", "Medicare", null)).singleElement()
                .satisfies(row -> assertThat(row.authRequired()).isEqualTo(AuthRequirement.REQUIRED));
        assertThat(service.rowsFor("95810", "Commercial", null)).singleElement()
                .satisfies(row -> assertThat(row.authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED));
        assertThat(service.rowsFor("C8929", "Medicare", null).get(0).authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(service.rowsFor("C8929", "Commercial", null).get(0).authRequired()).isEqualTo(AuthRequirement.REQUIRED);

        // The same code and line with one answer until the end of 2025 and another from 2026.
        assertThat(service.rowsFor("G0399", "Medicare", null)).hasSize(2);
        assertThat(service.rowsFor("G0399", "Medicare", LocalDate.of(2025, 6, 1))).singleElement().satisfies(row -> {
            assertThat(row.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
            assertThat(row.effectiveDate()).isEqualTo("2025-01-01");
            assertThat(row.endDate()).isEqualTo("2025-12-31");
        });
        assertThat(service.rowsFor("G0399", "Medicare", LocalDate.of(2026, 6, 1))).singleElement()
                .satisfies(row -> assertThat(row.authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED));
    }

    @Test
    void itExercisesTheReaderWithACommaAQuoteAndABlankCell() throws IOException {
        // Descriptions with commas, read whole.
        assertThat(rows.stream().filter(row -> row.description().contains(","))).hasSizeGreaterThan(20);
        assertThat(service.rowsFor("E0260", null, null).get(0).description()).isEqualTo("Hospital bed, semi-electric, with side rails and a mattress");
        // A double quote, written twice in the file and read as one.
        assertThat(text()).contains("under 22\"\" wide\"");
        assertThat(service.rowsFor("E2601", null, null).get(0).description()).isEqualTo("Wheelchair seat cushion, general use, under 22\" wide");
        // A row with nothing but its code, its description and its type.
        ProcedureCode bare = service.rowsFor("A7030", null, null).get(0);
        assertThat(bare.authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(bare.lineOfBusiness()).isNull();
        assertThat(bare.delegatedVendor()).isNull();
        assertThat(bare.effectiveDate()).isNull();
        // Dates are written the way a spreadsheet exports them and served in ISO form.
        assertThat(text()).contains(",1/1/2026,").doesNotContain("2026-01-01");
        assertThat(service.rowsFor("E0601", "Medicare", null).get(0).effectiveDate()).isEqualTo("2026-01-01");
    }

    @Test
    void theFileIsPlainTextThatAnyEditorAndAnyCheckoutLeaveAlone() throws IOException {
        String text = text();
        assertThat(text.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c < 0x7F))).as("plain ASCII, with LF line ends and no tab").isTrue();
        assertThat(text).endsWith("\n").doesNotContain("\n\n");
        // Every line has the nine cells of the header, so even a reader that got quoting wrong would notice a damaged row.
        CsvContent content = CsvReader.commaSeparated().read(text);
        assertThat(content.problems()).isEmpty();
        assertThat(content.header().cells()).hasSize(9);
        assertThat(content.rows()).hasSize(43).allSatisfy(row -> assertThat(row.cells()).hasSize(9));
    }

    @Test
    void mostCodesAreHcpcsLevelTwoAndTheCodesTheScenariosUseAreThere() {
        long levelTwo = rows.stream().filter(row -> row.code().matches("[A-V]\\d{4}")).count();
        long fiveDigit = rows.stream().filter(row -> row.code().matches("\\d{5}")).count();
        assertThat(levelTwo + fiveDigit).isEqualTo(rows.size());
        assertThat(levelTwo).isGreaterThan(fiveDigit);
        // The code type column agrees with the shape of the code.
        assertThat(rows).allSatisfy(row -> assertThat(row.codeType()).isEqualTo(row.code().matches("\\d{5}") ? "CPT" : "HCPCS"));
        for (String code : new String[] {"95810", "95811", "70553", "E0601", "G0299", "J1745"}) {
            assertThat(service.rowsFor(code, null, null)).as(code).isNotEmpty();
        }
        // The code the tests use for "not in the list" is not in the list.
        assertThat(service.rowsFor("99999", null, null)).isEmpty();
    }
}
