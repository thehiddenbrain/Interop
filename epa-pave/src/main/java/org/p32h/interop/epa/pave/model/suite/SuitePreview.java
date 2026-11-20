package org.p32h.interop.epa.pave.model.suite;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentRef;

/**
 * What the tool made of a suite file, before anything is run. Nothing is sent to produce it.
 *
 * <p>It never repeats a member ID from the file.
 *
 * @param runnable     true when the file can be started. False when it has a problem that affects
 *                     every row, or no row that can be run
 * @param headline     one plain sentence saying what would run
 * @param problems     what is wrong with the file as a whole
 * @param rowProblems  what is wrong with single rows. Those rows are reported as invalid and the
 *                     rest still run
 * @param rowProblemCount how many rows have a problem, which can be more than are listed
 * @param kinds        how many rows run each thing, such as "CRD order-sign": 12
 * @param writes       rows that create or change something on the far side
 * @param writeNote    what happens to those rows against the environment now active
 * @param columns      the columns or keys the file has, as the tool read them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuitePreview(
        String name,
        String format,
        boolean runnable,
        String headline,
        int total,
        int valid,
        List<String> problems,
        List<RowProblem> rowProblems,
        int rowProblemCount,
        List<Kind> kinds,
        int writes,
        String writeNote,
        int withExpectation,
        List<String> columns,
        String mode,
        String target,
        EnvironmentRef environment) {

    public SuitePreview {
        problems = problems == null ? List.of() : List.copyOf(problems);
        rowProblems = rowProblems == null ? List.of() : List.copyOf(rowProblems);
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** One row that will be reported as invalid, and why. */
    public record RowProblem(int row, String caseId, List<String> problems) {

        public RowProblem {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** Prints the row only. The case id is a cell of the file. */
        @Override
        public String toString() {
            return "RowProblem[" + row + "]";
        }
    }

    /** How many rows run one thing. */
    public record Kind(String what, int count) {
    }

    /** Prints the counts. The name, the columns and the problems come from the file. */
    @Override
    public String toString() {
        return "SuitePreview[" + total + " cases, " + valid + " valid]";
    }
}
