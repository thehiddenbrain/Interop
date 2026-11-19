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
}
