package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** What the lists endpoints answer. Each prints counts only: a list is member data. */
public final class TestListViews {

    private TestListViews() {
    }

    /**
     * @param enabled false when test-lists.enabled is false: the list is then empty and cannot be changed
     * @param problem why the file could not be read, or absent
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"enabled", "members", "count", "problem"})
    public record MemberList(boolean enabled, List<TestMember> members, int count, String problem) {

        @Override
        public String toString() {
            return "MemberList[enabled=" + enabled + ", count=" + count + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"enabled", "providers", "count", "problem"})
    public record ProviderList(boolean enabled, List<TestProvider> providers, int count, String problem) {

        @Override
        public String toString() {
            return "ProviderList[enabled=" + enabled + ", count=" + count + "]";
        }
    }

    /** What a row of an imported file does, or would do on a dry run. */
    public enum Status {
        ADD, REPLACE, REFUSED
    }

    /**
     * @param line   the line of the file the row starts on
     * @param label  the row's label, as the entry would carry it
     * @param reason why the row is refused, or absent
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"line", "status", "label", "reason"})
    public record ImportRow(int line, Status status, String label, String reason) {

        @Override
        public String toString() {
            return "ImportRow[line=" + line + ", " + status + "]";
        }
    }

    /**
     * @param columnsUsed    the columns read, by the list's names
     * @param columnsIgnored the file's headers that name nothing the list keeps
     * @param applied        true when the rows were saved; false on a dry run
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"columnsUsed", "columnsIgnored", "rows", "added", "replaced", "refused", "applied"})
    public record ImportResult(List<String> columnsUsed, List<String> columnsIgnored, List<ImportRow> rows, int added, int replaced,
            int refused, boolean applied) {

        @Override
        public String toString() {
            return "ImportResult[added=" + added + ", replaced=" + replaced + ", refused=" + refused + ", applied=" + applied + "]";
        }
    }

    /**
     * The body of an import: the file's text, read in the browser, and whether to save.
     *
     * @param dryRun true shows what each row would do and saves nothing. Never assumed: a body
     *               without it is refused
     */
    public record ImportRequest(String csv, Boolean dryRun) {

        @Override
        public String toString() {
            return "ImportRequest[length=" + (csv == null ? 0 : csv.length()) + ", dryRun=" + dryRun + "]";
        }
    }
}
