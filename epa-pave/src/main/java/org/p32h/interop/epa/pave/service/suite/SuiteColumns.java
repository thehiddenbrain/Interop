package org.p32h.interop.epa.pave.service.suite;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The columns of a suite file. A CSV header and a JSON key are read the same way: letter case,
 * spaces, hyphens and underscores are ignored, and a few plain alternatives are accepted, so
 * "Member ID", "member_id" and "memberId" are one column.
 */
public final class SuiteColumns {

    private SuiteColumns() {
    }

    /** A column name that starts with this is the tester's own and is never read. */
    public static final String IGNORED_PREFIX = "#";

    public enum Column {
        CASE_ID("caseId", "case", "id", "testCase", "testCaseId"),
        PROTOCOL("protocol", "type"),
        HOOK("hook"),
        OPERATION("operation"),
        CHAIN("chain"),
        MEMBER_ID("memberId", "member", "memberIdentifier"),
        NPI("npi", "providerNpi", "provider"),
        PROCEDURE_CODES("procedureCodes", "procedureCode", "codes", "code"),
        DATE_OF_SERVICE("dateOfService", "serviceDate", "dos"),
        EXPECTED("expected", "expectedOutcome", "expectation"),
        SCENARIO_ID("scenarioId", "scenario"),
        TESTER_REF("testerRef", "tester", "reference", "ticket"),
        NOTE("note", "notes", "comment"),
        CLAIM_ID("claimId"),
        SUBSCRIPTION_ID("subscriptionId");

        private final String canonical;
        private final List<String> accepted;

        Column(String canonical, String... alternatives) {
            this.canonical = canonical;
            this.accepted = java.util.stream.Stream.concat(java.util.stream.Stream.of(canonical), Arrays.stream(alternatives))
                    .map(SuiteColumns::normalise).toList();
        }

        /** The name the documentation and the messages use. */
        public String canonical() {
            return canonical;
        }
    }

    /** The column a written name means, or null when this tool does not know it. */
    public static Column find(String written) {
        String wanted = normalise(written);
        if (wanted.isEmpty()) {
            return null;
        }
        for (Column column : Column.values()) {
            if (column.accepted.contains(wanted)) {
                return column;
            }
        }
        return null;
    }

    public static boolean ignored(String written) {
        return written != null && written.strip().startsWith(IGNORED_PREFIX);
    }

    /** True for the one JSON key that holds an object: identifiers for a follow-up read. */
    public static boolean isCorrelation(String written) {
        return "correlation".equals(normalise(written));
    }

    /** Every column name, as the documentation writes it. */
    public static String known() {
        return String.join(", ", Arrays.stream(Column.values()).map(Column::canonical).toList());
    }

    static String normalise(String written) {
        return written == null ? "" : written.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
