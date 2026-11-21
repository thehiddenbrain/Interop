package org.p32h.interop.epa.pave.service.suite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.suite.SuiteCaseResult;
import org.p32h.interop.epa.pave.model.suite.SuiteCountGroup;
import org.p32h.interop.epa.pave.model.suite.SuiteCounts;
import org.p32h.interop.epa.pave.model.suite.SuitePreview;
import org.p32h.interop.epa.pave.model.suite.SuiteRowState;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;
import org.p32h.interop.epa.pave.model.suite.SuiteState;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.cases.ChainCaseStep;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.cases.ProtocolCaseRunner;
import org.p32h.interop.epa.pave.service.chain.ChainService;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.BusyException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.UncaughtFailures;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Runs a suite file: each row a case, one after another, against the configuration in force when
 * the run started.
 *
 * <p>Every case goes through {@link CaseRunner}, the path a manual run takes, so each writes its
 * own ledger record and each is held to the production guardrail. A suite never carries the unlock
 * for production writes: against a production environment its reads run and each of its writes is
 * reported as blocked, which is not a failure.
 *
 * <p>The run is pinned to the configuration it started under. If the configuration changes while
 * it runs, the run stops and says so, so that every case of one results table ran under one
 * configuration. What the change was is not known here, so the reason does not guess at it.
 *
 * <p>What is held in memory, and for how long:
 * <ul>
 *   <li>a case holds its member ID until the case has run, or the run stops. Then it is dropped</li>
 *   <li>a result row never holds a member ID. It points to the ledger by run id</li>
 *   <li>the last few finished runs are kept until the tool restarts. The ledger is where results last</li>
 * </ul>
 * Nothing here logs a case.
 */
