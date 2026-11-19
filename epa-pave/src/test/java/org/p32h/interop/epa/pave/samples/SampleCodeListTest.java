package org.p32h.interop.epa.pave.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;

/**
 * The sample code list loads through the real loader the way the README says a real export does:
 * {@code codelist.file} pointed at it, every column recognised, and every row saying it is a sample.
 */
class SampleCodeListTest {

    static final Path SAMPLE = Path.of("samples/codelist/sample-code-list.csv");

    private final ProcedureCodeService service = new ProcedureCodeService(CodelistProperties.of(Map.of("file", SAMPLE.toString())));

    @Test
    void everyColumnIsRecognisedAndTheSelfCheckCountsWhatTheFileHolds() {
        CodeListSelfCheck check = service.selfCheck();

        assertThat(check.placeholder()).isFalse();
        assertThat(check.format()).isEqualTo("csv");
        assertThat(check.unrecognisedColumns()).isEmpty();
        assertThat(check.recognisedColumns()).containsOnlyKeys("code", "description", "codeType", "authRequired", "lineOfBusiness",
                "delegatedVendor", "effectiveDate", "endDate", "notes");
        assertThat(check.rowCount()).isEqualTo(11);
        // One row leaves the flag blank on purpose, so the list does not say: that is not a "no".
        assertThat(check.statingAuthRequirement()).isEqualTo(10);
        assertThat(check.namingVendor()).isEqualTo(7);
    }

    @Test
    void everyRowIsMarkedAsASampleAndQuotedCellsAreReadWhole() {
        List<ProcedureCode> rows = service.search("", null, 100);

        assertThat(rows).hasSize(11);
        assertThat(rows).allSatisfy(row -> assertThat(row.notes()).startsWith("SAMPLE ROW."));
        assertThat(service.rowsFor("E2601", null, null)).singleElement()
                .satisfies(row -> assertThat(row.description()).isEqualTo("Wheelchair seat cushion, general use, under 22\" wide"));
    }

    @Test
    void theShortSpellingsOfTheFlagAreReadAndABlankFlagStaysUnknown() {
        assertThat(service.rowsFor("27447", "Medicare", null)).singleElement()
                .satisfies(row -> assertThat(row.authRequired()).isEqualTo(AuthRequirement.REQUIRED));
        assertThat(service.rowsFor("E0601", null, null)).singleElement()
                .satisfies(row -> assertThat(row.authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED));
        assertThat(service.rowsFor("J3490", null, null)).singleElement()
                .satisfies(row -> assertThat(row.authRequired()).isEqualTo(AuthRequirement.UNKNOWN));
    }
}
