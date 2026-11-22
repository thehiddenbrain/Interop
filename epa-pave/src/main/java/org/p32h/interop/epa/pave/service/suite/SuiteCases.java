package org.p32h.interop.epa.pave.service.suite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.cases.ChainExpectation;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.cases.ProtocolCaseRunner;
import org.p32h.interop.epa.pave.service.run.MemberInText;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.suite.SuiteColumns.Column;
import org.p32h.interop.epa.pave.service.suite.SuiteTable.SourceRow;

/**
 * Turns the rows of a suite file into cases, and says what is wrong with any row that cannot be
 * one. Nothing is sent and nothing is looked up.
 *
 * <p>The rule throughout: a row this tool cannot read exactly is reported and not run. It is never
 * run with a guess in place of what was written, because a suite that passes on a guess has
 * tested something nobody asked for.
 *
 * <p>No message here quotes a member ID.
 */
public final class SuiteCases {

    /** More than the programme's test package by a wide margin, and few enough to hold in memory. */
    public static final int MAX_CASES = 5000;

    private static final Pattern CASE_ID = Pattern.compile("[A-Za-z0-9 ._:/#()\\-]{1,64}");
    private static final Pattern CODE_SEPARATOR = Pattern.compile("[|;,\\s]+");
    /**
     * One procedure code: letters, digits, dots and hyphens, with at least one digit. Every code of
     * the code list has one, and a word typed between codes, such as "and", has none.
     */
    private static final Pattern PROCEDURE_CODE = Pattern.compile("(?=[A-Za-z.\\-]*[0-9])[A-Za-z0-9.\\-]{1,20}");
    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9\\-._:/|]{1,128}");

    private SuiteCases() {
    }

    /**
     * @param name     the name the file was uploaded under
     * @param mock     true on the mock profile, where a row may choose a scenario
     * @param outcomes used to check a declared expectation before anything is sent
     */
    public static SuiteDefinition define(String name, SuiteTable table, boolean mock, OutcomeCatalogue outcomes) {
        return define(name, table, mock, outcomes, null);
    }

    /**
     * @param runner the runner the suite would use. It says which rows this build cannot run and
     *               what its services would refuse before sending, so the preview counts only what
     *               will really run. Null when there is none, and then only the file itself is checked
     */
    public static SuiteDefinition define(String name, SuiteTable table, boolean mock, OutcomeCatalogue outcomes, ProtocolCaseRunner runner) {
        List<String> problems = new ArrayList<>(table.problems());
        if (!problems.isEmpty()) {
            return new SuiteDefinition(name, table.format(), table.columns(), List.of(), problems);
        }
        boolean csv = SuiteTable.CSV.equals(table.format());
        if (csv) {
            // A CSV column applies to every row, so a column this tool does not know is one problem
            // with the file and not the same problem repeated on every row.
            problems.addAll(columnProblems(table.columns()));
        }
        if (table.rows().isEmpty()) {
            problems.add("The file has no cases." + (csv ? " The first row names the columns and each row after it is one case." : ""));
        }
        if (table.rows().size() > MAX_CASES) {
            problems.add("The file has " + table.rows().size() + " cases and this tool runs at most " + MAX_CASES
                    + " in one suite. Split the file.");
        }
        if (!problems.isEmpty()) {
            return new SuiteDefinition(name, table.format(), table.columns(), List.of(), problems);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<SuiteRow> rows = new ArrayList<>();
        for (SourceRow source : table.rows()) {
            rows.add(row(source, csv, mock, outcomes, seen, runner));
        }
        return new SuiteDefinition(name, table.format(), table.columns(), rows, List.of(), table.skipped());
    }

    private static List<String> columnProblems(List<String> columns) {
        List<String> problems = new ArrayList<>();
        Map<Column, String> used = new EnumMap<>(Column.class);
        for (String written : columns) {
            if (SuiteColumns.ignored(written)) {
                continue;
            }
            Column column = SuiteColumns.find(written);
            if (column == null) {
                problems.add("This tool does not know the column \"" + written + "\". Its columns are: " + SuiteColumns.known()
                        + ". A column the tool should not read can stay if its name starts with " + SuiteColumns.IGNORED_PREFIX
                        + ". Nothing was run, because a misspelt column that is quietly dropped can change what every row means.");
            } else if (used.containsKey(column)) {
                problems.add("The columns \"" + used.get(column) + "\" and \"" + written + "\" both mean " + column.canonical()
                        + ". Keep one.");
            } else {
                used.put(column, written);
            }
        }
        if (problems.isEmpty() && !used.containsKey(Column.MEMBER_ID)) {
            problems.add("The file has no " + Column.MEMBER_ID.canonical() + " column. Every case needs a member ID.");
        }
        return problems;
    }

    private static SuiteRow row(SourceRow source, boolean csv, boolean mock, OutcomeCatalogue outcomes, Map<String, Integer> seen,
                                ProtocolCaseRunner runner) {
        if (!source.problems().isEmpty()) {
            // A row whose shape is wrong has no cells to check: what it lacks would be reported as missing when it was written.
            String caseId = "case-" + source.number();
            seen.putIfAbsent(caseId, source.number());
            return new SuiteRow(source.number(), caseId, null, false, false, null, null, source.problems());
        }
        List<String> problems = new ArrayList<>();
        Map<Column, String> cells = new EnumMap<>(Column.class);
        Map<Column, String> writtenAs = new EnumMap<>(Column.class);
        for (Map.Entry<String, String> cell : source.cells().entrySet()) {
            String written = cell.getKey();
            if (SuiteColumns.ignored(written)) {
                continue;
            }
            Column column = SuiteColumns.find(written);
            if (column == null) {
                // For a CSV file this was already reported once for the whole file.
                if (!csv) {
                    problems.add("This tool does not know the key \"" + written + "\". Its keys are: " + SuiteColumns.known()
                            + ". A key the tool should not read can stay if its name starts with " + SuiteColumns.IGNORED_PREFIX + ".");
                }
            } else if (cells.containsKey(column)) {
                problems.add("\"" + writtenAs.get(column) + "\" and \"" + written + "\" both mean " + column.canonical() + ". Keep one.");
            } else {
                cells.put(column, cell.getValue());
                writtenAs.put(column, written);
            }
        }

        String caseId = Texts.trimToNull(cells.get(Column.CASE_ID));
        if (caseId == null) {
            // Made from the position, so the same file gives the same ids on every run and two runs can be compared. Named
            // after the case and not the row, because a spreadsheet's row numbers count the header and any blank lines.
            caseId = "case-" + source.number();
        } else if (!CASE_ID.matcher(caseId).matches()) {
            problems.add("The case id may hold letters, digits, spaces and . _ : / # ( ) - and at most 64 characters.");
            caseId = "case-" + source.number();
        }
        Integer first = seen.putIfAbsent(caseId.toLowerCase(Locale.ROOT), source.number());
        if (first != null) {
            // Not quoted: a case id can hold member data that a later check refuses, and this problem
            // stays in the results a tester downloads. The row number finds it.
            problems.add("This case id is already used by case " + first + " of the file. Each case needs its own id, "
                    + "because results are matched to cases by it.");
        }

        Target target = target(cells, problems);

        if (Texts.blank(cells.get(Column.MEMBER_ID))) {
            problems.add("The row has no member ID.");
        }
        String npi = Texts.trimToNull(cells.get(Column.NPI));
        if (npi != null && !Npi.isValid(npi)) {
            problems.add("The NPI is not valid: it must be ten digits and its check digit must match. Check for a typing mistake, "
                    + "and that a spreadsheet has not dropped a digit.");
        }
        String date = Texts.trimToNull(cells.get(Column.DATE_OF_SERVICE));
        if (date != null && !IsoDates.valid(date)) {
            problems.add("The date of service must be written as YYYY-MM-DD, for example 2026-01-31. A spreadsheet may have rewritten it.");
        }
        String scenario = Texts.trimToNull(cells.get(Column.SCENARIO_ID));
        if (scenario != null && !mock) {
            problems.add("The row chooses a scenario, which is possible on the mock profile only. On live the answer comes from the service.");
        }
        String expected = Texts.trimToNull(cells.get(Column.EXPECTED));
        // An expectation over the limit is reported by its length alone, below. Its terms are not
        // read, because a problem with a term quotes the term, and such a cell is most likely
        // something pasted in that can name a member.
        String readable = expected != null && expected.length() > RunRecord.LONGEST_EXPECTED ? null : expected;
        // Where the expectation's terms are reported from, so that they can be taken out again if
        // the expectation turns out to hold member data: a problem with a term quotes the term.
        int termsFrom = problems.size();
        if (readable != null && target.chain()) {
            problems.addAll(ChainExpectation.parse(readable, outcomes).problems());
        } else if (readable != null && target.operation() != null) {
            problems.addAll(outcomes.expectationProblems(target.operation().protocol(), readable));
        } else if (readable != null && !readsForAnyProtocol(readable, outcomes)) {
            // The row does not say what it runs, so its expectation cannot be checked against one protocol. Said now, so that
            // fixing the protocol does not reveal a second problem.
            problems.add("The expectation is not an outcome or a code assertion of crd, dtr or pas, so once the row says what it runs "
                    + "the expectation will be refused too. Check its spelling.");
        }
        // The same limits a run form is held to, so a row is refused here, with its number, and not
        // found too long by each service in turn. The cell is never quoted: it can name a member.
        Map<String, String> text = RunInputs.textForTheLedger(Texts.trimToNull(cells.get(Column.NOTE)),
                Texts.trimToNull(cells.get(Column.TESTER_REF)), expected, !target.chain());
        text.values().forEach(problems::add);
        // The row's own member ID in its note, tester reference or expectation would undo the token
        // the line keeps in its place. The row carries no name or date of birth to look for; the
        // service looks for those once the member is looked up, before the case is sent.
        String rowMember = Texts.trimToNull(cells.get(Column.MEMBER_ID));
        // What the results of the suite repeat as the row's expectation. One that was refused for
        // its length or for holding the member ID is not repeated: the refusal is written in its
        // place, so the results file a tester downloads does not carry what the ledger may not.
        String shownExpected = text.containsKey("expected") && expected != null && expected.length() > RunRecord.LONGEST_EXPECTED
                ? text.get("expected") : expected;
        int termsTo = problems.size() - text.size();
        boolean termsCleared = false;
        java.util.Set<String> refusedForTheRowsId = new java.util.HashSet<>();
        if (rowMember != null) {
            Map<String, String> member = MemberInText.problems(text.containsKey("note") ? null : Texts.trimToNull(cells.get(Column.NOTE)),
                    text.containsKey("testerRef") ? null : Texts.trimToNull(cells.get(Column.TESTER_REF)),
                    text.containsKey("expected") ? null : expected, MemberDetail.builder().memberId(rowMember).build(),
                    "The row was not run.");
            member.values().forEach(problems::add);
            Map<String, String> inCaseId = MemberInText.problemsInCaseId(caseId, MemberDetail.builder().memberId(rowMember).build(),
                    runner == null ? null : runner.ownCaseIds(), "Nothing was sent.");
            // In the words of the case id's refusal for the looked-up member's name, field first, so the
            // tester reads one refusal for the case id whichever member data it holds.
            inCaseId.forEach((field, problem) -> problems.add(field + ": " + problem));
            refusedForTheRowsId.addAll(member.keySet());
            refusedForTheRowsId.addAll(inCaseId.keySet());
            if (member.containsKey("expected")) {
                shownExpected = member.get("expected");
                // The refusal says what is wrong with the expectation, and nothing else quotes it.
                problems.subList(termsFrom, termsTo).clear();
                termsCleared = true;
            }
        }
        Map<String, String> correlation = new LinkedHashMap<>();
        correlation(cells, Column.CLAIM_ID, RunRecord.CORR_CLAIM_ID, correlation, problems);
        correlation(cells, Column.SUBSCRIPTION_ID, RunRecord.CORR_SUBSCRIPTION_ID, correlation, problems);
        unusedCorrelation(target, cells, problems);

        List<String> codes = Texts.blank(cells.get(Column.PROCEDURE_CODES)) ? List.of()
                : Arrays.stream(CODE_SEPARATOR.split(cells.get(Column.PROCEDURE_CODES).strip())).filter(Texts::present).toList();
        if (codes.stream().anyMatch(code -> !PROCEDURE_CODE.matcher(code).matches())) {
            // Never quoted: a word between two codes would be sent as a code, so the cell is refused as a whole.
            problems.add("The procedureCodes cell holds something that is not a procedure code, such as a word or a sign between two "
                    + "codes. Separate codes with a comma, a semicolon, a bar or a space, and nothing else.");
        }

        CaseSpec spec = null;
        if (problems.isEmpty()) {
            spec = CaseSpec.builder()
                    .caseId(caseId)
                    .chain(target.chain())
                    .operation(target.operation())
                    .memberId(cells.get(Column.MEMBER_ID).strip())
                    .npi(npi)
                    .procedureCodes(codes)
                    .dateOfService(date)
                    .scenarioId(scenario)
                    .expected(expected)
                    .testerRef(Texts.trimToNull(cells.get(Column.TESTER_REF)))
                    .note(Texts.trimToNull(cells.get(Column.NOTE)))
                    .correlation(correlation)
                    .build();
        }
        String cannotRun = null;
        if (spec != null && runner != null) {
            cannotRun = runner.cannotRun(spec);
            if (cannotRun == null) {
                problems.addAll(runner.refusals(spec));
            }
        }
        if (runner != null && rowMember != null && (spec == null || cannotRun != null)) {
            // The row is not sent, for its other problems or because this build cannot run it, and the
            // check made once the member is looked up is made all the same: the preview and the run list
            // every problem of the row at once, and the results keep its case id and expectation either way.
            // A text already refused, for its length or for the row's own member ID, is not checked again.
            // An expectation is checked as the first call would be sent with it: a chain's CRD part when
            // the chain's expectation can be read, and none of it when it cannot, since a term of another
            // step then cannot be told from a word of the CRD step's.
            String forTheCheck = text.containsKey("expected") || refusedForTheRowsId.contains("expected") ? null
                    : !target.chain() ? expected
                    : readable != null && ChainExpectation.parse(readable, outcomes).problems().isEmpty()
                    ? ChainExpectation.parse(readable, outcomes).of(Protocol.CRD) : null;
            CaseSpec probe = CaseSpec.builder()
                    .caseId(refusedForTheRowsId.contains(MemberInText.CASE_ID) ? null : caseId)
                    .chain(target.chain())
                    .operation(target.operation())
                    .memberId(rowMember)
                    .testerRef(text.containsKey("testerRef") || refusedForTheRowsId.contains("testerRef") ? null
                            : Texts.trimToNull(cells.get(Column.TESTER_REF)))
                    .note(text.containsKey("note") || refusedForTheRowsId.contains("note") ? null : Texts.trimToNull(cells.get(Column.NOTE)))
                    .build();
            List<String> found = runner.memberProblems(probe, forTheCheck);
            if (!termsCleared && found.stream().anyMatch(problem -> problem.startsWith("expected: "))) {
                // The refusal says what is wrong with the expectation, and no problem with one of its terms quotes it.
                problems.subList(termsFrom, termsTo).clear();
            }
            if (cannotRun != null && !found.isEmpty()) {
                // Reported as not valid now, and the reason it could not have run is kept beside the refusal.
                problems.add(cannotRun);
            }
            problems.addAll(found);
        }
        if (problems.stream().anyMatch(MemberInText::refusesCaseId)) {
            // A case id that holds member data is not repeated on screen or in the results, which a
            // tester downloads: the row is named by its position, in a name no case id of the file can have.
            caseId = withheldCaseId(source.number());
        }
        // The service's own check, made once the member is looked up, can refuse the expectation for
        // a name or a date of birth, and says so in the words MemberInText gives it.
        for (String problem : problems) {
            String refusal = expectationRefusal(problem);
            if (refusal != null && expectationRefusal(shownExpected) == null) {
                shownExpected = refusal;
            }
        }
        return new SuiteRow(source.number(), caseId, target.what(), target.chain(), target.write(), shownExpected,
                problems.isEmpty() ? spec : null, problems, problems.isEmpty() ? cannotRun : null);
    }

    /**
     * The name a row is shown by when its case id was refused for holding member data. It holds
     * square brackets, which a case id may not ({@link #CASE_ID}), so it can never be the id of
     * another row of the file, or of a case of an earlier run that results are compared with.
     */
    public static String withheldCaseId(int number) {
        return "case-" + number + " [id not shown]";
    }

    /**
     * The refusal of an expectation for member data, wherever it stands in a problem or a reason,
     * or null when there is none. A service names the field before it ("expected: "), a row check
     * does not, and either may be one of several problems joined into one reason. The refusal
     * names the kind of value it found and never the value.
     */
    public static String expectationRefusal(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher refusal = EXPECTATION_REFUSAL.matcher(text);
        return refusal.find() ? refusal.group() : null;
    }

    private static final java.util.regex.Pattern EXPECTATION_REFUSAL = java.util.regex.Pattern.compile(
            "The expectation holds [^.]{1,200}\\. The ledger keeps it as written and may not hold member data, so leave (?:it|them) out\\.");

    /** True when an expectation is well formed for at least one protocol a row can run. */
    private static boolean readsForAnyProtocol(String expected, OutcomeCatalogue outcomes) {
        for (Protocol protocol : List.of(Protocol.CRD, Protocol.DTR, Protocol.PAS)) {
            if (outcomes.expectationProblems(protocol, expected).isEmpty()) {
                return true;
            }
        }
        return ChainExpectation.parse(expected, outcomes).problems().isEmpty();
    }

    /**
     * A correlation column the row's operation does not use is refused. Dropped in silence, a
     * claim id typed on a submission would leave the tester believing it was sent; a submission
     * run from a suite is a new request with a new claim id.
     */
    private static void unusedCorrelation(Target target, Map<Column, String> cells, List<String> problems) {
        if (Texts.present(cells.get(Column.CLAIM_ID)) && (target.chain() || target.operation() != null
                && target.operation() != EpaOperation.PAS_INQUIRE)) {
            problems.add("The claimId column is used by a PAS inquiry only. This row runs " + (target.chain() ? "a chain" : target.what())
                    + ", which would not send it: a submission run from a suite always gets a new claim id. Leave the cell empty.");
        }
        if (Texts.present(cells.get(Column.SUBSCRIPTION_ID))) {
            problems.add("The subscriptionId column is not used by any row a suite can run. Leave the cell empty.");
        }
    }

    private static void correlation(Map<Column, String> cells, Column column, String key, Map<String, String> into, List<String> problems) {
        String value = Texts.trimToNull(cells.get(column));
        if (value == null) {
            return;
        }
        if (!CORRELATION_ID.matcher(value).matches()) {
            problems.add("The " + column.canonical() + " may hold letters, digits and - . _ : / | and at most 128 characters.");
        } else {
            into.put(key, value);
        }
    }

    /** What a row runs. The operation is null when the row does not say, or for a chain that names no hook. */
    private record Target(boolean chain, EpaOperation operation, String what, boolean write) {
    }

    private static Target target(Map<Column, String> cells, List<String> problems) {
        String protocolText = Texts.trimToNull(cells.get(Column.PROTOCOL));
        String hookText = Texts.trimToNull(cells.get(Column.HOOK));
        String operationText = Texts.trimToNull(cells.get(Column.OPERATION));
        String chainText = Texts.trimToNull(cells.get(Column.CHAIN));
        int before = problems.size();

        boolean chain = false;
        if (chainText != null) {
            Boolean flag = yesNo(chainText);
            if (flag == null) {
                problems.add("\"chain\" must be yes or no.");
            } else {
                chain = flag;
            }
        }
        EpaOperation hook = null;
        if (hookText != null) {
            hook = switch (SuiteColumns.normalise(hookText)) {
                case "ordersign" -> EpaOperation.CRD_ORDER_SIGN;
                case "orderselect" -> EpaOperation.CRD_ORDER_SELECT;
                default -> null;
            };
            if (hook == null) {
                problems.add("The hook must be order-sign or order-select.");
            }
        }
        EpaOperation named = null;
        if (operationText != null) {
            try {
                named = EpaOperation.parse(operationText);
            } catch (IllegalArgumentException e) {
                problems.add("The operation is not one this tool knows. " + e.getMessage());
            }
        }
        EpaOperation fromProtocol = null;
        boolean protocolIsCrd = false;
        if (protocolText != null) {
            switch (protocolText.toLowerCase(Locale.ROOT)) {
                case "crd" -> protocolIsCrd = true;
                case "dtr" -> fromProtocol = EpaOperation.DTR_QUESTIONNAIRE_PACKAGE;
                case "pas" -> fromProtocol = EpaOperation.PAS_SUBMIT;
                case "chain" -> chain = true;
                default -> problems.add("The protocol must be crd, dtr, pas or chain.");
            }
        }
        if (problems.size() > before) {
            return new Target(chain, null, null, false);
        }

        if (chain) {
            EpaOperation start = named != null ? named : hook;
            if (named != null && hook != null && named != hook) {
                problems.add("The hook and the operation of this row disagree.");
            } else if (start != null && start != EpaOperation.CRD_ORDER_SIGN && start != EpaOperation.CRD_ORDER_SELECT) {
                problems.add("A chain starts with a CRD hook, order-sign or order-select. This row names " + start.label() + ".");
            } else if (fromProtocol != null) {
                problems.add("The row is marked as a chain and its protocol names a single step. Set the protocol to chain, or remove the chain mark.");
            }
            EpaOperation first = start == null ? EpaOperation.CRD_ORDER_SIGN : start;
            // A chain can end in a PAS submission, so it counts as a write.
            return new Target(true, start, "Chain, starting with " + first.label(), true);
        }

        EpaOperation operation = named;
        if (protocolIsCrd || (protocolText == null && named == null && hook != null)) {
            fromProtocol = hook == null ? EpaOperation.CRD_ORDER_SIGN : hook;
        }
        if (operation == null) {
            operation = fromProtocol;
        } else if (fromProtocol != null && fromProtocol.protocol() != operation.protocol()
                || protocolIsCrd && operation.protocol() != Protocol.CRD) {
            problems.add("The protocol and the operation of this row disagree.");
        }
        if (operation == null) {
            problems.add("The row does not say what to run. Give a protocol (crd, dtr, pas or chain), or an operation.");
            return new Target(false, null, null, false);
        }
        if (hook != null && operation != hook) {
            problems.add(operation.protocol() == Protocol.CRD ? "The hook and the operation of this row disagree."
                    : "A hook applies to a CRD row or a chain. This row runs " + operation.label() + ".");
        }
        if (!operation.outbound()) {
            problems.add(operation.label() + " is not something a suite can run: it records something that arrived, or a step "
                    + "completed inside this tool, so there is nothing to send.");
        }
        return new Target(false, operation, operation.label(), operation.write());
    }

    private static Boolean yesNo(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1" -> Boolean.TRUE;
            case "no", "n", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