@Service
public class SuiteService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SuiteService.class);

    /** Finished runs kept in memory. */
    static final int KEPT_RUNS = 10;
    /** Row problems listed in a preview. The count is always the true count. */
    static final int LISTED_ROW_PROBLEMS = 100;

    static final String STOPPED_BY_TESTER = "Stopped on request.";
    static final String STOPPED_BY_SHUTDOWN = "The tool was shut down while the suite was running.";

    private final ObjectProvider<CaseRunner> caseRunner;
    private final ObjectProvider<RunLedger> ledger;
    private final OperationGateway gateway;
    private final EpaProperties epa;
    private final OutcomeCatalogue outcomes;
    private final SuiteFileReader reader;
    private final RunSupport support;
    private final Executor executor;
    private final ExecutorService owned;

    private final Object lock = new Object();
    private final LinkedHashMap<String, Run> runs = new LinkedHashMap<>();

    @Autowired
    public SuiteService(ObjectProvider<CaseRunner> caseRunner, ObjectProvider<RunLedger> ledger, OperationGateway gateway,
                        EpaProperties epa, OutcomeCatalogue outcomes, SuiteFileReader reader, RunSupport support) {
        this(caseRunner, ledger, gateway, epa, outcomes, reader, support, null);
    }

    /** @param executor runs a suite. Null for the service's own single background thread */
    SuiteService(ObjectProvider<CaseRunner> caseRunner, ObjectProvider<RunLedger> ledger, OperationGateway gateway,
                 EpaProperties epa, OutcomeCatalogue outcomes, SuiteFileReader reader, RunSupport support, Executor executor) {
        this.caseRunner = caseRunner;
        this.ledger = ledger;
        this.gateway = gateway;
        this.epa = epa;
        this.outcomes = outcomes;
        this.reader = reader;
        this.support = support;
        if (executor == null) {
            // One thread, so cases run strictly one after another. A daemon, so it never keeps the tool from closing.
            this.owned = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "suite-runner");
                thread.setDaemon(true);
                // The JVM's default handler prints messages, which can quote a member ID. Set here as
                // well as for the process, so the thread is covered however the service was built.
                thread.setUncaughtExceptionHandler(UncaughtFailures.HANDLER);
                return thread;
            });
            this.executor = owned;
        } else {
            this.owned = null;
            this.executor = executor;
        }
    }

    /**
     * What the tool makes of a suite file. Nothing is sent and nothing is kept. A row is counted
     * as one that can be run only when the runner has a step for it and nothing its services
     * would refuse before sending is found in it, so the count is what will really be run.
     */
    public SuitePreview preview(String fileName, String text) {
        SuiteDefinition definition = define(fileName, text);
        EnvironmentRef environment = gateway.activeEnvironment();
        List<SuiteRow> rows = definition.rows();
        List<SuiteRow> valid = rows.stream().filter(SuiteRow::runnable).toList();
        List<SuiteRow> problemRows = rows.stream().filter(r -> !r.problems().isEmpty()).toList();
        List<SuiteRow> cannotRun = rows.stream().filter(r -> r.problems().isEmpty() && r.cannotRun() != null).toList();
        List<SuitePreview.RowProblem> rowProblems = problemRows.stream().limit(LISTED_ROW_PROBLEMS)
                .map(r -> new SuitePreview.RowProblem(r.number(), r.caseId(), r.problems())).toList();
        List<SuitePreview.RowProblem> notRunnable = cannotRun.stream().limit(LISTED_ROW_PROBLEMS)
                .map(r -> new SuitePreview.RowProblem(r.number(), r.caseId(), List.of(r.cannotRun()))).toList();
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (SuiteRow row : valid) {
            kinds.merge(row.what(), 1, Integer::sum);
        }
        List<SuiteRow> writes = valid.stream().filter(SuiteRow::write).toList();
        String headline;
        if (!definition.problems().isEmpty()) {
            headline = "This file cannot be run. Nothing was sent.";
        } else if (valid.isEmpty()) {
            headline = "No case in this file can be run. Nothing was sent.";
        } else if (valid.size() == rows.size()) {
            headline = cases(rows.size()) + ", and every one can be run.";
        } else {
            List<String> parts = new ArrayList<>();
            parts.add(valid.size() + " can be run");
            if (!cannotRun.isEmpty()) {
                parts.add(cannotRun.size() + " cannot be run in this build and will be reported as not runnable");
            }
            if (!problemRows.isEmpty()) {
                parts.add(problemRows.size() + (problemRows.size() == 1 ? " has a problem" : " have a problem")
                        + " and will be reported as not valid");
            }
            headline = cases(rows.size()) + ": " + (parts.size() == 2 ? parts.get(0) + ", and " + parts.get(1)
                    : String.join(", ", parts.subList(0, parts.size() - 1)) + ", and " + parts.get(parts.size() - 1)) + ".";
        }
        if (!valid.isEmpty() && definition.problems().isEmpty() && !epa.mock()) {
            // The preview sends nothing, so it cannot know whether the active configuration has a target for each operation.
            headline += " Whether the active configuration has a target for each is found when the case is sent: one with none is "
                    + "reported as not configured.";
        }
        return new SuitePreview(definition.name(), definition.format(), definition.runnable(), headline, rows.size(), valid.size(),
                definition.problems(), rowProblems, problemRows.size(),
                kinds.entrySet().stream().map(e -> new SuitePreview.Kind(e.getKey(), e.getValue())).toList(),
                writes.size(), writeNote(writes, environment), (int) valid.stream().filter(r -> r.expected() != null).count(),
                definition.columns(), epa.mode(), epa.target(), environment, notRunnable, cannotRun.size(), definition.skipped());
    }

    /** What will happen to the rows that can create or change something, which are only rows that will run. */
    private String writeNote(List<SuiteRow> writes, EnvironmentRef environment) {
        if (writes.isEmpty()) {
            return null;
        }
        int count = writes.size();
        String subject = count == 1 ? "1 case can create or change something on the far side"
                : count + " cases can create or change something on the far side";
        // A suite carries no unlock, so this is the answer for every write in it.
        WriteAccess access = gateway.writeAccess(null);
        if (!access.writesAllowed()) {
            if (writes.stream().noneMatch(SuiteRow::chain)) {
                return subject + ". " + access.reason() + " A suite never unlocks production writes, so "
                        + (count == 1 ? "its submission" : "the submission of each of them") + " will be blocked and reported as blocked, "
                        + "not as failed, and will not be sent.";
            }
            // A chain reaches a submission only when its earlier steps call for one, so not every chain row ends blocked.
            return subject + ". " + access.reason() + " A suite never unlocks production writes, so a submission "
                    + (count == 1 ? "it reaches" : "any of them reaches") + " will be blocked and reported as blocked, not as failed, "
                    + "and will not be sent. A chain sends its CRD step, and its DTR step when CRD calls for one, before it reaches a "
                    + "submission: those are reads. A chain that ends before a submission is reported by what its steps gave, not as blocked.";
        }
        if (epa.mock()) {
            return subject + ". On the mock profile nothing leaves this machine.";
        }
        return subject + (environment.active() ? " in " + environment.name() + "." : ".");
    }

    /**
     * Starts a suite run and returns at once. The cases run in the background, one after another.
     *
     * @param testerRef   a reference recorded on every case that gives none of its own, or null
     * @param compareWith the id of an earlier suite run to compare each case with, or null
     * @param configEpoch the configuration epoch the screen was drawn under, or null to not check
     */
    public SuiteRunView start(String fileName, String text, String testerRef, String compareWith, Long configEpoch) {
        CaseRunner runner = caseRunner.getIfAvailable();
        if (runner == null) {
            throw new BadInputException("Suites cannot be run here: this copy of the tool was started without what runs a case. Nothing was sent.", "Run the cases one at a time from their tabs.");
        }
        // Recorded on every case that gives none of its own, so it is held to the limit a run's is.
        String refProblem = RunInputs.textForTheLedger(null, testerRef, null).get("testerRef");
        if (refProblem != null) {
            throw new ValidationException("The suite was not started. Nothing was sent.", List.of(new FieldError("testerRef", refProblem)));
        }
        SuiteDefinition definition = define(fileName, text);
        if (!definition.problems().isEmpty()) {
            throw new ValidationException("The suite file cannot be run. Nothing was sent.",
                    definition.problems().stream().map(p -> new FieldError("file", p)).toList());
        }
        if (!definition.runnable()) {
            throw new ValidationException("No case in this file can be run. Nothing was sent.",
                    definition.rows().stream().limit(20).map(r -> new FieldError("file",
                            "Case " + r.number() + ": " + String.join(" ", r.problems()))).toList());
        }
        String earlier = Texts.trimToNull(compareWith);
        Map<String, Previous> previous = earlier == null ? null : previous(earlier);
        Run run;
        synchronized (lock) {
            for (Run other : runs.values()) {
                if (other.state == SuiteState.RUNNING) {
                    throw new BusyException("A suite is already running: " + other.name + ", " + other.done() + " of "
                            + other.entries.size() + " cases done. This tool runs one suite at a time.",
                            "Wait for it to finish, or stop it, then start this one.");
                }
            }
            long epoch = gateway.epoch();
            if (configEpoch != null && configEpoch != epoch) {
                throw ConfigurationChangedException.sinceTheScreenWasDrawn();
            }
            run = new Run(RunStamps.newSuiteRunId(), definition, RunStamps.timestamp(support.clock()), gateway.activeEnvironment(), epoch,
                    Texts.trimToNull(testerRef), earlier, previous);
            runs.put(run.id, run);
            trim();
        }
        try {
            executor.execute(() -> runAll(run, runner));
        } catch (RuntimeException refused) {
            // The executor is closed, which happens only while the tool shuts down.
            run.finish(STOPPED_BY_SHUTDOWN, RunStamps.timestamp(support.clock()));
        }
        return run.view(true);
    }

    /** A suite run held in memory, with its rows. */
    public SuiteRunView get(String suiteRunId, boolean withRows) {
        return find(suiteRunId).view(withRows);
    }

    /** The suite runs held in memory, newest first, without their rows. */
    public List<SuiteRunView> list() {
        List<Run> held;
        synchronized (lock) {
            held = new ArrayList<>(runs.values());
        }
        List<SuiteRunView> views = new ArrayList<>();
        for (int i = held.size() - 1; i >= 0; i--) {
            views.add(held.get(i).view(false));
        }
        return views;
    }

    /** Asks a running suite to stop after the case it is on. A run that has ended is returned as it is. */
    public SuiteRunView stop(String suiteRunId) {
        Run run = find(suiteRunId);
        run.requestStop(STOPPED_BY_TESTER);
        return run.view(true);
    }

    /** The results table of a suite run as CSV. */
    public String resultsCsv(String suiteRunId) {
        return SuiteResultsCsv.of(find(suiteRunId).view(true));
    }

    /**
     * Stops whatever is running. Called when the tool shuts down. Never throws: Spring logs what a
     * destroy method throws with its message, and a message can quote a member ID.
     */
    @Override
    public void close() {
        try {
            synchronized (lock) {
                for (Run run : runs.values()) {
                    run.requestStop(STOPPED_BY_SHUTDOWN);
                }
            }
            if (owned != null) {
                owned.shutdownNow();
            }
        } catch (RuntimeException | Error e) {
            // A reference and the exception types and frames only, as every failure of this tool.
            log.error("Stopping the suites failed inside this tool, reference {}: {}", UUID.randomUUID(), Faults.describe(e));
        }
    }

    private Run find(String suiteRunId) {
        Run run;
        synchronized (lock) {
            run = runs.get(Texts.trimToNull(suiteRunId) == null ? "" : suiteRunId.strip());
        }
        if (run == null) {
            throw new NotFoundException("This tool holds no suite run with that id. It keeps the last " + KEPT_RUNS
                    + " suite runs until it restarts. Every case a suite ran is still in the ledger, on the History tab, under its "
                    + "suite run id.");
        }
        return run;
    }

    /** Drops the oldest finished runs beyond the number kept. A running one is never dropped. Called under the lock. */
    private void trim() {
        var iterator = runs.values().iterator();
        int excess = runs.size() - KEPT_RUNS;
        while (excess > 0 && iterator.hasNext()) {
            if (iterator.next().state != SuiteState.RUNNING) {
                iterator.remove();
                excess--;
            }
        }
    }

    private SuiteDefinition define(String fileName, String text) {
        CaseRunner runner = caseRunner.getIfAvailable();
        return SuiteCases.define(displayName(fileName), reader.read(fileName, text), epa.mock(), outcomes,
                runner instanceof ProtocolCaseRunner checks ? checks : null);
    }

    /** The file name without any directory, cut to a length a screen can show. */
    static String displayName(String fileName) {
        String name = Texts.trimToNull(fileName);
        if (name == null) {
            return "suite";
        }
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).replaceAll("\\p{Cntrl}", " ").strip();
        if (name.isEmpty()) {
            return "suite";
        }
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    /** What an earlier suite run made of each case, read from the ledger, by case id. */
    private Map<String, Previous> previous(String suiteRunId) {
        RunLedger store = ledger.getIfAvailable();
        if (store == null) {
            throw new BadInputException("There is no ledger to read the earlier suite run from.", "Start the suite without a comparison.");
        }
        Map<String, Previous> byCase = new LinkedHashMap<>();
        // Newest first, so the first record seen for a case is its last step: the one that states its result.
        for (RunRecord record : store.query(RunQuery.forSuite(suiteRunId))) {
            // Only a run line is a step of a case. The ledger applies a case line to its run and does not return it.
            if (Texts.present(record.caseId()) && RunRecord.TYPE_RUN.equals(record.recordType())) {
                byCase.putIfAbsent(record.caseId().toLowerCase(Locale.ROOT),
                        new Previous(record.caseId(), record.protocol(), record.outcome(), record.result(), record.caseResult()));
            }
        }
        if (byCase.isEmpty()) {
            throw new NotFoundException("The ledger holds no run of a suite with that id, so there is nothing to compare with.");
        }
        return byCase;
    }

    private void runAll(Run run, CaseRunner runner) {
        String ended = null;
        try {
            for (Entry entry : run.entries) {
                CaseSpec spec;
                synchronized (run) {
                    if (run.stopRequested != null) {
                        ended = run.stopRequested;
                        break;
                    }
                    if (entry.spec == null) {
                        entry.result = notValid(run, entry);
                        entry.state = SuiteRowState.DONE;
                        continue;
                    }
                    if (gateway.epoch() != run.epoch) {
                        ended = configurationChanged();
                        break;
                    }
                    entry.state = SuiteRowState.RUNNING;
                    spec = entry.spec.toBuilder().suiteRunId(run.id).configEpoch(run.epoch)
                            .testerRef(Texts.firstPresent(entry.spec.testerRef(), run.testerRef)).build();
                }
                CaseOutcome outcome;
                try {
                    outcome = runner.run(spec);
                } catch (RuntimeException e) {
                    // The runner is specified never to throw. If it does, the suite still gets a row for the case.
                    String reference = UUID.randomUUID().toString();
                    // The exception types and frames only. Never its message, and never the case, which holds a member ID.
                    log.error("A suite case failed inside this tool, reference {}: {}", reference, Faults.describe(e));
                    outcome = toolFailure(reference, Faults.cause(e));
                }
                synchronized (run) {
                    // The member ID has done its work. From here the row points to the ledger by run id.
                    entry.spec = null;
                    for (RunRecord record : outcome.runs()) {
                        run.timings.add(record);
                    }
                    boolean moved = gateway.epoch() != run.epoch;
                    entry.result = result(run, entry, outcome, moved);
                    entry.state = SuiteRowState.DONE;
                    if (outcome.status() == CaseStatus.NOT_CONFIGURED && moved) {
                        // The configuration moved between the check above and a call of this case. The row says what was sent.
                        ended = configurationChanged();
                        break;
                    }
                }
            }
        } catch (RuntimeException | Error e) {
            String reference = UUID.randomUUID().toString();
            // A reference and the exception types and frames only. Never a message, and never a case, which holds a member ID.
            log.error("A suite run failed inside this tool, reference {}: {}", reference, Faults.describe(e));
            ended = "This tool failed while running the suite. Reference " + reference + ".";
            // The cause goes on the screen beside the reference: a missing class ends a suite here
            // and nowhere else, and the server log may be on a machine the tester does not see.
            String cause = Faults.cause(e);
            run.fault(reference, cause);
            synchronized (run) {
                for (Entry entry : run.entries) {
                    if (entry.state == SuiteRowState.RUNNING) {
                        // Its request may have left before the failure, so it is never called not run.
                        // It is the same failure as the stop, under the same reference.
                        entry.spec = null;
                        entry.result = result(run, entry, toolFailure(reference, cause), false);
                        entry.state = SuiteRowState.DONE;
                    }
                }
            }
            // Not thrown on: nothing above the run catches it, so the JVM's default handler would
            // print it again with every message, and a message can quote the member ID of the case.
        } finally {
            run.finish(ended, RunStamps.timestamp(support.clock()));
        }
    }

    /** How a case ends when this tool failed while running it: whether a request left for it is not known. */
    private static CaseOutcome toolFailure(String reference, String cause) {
        return new CaseOutcome(null, CaseStatus.NOT_RUNNABLE, "This tool failed while running the case. Whether a request "
                + "left for it is not known, and nothing can be said about the service from this row. Look for it in History "
                + "before running it again. Reference " + reference + ".", List.of(), List.of(), List.of(), null, reference, cause);
    }

    static String configurationChanged() {
        return "The configuration was changed while the suite was running. The suite stopped so that every case of it ran under one "
                + "configuration, and the cases left were not run. Start the suite again to run them under the configuration now in force.";
    }

    private SuiteCaseResult notValid(Run run, Entry entry) {
        Previous earlier = run.previous == null ? null : run.previous.get(entry.caseId.toLowerCase(Locale.ROOT));
        CaseStatus status = entry.cannotRun != null ? CaseStatus.NOT_RUNNABLE : CaseStatus.INVALID;
        return new SuiteCaseResult(entry.number, entry.caseId, entry.what, entry.chain, SuiteRowState.DONE, status,
                statusLine(status, null, entry.expected), entry.expected, null, null, null, null, null, false, null,
                List.of(), null, null, List.of(), null, entry.cannotRun != null ? entry.cannotRun : String.join(" ", entry.problems),
                List.of(), List.of(),
                earlier == null ? null : earlier.outcome(),
                earlier == null ? null : outcomes.label(earlier.protocol(), earlier.outcome()),
                earlier == null ? null : earlier.result(),
                earlier == null ? null : Boolean.TRUE, List.of(), null, null, null);
    }

    private SuiteCaseResult result(Run run, Entry entry, CaseOutcome outcome, boolean configurationMoved) {
        CaseStatus status = outcome.status();
        RunRecord last = outcome.finalRun();
        boolean completed = status == CaseStatus.COMPLETED && last != null;
        String code = last == null ? null : last.outcome();
        Protocol protocol = last == null ? null : last.protocol();
        // For a chain this is the result of the whole journey, which no single record can state.
        TestResult result = completed ? outcome.result() : null;
        Long elapsed = null;
        boolean slow = false;
        Set<String> findings = new LinkedHashSet<>();
        List<String> runIds = new ArrayList<>();
        List<SuiteCaseResult.Step> steps = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        for (RunRecord record : outcome.runs()) {
            runIds.add(record.runId());
            findings.addAll(record.findings());
            slow = slow || Boolean.TRUE.equals(record.slow());
            boolean wentOut = !RunOutcome.BLOCKED.name().equals(record.outcome());
            if (wentOut && record.elapsedMillis() != null) {
                elapsed = (elapsed == null ? 0 : elapsed) + record.elapsedMillis();
            }
            if (wentOut) {
                sent.add(label(record));
            }
            if (entry.chain) {
                steps.add(new SuiteCaseResult.Step(label(record), record.outcome(), outcomes.label(record.protocol(), record.outcome()),
                        record.result(), record.runId()));
            }
        }
        Tone tone = last == null ? null : outcomes.tone(protocol, code);
        OutcomeCode found = last == null ? null : outcomes.find(protocol, code);
        Previous earlier = run.previous == null ? null : run.previous.get(entry.caseId.toLowerCase(Locale.ROOT));
        // A chain is compared case result with case result. An earlier chain line from before the
        // ledger kept a case result has none, and its last step's result is not put in its place.
        TestResult earlierResult = earlier == null ? null : entry.chain && earlier.caseResult() == null ? null
                : earlier.caseResult() != null ? earlier.caseResult() : earlier.result();
        Boolean changed = earlier == null ? null : !Objects.equals(earlier.outcome(), code) ? Boolean.TRUE
                : entry.chain && earlierResult == null ? null : !Objects.equals(earlierResult, result);
        // A service that refused the expectation for member data it found once the member was looked
        // up, a date of birth or a name, says so in the reason. The results then repeat that refusal
        // and not the expectation.
        String refusal = status == CaseStatus.INVALID ? SuiteCases.expectationRefusal(outcome.reason()) : null;
        String shownExpected = refusal != null ? refusal : entry.expected;
        return new SuiteCaseResult(entry.number, entry.caseId, entry.what, entry.chain, SuiteRowState.DONE, status,
                rowLine(outcome, result, entry, sent, configurationMoved), shownExpected, code, outcomes.label(protocol, code), tone, result,
                elapsed, slow, last == null ? null : last.runId(), runIds, last == null ? null : last.chainId(),
                last == null ? null : last.httpStatus(), List.copyOf(findings), last == null ? null : last.nextAction(),
                outcome.reason(), outcome.notes(), outcome.notRecorded(),
                earlier == null ? null : earlier.outcome(),
                earlier == null ? null : outcomes.label(earlier.protocol(), earlier.outcome()),
                earlierResult, changed, steps, outcome.faultReference(), sent.isEmpty() ? null : entry.chain ? chainFault(outcome, found)
                : found == null ? null : found.fault(),
                nextStep(last)).withFaultCause(outcome.faultCause());
    }

    /**
     * Whether a chain failed because the service failed or did not answer. Its last step is not
     * the one to ask: a chain can fail on a determination at CRD and then time out at PAS. So the
     * steps whose own result is a failure are asked, and it is a fault only when every one of them
     * is. When no step failed by itself, the case failed for a step it never reached, and that is
     * a fault only when the chain stopped there because its last step was one.
     */
    private Boolean chainFault(CaseOutcome outcome, OutcomeCode last) {
        List<OutcomeCode> failing = new ArrayList<>();
        for (RunRecord record : outcome.runs()) {
            if (record.result() == TestResult.FAIL) {
                failing.add(outcomes.find(record.protocol(), record.outcome()));
            }
        }
        if (failing.isEmpty()) {
            return last == null ? null : last.fault();
        }
        return failing.stream().allMatch(code -> code != null && code.fault());
    }

    private static String label(RunRecord record) {
        return record.operation() == null ? "a step" : record.operation().label();
    }

    /** What the last step said should happen next, in words. Null when it named nothing this version knows. */
    private static String nextStep(RunRecord last) {
        if (last == null || Texts.blank(last.nextAction())) {
            return null;
        }
        try {
            return ChainService.instruction(NextAction.valueOf(last.nextAction().strip()));
        } catch (IllegalArgumentException e) {
            // Written by a later version. The code itself is still in its own column.
            return null;
        }
    }

    /**
     * One plain sentence saying how a case ended, true to what was sent. A chain that sent some
     * steps never reads "not sent", and a failure of this tool never reads as a case that cannot
     * be run.
     */
    static String rowLine(CaseOutcome outcome, TestResult result, Entry entry, List<String> sent, boolean configurationMoved) {
        CaseStatus status = outcome.status();
        String wentOut = sent.isEmpty() ? null : sent.size() == 1 ? sent.get(0) + " was sent"
                : String.join(" and ", sent) + " were sent";
        if (outcome.toolFailed()) {
            return wentOut == null ? "Tool failure: this tool failed while running the case, so whether a request left is not known. "
                    + "The reason gives the reference."
                    : "Tool failure part way: " + wentOut + "; this tool then failed, so whether the next request left is not known. "
                    + "The reason gives the reference.";
        }
        if (status == CaseStatus.COMPLETED) {
            if (!entry.chain) {
                return statusLine(status, result, entry.expected);
            }
            if (result == null) {
                return "Ran.";
            }
            return switch (result) {
                case PASS -> "Passed: every step the row expected something of ran and gave what it expected.";
                case FAIL -> "Failed: the journey is not what the row expected. The reason names the step.";
                case UNVERIFIED -> Texts.blank(entry.expected) ? "Unverified: the row declared no expected outcome."
                        : "Unverified: what the row expected was not compared with an answer. The reason and the steps say why.";
            };
        }
        if (status == CaseStatus.BLOCKED && wentOut != null) {
            return "Blocked part way: " + wentOut + "; the production guardrail refused the next step, a write, so it was not sent. "
                    + "This is not a failure.";
        }
        if (wentOut != null) {
            return "Stopped part way: " + wentOut + "; the next step was not sent. The reason says why.";
        }
        if (status == CaseStatus.NOT_CONFIGURED && configurationMoved) {
            return "Not sent: the configuration was changed while the suite was running.";
        }
        return statusLine(status, result, entry.expected);
    }

    /** One plain sentence saying how a single-step case ended. The outcome and the test result are stated apart from it. */
    static String statusLine(CaseStatus status, TestResult result, String expected) {
        return switch (status) {
            case COMPLETED -> result == null ? "Ran."
                    : switch (result) {
                        case PASS -> "Passed: the outcome is what the row expected.";
                        case FAIL -> "Failed: the outcome is not what the row expected.";
                        case UNVERIFIED -> Texts.blank(expected) ? "Unverified: the row declared no expected outcome."
                                : "Unverified: what the row expected was not compared with an answer. The run in History says why.";
                    };
            case BLOCKED -> "Blocked: the production guardrail refused this write. Nothing was sent. This is not a failure.";
            case INVALID -> "Not sent: the row is not valid.";
            case NOT_CONFIGURED -> "Not sent: the tool has no target for this in the active configuration.";
            case NOT_RUNNABLE -> "Not sent: this case cannot be run from a suite.";
        };
    }

    private static String cases(int count) {
        return count == 1 ? "1 case" : count + " cases";
    }

    /**
     * What an earlier run made of one case.
     *
     * @param result     the result of its last step
     * @param caseResult the result of the case as a whole, for a chain whose case line the ledger
     *                   holds. Null otherwise
     */
    private record Previous(String caseId, Protocol protocol, String outcome, TestResult result, TestResult caseResult) {

        /** Prints the protocol and the result. The case id is a cell of a suite file. */
        @Override
        public String toString() {
            return "Previous[" + protocol + ", " + result + "]";
        }
    }

    /** One case of a run. Its fields are read and written under the run's monitor. */
    static final class Entry {
        final int number;
        final String caseId;
        final String what;
        final boolean chain;
        final String expected;
        final List<String> problems;
        final String cannotRun;
        /** Holds the member ID. Dropped as soon as the case has run or the run has ended. */
        CaseSpec spec;
        SuiteRowState state = SuiteRowState.PENDING;
        SuiteCaseResult result;

        Entry(SuiteRow row) {
            this.number = row.number();
            this.caseId = row.caseId();
            this.what = row.what();
            this.chain = row.chain();
            this.expected = row.expected();
            this.problems = row.problems();
            this.cannotRun = row.cannotRun();
            this.spec = row.spec();
        }
    }

    /** One suite run. Everything that changes is read and written under this object's monitor. */
    private final class Run {
        final String id;
        final String name;
        final String format;
        final String startedAt;
        final EnvironmentRef environment;
        final long epoch;
        final String testerRef;
        final String comparedWith;
        final Map<String, Previous> previous;
        final List<Entry> entries = new ArrayList<>();
        final SuiteTimings timings = new SuiteTimings();
        /** Read under the service's lock as well as this run's, so a write here is seen there without a stale running state. */
        volatile SuiteState state = SuiteState.RUNNING;
        String stopRequested;
        String stopReason;
        String finishedAt;
        /** A failure of this tool that ended the run, kept apart until the run ends, when it holds only if the run stopped. */
        String faultReference;
        String faultCause;
        String stopReference;
        String stopCause;

        Run(String id, SuiteDefinition definition, String startedAt, EnvironmentRef environment, long epoch, String testerRef,
            String comparedWith, Map<String, Previous> previous) {
            this.id = id;
            this.name = definition.name();
            this.format = definition.format();
            this.startedAt = startedAt;
            this.environment = environment;
            this.epoch = epoch;
            this.testerRef = testerRef;
            this.comparedWith = comparedWith;
            this.previous = previous;
            for (SuiteRow row : definition.rows()) {
                entries.add(new Entry(row));
            }
        }

        synchronized int done() {
            return (int) entries.stream().filter(e -> e.state == SuiteRowState.DONE).count();
        }

        synchronized void requestStop(String why) {
            if (state == SuiteState.RUNNING && stopRequested == null) {
                stopRequested = why;
            }
        }

        synchronized void fault(String reference, String cause) {
            faultReference = reference;
            faultCause = cause;
        }

        /** Ends the run. Every case not attempted is marked as not run and its member ID is dropped. */
        synchronized void finish(String why, String when) {
            if (state != SuiteState.RUNNING) {
                return;
            }
            boolean all = true;
            for (Entry entry : entries) {
                entry.spec = null;
                if (entry.state != SuiteRowState.DONE) {
                    entry.state = SuiteRowState.NOT_RUN;
                    all = false;
                }
            }
            // A failure of this tool ends the run even when it came on the last case, which then never finished.
            boolean completed = all && faultReference == null;
            state = completed ? SuiteState.COMPLETED : SuiteState.STOPPED;
            stopReason = completed ? null : Texts.orElse(why, Texts.orElse(stopRequested, STOPPED_BY_TESTER));
            // Given only with the stop sentence it belongs to.
            stopReference = completed ? null : faultReference;
            stopCause = completed ? null : faultCause;
            finishedAt = when;
        }

        synchronized SuiteRunView view(boolean withRows) {
            int passed = 0;
            int failed = 0;
            int unverified = 0;
            int blocked = 0;
            int invalid = 0;
            int notConfigured = 0;
            int notRunnable = 0;
            int toolFailed = 0;
            int partly = 0;
            int failedByFault = 0;
            int notRun = 0;
            int waiting = 0;
            int notRecorded = 0;
            int caseLinesNotRecorded = 0;
            int withFindings = 0;
            int slow = 0;
            int changed = 0;
            Map<String, Integer> findingCounts = new java.util.TreeMap<>();
            List<SuiteCaseResult> rows = new ArrayList<>();
            for (Entry entry : entries) {
                SuiteCaseResult row = entry.result;
                if (row == null) {
                    boolean running = entry.state == SuiteRowState.RUNNING;
                    boolean skipped = entry.state == SuiteRowState.NOT_RUN;
                    SuiteCountGroup group = skipped ? SuiteCountGroup.NOT_RUN : SuiteCountGroup.WAITING;
                    if (skipped) {
                        notRun++;
                    } else {
                        waiting++;
                    }
                    row = new SuiteCaseResult(entry.number, entry.caseId, entry.what, entry.chain, entry.state, null,
                            skipped ? "Not run: the suite stopped before this case." : running ? "Running now." : "Waiting.",
                            entry.expected, null, null, null, null, null, false, null, List.of(), null, null, List.of(), null,
                            null, List.of(), List.of(), null, null, null, null, List.of(), null, null, null).countedIn(group);
                } else {
                    SuiteCountGroup group;
                    if (row.faultReference() != null) {
                        // A failure of this tool, which is neither a fact about the case nor about the service.
                        toolFailed++;
                        group = SuiteCountGroup.TOOL_FAILED;
                    } else if (row.partlySent()) {
                        partly++;
                        group = SuiteCountGroup.PARTLY;
                    } else {
                        group = switch (row.status()) {
                            case COMPLETED -> {
                                if (row.result() == TestResult.PASS) {
                                    passed++;
                                    yield SuiteCountGroup.PASSED;
                                } else if (row.result() == TestResult.FAIL) {
                                    failed++;
                                    failedByFault += Boolean.TRUE.equals(row.outcomeFault()) ? 1 : 0;
                                    yield SuiteCountGroup.FAILED;
                                }
                                unverified++;
                                yield SuiteCountGroup.UNVERIFIED;
                            }
                            case BLOCKED -> {
                                blocked++;
                                yield SuiteCountGroup.BLOCKED;
                            }
                            case INVALID -> {
                                invalid++;
                                yield SuiteCountGroup.INVALID;
                            }
                            case NOT_CONFIGURED -> {
                                notConfigured++;
                                yield SuiteCountGroup.NOT_CONFIGURED;
                            }
                            case NOT_RUNNABLE -> {
                                notRunnable++;
                                yield SuiteCountGroup.NOT_RUNNABLE;
                            }
                        };
                    }
                    row = row.countedIn(group);
                    for (String message : row.notRecorded()) {
                        // A case line records no call, so it is counted apart from the runs.
                        if (ChainCaseStep.aboutTheCaseLine(message)) {
                            caseLinesNotRecorded++;
                        } else {
                            notRecorded++;
                        }
                    }
                    withFindings += row.findings().isEmpty() ? 0 : 1;
                    for (String kind : row.findings()) {
                        findingCounts.merge(kind, 1, Integer::sum);
                    }
                    slow += row.slow() ? 1 : 0;
                    changed += Boolean.TRUE.equals(row.changed()) ? 1 : 0;
                }
                rows.add(row);
            }
            int total = entries.size();
            int done = total - notRun - waiting;
            SuiteCounts counts = new SuiteCounts(total, done, passed, failed, unverified, blocked, invalid, notConfigured, notRunnable,
                    toolFailed, partly, notRun, waiting, notRecorded, withFindings, slow, changed, failedByFault);
            boolean stopping = state == SuiteState.RUNNING && stopRequested != null;
            return new SuiteRunView(id, name, format, state, headline(counts, stopping), stopReason, startedAt, finishedAt, epa.mode(),
                    epa.target(), environment, epoch, testerRef, comparedWith, counts, timings.summarise(support.slowThresholdMillis()),
                    findingCounts, notes(counts, caseLinesNotRecorded), withRows ? rows : null, stopping ? Boolean.TRUE : null, null,
                    stopReference, stopCause);
        }

        private String headline(SuiteCounts c, boolean stopping) {
            List<String> parts = new ArrayList<>();
            add(parts, c.passed(), "passed");
            if (c.failed() > 0 && c.failedByFault() > 0) {
                // A service that was down is a finding about the environment, not a determination, so the two are told apart.
                parts.add(c.failedByFault() == c.failed()
                        ? c.failed() + " failed " + (c.failed() == 1 ? "" : "(all of them) ") + "because the service failed or did not answer"
                        : c.failed() + " failed (" + c.failedByFault() + " of them because the service failed or did not answer)");
            } else {
                add(parts, c.failed(), "failed");
            }
            add(parts, c.unverified(), "unverified");
            add(parts, c.blocked(), "blocked");
            add(parts, c.invalid(), "not valid");
            add(parts, c.notConfigured(), "not configured");
            add(parts, c.notRunnable(), "not runnable");
            add(parts, c.partly(), "stopped part way");
            add(parts, c.toolFailed(), c.toolFailed() == 1 ? "tool failure" : "tool failures");
            add(parts, c.notRun(), "not run");
            String detail = parts.isEmpty() ? "" : " " + String.join(", ", parts) + ".";
            return switch (state) {
                case RUNNING -> (stopping ? "Stopping after the current case: " : "Running: ") + c.done() + " of " + cases(c.total())
                        + " done." + detail;
                case COMPLETED -> "Finished: " + cases(c.total()) + "." + detail;
                case STOPPED -> "Stopped after " + c.done() + " of " + cases(c.total()) + "." + detail;
            };
        }

        private void add(List<String> parts, int count, String word) {
            if (count > 0) {
                parts.add(count + " " + word);
            }
        }

        private List<String> notes(SuiteCounts c, int caseLinesNotRecorded) {
            List<String> notes = new ArrayList<>();
            if (c.notRecorded() > 0) {
                notes.add(c.notRecorded() + (c.notRecorded() == 1 ? " run was" : " runs were") + " NOT recorded in the ledger. The rows "
                        + "say which, and why. Their results are shown here and will not be in History or in the ledger export.");
            }
            if (caseLinesNotRecorded > 0) {
                notes.add("The result as a whole of " + (caseLinesNotRecorded == 1 ? "1 chain case was" : caseLinesNotRecorded
                        + " chain cases were") + " NOT recorded in the ledger, so History cannot show " + (caseLinesNotRecorded == 1
                        ? "it" : "them") + ". The rows say which, and why.");
            }
            if (c.blocked() > 0) {
                // A chain blocked at its submission sent its earlier steps, so this never says nothing was sent for the case.
                notes.add(c.blocked() + (c.blocked() == 1 ? " case was" : " cases were") + " blocked by the production guardrail. "
                        + "The write that was blocked was not sent; the steps of a chain before it were, and its row says which. "
                        + "A blocked case is not a failed case.");
            }
            if (c.toolFailed() > 0) {
                notes.add(c.toolFailed() + (c.toolFailed() == 1 ? " case" : " cases") + " ended in a failure of this tool. Whether a "
                        + "request left for " + (c.toolFailed() == 1 ? "it" : "them") + " is not known: look in History, under the suite "
                        + "run id, before running " + (c.toolFailed() == 1 ? "it" : "them") + " again. Each row gives the reference.");
            }
            if (previous != null && state != SuiteState.RUNNING) {
                Set<String> inThisRun = new LinkedHashSet<>();
                for (Entry entry : entries) {
                    inThisRun.add(entry.caseId.toLowerCase(Locale.ROOT));
                }
                List<String> missing = previous.values().stream().filter(p -> !inThisRun.contains(p.caseId().toLowerCase(Locale.ROOT)))
                        .map(Previous::caseId).toList();
                if (!missing.isEmpty()) {
                    notes.add(missing.size() + (missing.size() == 1 ? " case" : " cases") + " of the earlier run "
                            + (missing.size() == 1 ? "is" : "are") + " not in this file: " + String.join(", ", missing.stream().limit(20).toList())
                            + (missing.size() > 20 ? ", and more" : "") + ".");
                }
            }
            return notes;
        }
    }
}
