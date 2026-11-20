package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * What the code list writes to the log when the application starts: where the list came from and
 * how much of it there is, a count of warnings, and the reason when it cannot be used. Never the
 * text of a cell.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProcedureCodeServiceLogTest {

    @TempDir
    Path directory;

    @Test
    void aLoadedListLogsItsSourceAndItsCountsAndNoCell(CapturedOutput output) {
        // Every cell here holds a word that appears nowhere else, so finding one in the log means a cell was logged.
        Path file = TestCodeLists.write(directory, "codes.csv", "Code,Description,PA,Vendor,Mystery\n"
                + "QX958,Zebra crossing study,See the walrus policy,Giraffe Health,ocelot\nE0601,CPAP device,No,,\n");
        TestCodeLists.load(file);

        assertThat(output.getOut()).contains("Procedure code list loaded from " + file + " as csv: 2 rows, 1 state a prior authorization "
                + "requirement, 1 name a vendor");
        // Two warnings: the flag that was not read, and the vendor nobody knows. The log gives the count and where to read them.
        assertThat(output.getOut()).contains("The procedure code list was loaded with 2 warning(s). GET /api/procedure-codes/self-check "
                + "lists them.");
        assertThat(output.getAll()).doesNotContain("QX958").doesNotContain("Zebra").doesNotContain("walrus").doesNotContain("Giraffe")
                .doesNotContain("ocelot").doesNotContain("Mystery");
        assertThat(output.getAll()).doesNotContain("placeholder");
    }

    @Test
    void thePlaceholderIsNamedInTheLogAndIsNotCountedAsAWarningAboutTheFile(CapturedOutput output) {
        new ProcedureCodeService(CodelistProperties.defaults());
        assertThat(output.getOut()).contains("Procedure code list loaded from classpath:codelist/placeholder-codes.csv as csv: 43 rows");
        assertThat(output.getOut()).contains("The bundled placeholder code list is in use. Every row in it is synthetic.");
        assertThat(output.getAll()).doesNotContain("warning(s)");
    }

    @Test
    void aListThatCannotBeUsedSaysWhyInOneLineBeforeTheApplicationStops(CapturedOutput output) {
        Path missing = directory.resolve("not-there.csv");
        assertThatThrownBy(() -> TestCodeLists.load(missing)).isInstanceOf(UnusableCodeListException.class);
        assertThat(output.getOut()).contains("ERROR").contains("The procedure code list cannot be used, so the application will not start. "
                + "The code list file " + missing + " does not exist.");
        assertThat(output.getAll()).doesNotContain("Procedure code list loaded");
    }

    @Test
    void aFileThatTurnsOutToHoldAMembersRecordDoesNotPutThatRecordInTheLog(CapturedOutput output) {
        // codelist.file pointed at the wrong file. Its first row is a record, not a header row, and
        // the failure that lists the first row is logged. Every value here is a placeholder.
        Path file = TestCodeLists.write(directory, "members.csv", "S123456789,Placeholder-Surname,Given,1970-01-31,F,"
                + "1200 Placeholder Street,SUB-PLACEHOLDER-000001\nS987654321,Another-Placeholder,Name,1980-12-01,M,,\n");
        assertThatThrownBy(() -> TestCodeLists.load(file)).isInstanceOf(UnusableCodeListException.class);

        // The one line still says what is wrong and what was found, cell by cell.
        assertThat(output.getOut()).contains("ERROR").contains("The procedure code list cannot be used, so the application will not start. "
                + "The code list file " + file + " has no column this tool recognises as the procedure code. Headers found: "
                + "(a cell that does not read as a header, 10 characters), \"Placeholder-Surname\", \"Given\", "
                + "(a cell that does not read as a header, 10 characters), \"F\", (a cell that does not read as a header, 23 characters), "
                + "(a cell that does not read as a header, 22 characters).");
        // The member ID, the date of birth, the address and the subscriber ID are in no line of it, from the first row or any other.
        assertThat(output.getAll()).doesNotContain("S123456789").doesNotContain("1970-01-31").doesNotContain("Placeholder Street")
                .doesNotContain("SUB-PLACEHOLDER").doesNotContain("S987654321").doesNotContain("1980-12-01");
    }
}
