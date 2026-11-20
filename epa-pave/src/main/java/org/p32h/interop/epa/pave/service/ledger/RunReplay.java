package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs recorded runs again against the configuration in force now, and says what changed.
 *
 * <p>A run is rebuilt from its ledger line alone and handed to the same runner a suite uses, so it
 * takes the path a manual run takes and writes its own ledger record, marked as a replay of the
 * original. It is not part of the original's chain or suite.
 *
 * <p><b>Honest about what cannot be reproduced.</b> A line that does not hold everything the
 * original sent is not run again, because the result would compare two different requests: a run
 * whose member ID was not recorded, a run that sent something typed or edited by hand, a line that
 * records something that arrived. Each is reported as not replayable with the reason. No run that
 * was selected is left out of the report, and none is run with a value put in the place of one
 * that is missing.
 *
 * <p><b>Selection.</b> A date range selects the runs in it, oldest first, except those that are
 * themselves replays. A replay writes its record on the day it is made, so a range that reaches
 * today would otherwise take in the records of the last replay and send twice as much each time it
 * was repeated. The report says how many were left out for that reason, and a replay can still be
 * run again by naming its id. Run ids select those runs, in the order given. With both, the ids
 * are the selection and the range is a check on it: an id outside the range is refused, since
 * replaying sends real requests and sending more than was meant is the mistake to avoid. An id the
 * ledger does not hold is refused too, before anything is sent.
 *
 * <p>A replay never carries a production unlock, so a write against a production environment
 * comes back blocked. Against any other environment a write is sent again as a read is, which
 * submits or changes something a second time, and the report says how many of those there were.
 * It also says how many runs went to another environment or profile than the first time, because
 * a difference in those can come from the move and not from the service.
 */
@Component
public class RunReplay {

    private static final Logger log = LoggerFactory.getLogger(RunReplay.class);

    /**
     * The most runs one request may send again. A replay sends a real request for each of them and
     * answers only when the last one is back, so a range chosen by mistake could otherwise send
     * thousands of requests at a test environment before anyone saw a result. Runs that are
     * selected and cannot be sent again are reported and do not count towards it.
     */
    public static final int MOST_RUNS = 1000;

    static final String NO_RUNNER = "Nothing in this build can run a case again, so no run was sent.";
    static final String NOTHING_SELECTED = "No run in that date range was selected, so nothing was run again.";

    /** What the record writes in place of a correlation value it refused. It is not an identifier and is never passed on as one. */
    private static final Pattern NOT_RECORDED = Pattern.compile("\\(not recorded, \\d+ characters\\)");

    /**
     * The operations a case sends with a value read from the line's correlation entries, and the
     * key it reads. A refused value there was part of the request, so without it a run again would
     * send something else. Elsewhere a correlation value is what came back, and is only compared.
     */
    private static final Map<EpaOperation, String> SENT_FROM_CORRELATION = Map.of(
            EpaOperation.PAS_INQUIRE, RunRecord.CORR_CLAIM_ID,
            EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, RunRecord.CORR_QUESTIONNAIRE);

    /** What the DTR service writes among the questionnaires in place of one that was not an address. */
    private static final Pattern NOT_A_URL = Pattern.compile("\\(not a url, \\d+ characters\\)");

    /** What the record writes in place of a procedure code that did not have the shape of one. */
    private static final Pattern CODE_NOT_KEPT = Pattern.compile("\\(not a code, \\d+ characters\\)");

    /** What the record writes in place of text it did not keep, anywhere in a coverage code entry or a vendor name. */
    private static final Pattern TEXT_NOT_KEPT =
            Pattern.compile("\\((?:not a code|not a code entry|vendor not on the known list|" + Pattern.quote(RunRecord.READS_LIKE_AN_IDENTIFIER)
                    + "), \\d+ characters\\)");

    /** Each kind of input a line can say it does not hold, in words a tester reads. */
    static final Map<String, String> INPUTS_IN_WORDS = Map.of(
            RunRecord.INPUT_BODY, "a request body that was edited by hand",
            RunRecord.INPUT_MEMBER_DETAILS, "member details that were typed or changed by hand",
            RunRecord.INPUT_PROVIDER_DETAILS, "provider details that were typed or changed by hand",
            RunRecord.INPUT_RUN_VALUES, "values that were typed on the form for that run only",
            RunRecord.INPUT_CHOSEN_REQUEST, "a collection request that was picked by hand",
            RunRecord.INPUT_PRIOR_STEP, "answers or identifiers handed on by an earlier step",
            RunRecord.INPUT_INBOUND, "something that arrived from outside");

    private final RunLedger ledger;
    private final ObjectProvider<CaseRunner> runner;
    private final ObjectProvider<OperationGateway> gateway;
    private final Clock clock;

    public RunReplay(RunLedger ledger, ObjectProvider<CaseRunner> runner, ObjectProvider<OperationGateway> gateway, Clock clock) {
        this.ledger = ledger;
        this.runner = runner;
        this.gateway = gateway;
        this.clock = clock;
    }

    /**
     * Runs the selected runs again and reports on each.
     *
     * @throws ValidationException when neither a range nor an id is given, when the range is the
     *                             wrong way round, when an id is outside the range given with it,
     *                             or when more than {@link #MOST_RUNS} runs would be sent again
     * @throws NotFoundException   when an id is not in the ledger
     */
    public ReplayReport replay(LocalDate from, LocalDate to, List<String> runIds) {
        List<String> ids = distinctIds(runIds);
        boolean ranged = from != null || to != null;
        if (!ranged && ids.isEmpty()) {
            throw new ValidationException("Nothing was selected to run again. Nothing was sent.", List.of(
                    new FieldError("runIds", "Give a date range in from and to, or the ids of the runs in runIds, or both.")));
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ValidationException.of("from", "The from date is after the to date.");
        }
        List<String> notes = new ArrayList<>();
        List<RunRecord> selected = ids.isEmpty() ? oldestFirst(from, to, notes) : byId(ids, ranged, from, to);

        CaseRunner caseRunner = runner.getIfAvailable();
        // The limit is on what would be sent, not on what was selected. A day of chained runs holds
        // several lines for each request that can be sent again, and a line that is only reported
        // as not replayable costs the service nothing.
        long toSend = caseRunner == null ? 0 : selected.stream().filter(run -> whyNotReplayable(run) == null).count();
        if (toSend > MOST_RUNS) {
            throw new ValidationException("That would send " + toSend + " runs again, and one replay sends at most " + MOST_RUNS
                    + ". Nothing was sent.", List.of(new FieldError(ids.isEmpty() ? "from" : "runIds",
                    "Choose a shorter date range or name fewer runs, and replay the rest in a second request.")));
        }
        OperationGateway configuration = gateway.getIfAvailable();
        // Every run of this replay is held to the configuration it started under. A switch of
        // environment part way through would otherwise put the answers of two environments in one report.
        Long epoch = configuration == null ? null : configuration.epoch();
        String startedAt = RunStamps.timestamp(clock);
        List<ReplayEntry> results = new ArrayList<>();
        for (RunRecord original : selected) {
            results.add(caseRunner == null ? ReplayEntry.withNoRecord(original, ReplayStatus.NOT_REPLAYABLE, NO_RUNNER)
                    : replayOne(original, caseRunner, epoch));
        }
        if (selected.isEmpty()) {
            notes.add(NOTHING_SELECTED);
        } else if (caseRunner == null) {
            notes.add(NO_RUNNER);
        }
        String moved = ranSomewhereElse(results);
        if (moved != null) {
            notes.add(moved);
        }
        String writes = writesSentAgain(results);
        if (writes != null) {
            notes.add(writes);
        }
        return ReplayReport.of(startedAt, from == null ? null : from.toString(), to == null ? null : to.toString(), caseRunner != null,
                caseRunner == null ? null : epoch, notes, results);
    }

    private static boolean wasCompared(ReplayStatus status) {
        return status == ReplayStatus.SAME || status == ReplayStatus.CHANGED || status == ReplayStatus.NOT_COMPARABLE;
    }

    /**
     * What there is to say when runs were sent to another environment, or on another profile, than
     * the first time, or null when none were. Running against the configuration in force now is the
     * point of a replay. But then a difference can come from the move and not from the service, and
     * the counts alone would not show which. The environment is not listed among the differences of
     * a run, because those say how the answer changed.
     */
    private static String ranSomewhereElse(List<ReplayEntry> results) {
        long moved = results.stream().filter(entry -> wasCompared(entry.status()))
                .filter(entry -> !Objects.equals(entry.original().environment(), entry.replayed().environment())
                        || !Objects.equals(lower(entry.original().profile()), lower(entry.replayed().profile())))
                .count();
        if (moved == 0) {
            return null;
        }
        return moved == 1
                ? "1 of the runs that were sent again ran against another environment or profile than the first time. A difference in it "
                + "can come from that move and not from the service. Its entry shows both."
                : moved + " of the runs that were sent again ran against another environment or profile than the first time. A difference "
                + "in those can come from that move and not from the service. Each entry shows both.";
    }

    private static String lower(String text) {
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    /**
     * What there is to say when writes were sent again, or null when none were. A submission that
     * is replayed is a new submission. Someone asking what changed since Tuesday may not have had
     * that in mind, so the report says how many there were. On the mock profile nothing leaves the
     * tool, so there is nothing to say: which profile a run was made on is read from its own record.
     */
    private static String writesSentAgain(List<ReplayEntry> results) {
        Set<String> labels = new LinkedHashSet<>();
        int writes = 0;
        for (ReplayEntry entry : results) {
            if (wasCompared(entry.status()) && entry.original().operation().write()
                    && EpaProperties.LIVE.equalsIgnoreCase(entry.replayed().profile())) {
                writes++;
                labels.add(entry.original().operation().label());
            }
        }
        if (writes == 0) {
            return null;
        }
        String which = " (" + String.join(", ", labels) + "). ";
        return writes == 1
                ? "1 of the runs that were sent again is a write" + which + "It went to the service as a real request, so it can have "
                + "created or changed something there again."
                : writes + " of the runs that were sent again are writes" + which + "Each went to the service as a real request, so each "
                + "can have created or changed something there again.";
    }

    private static List<String> distinctIds(List<String> runIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (runIds != null) {
            for (int i = 0; i < runIds.size(); i++) {
                if (Texts.blank(runIds.get(i))) {
                    throw ValidationException.of("runIds[" + i + "]", "This run id is blank.");
                }
                ids.add(runIds.get(i).strip());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * The runs of a date range that are not themselves replays, in the order they happened, so
     * they are sent again in that order. What was left out is said in a note.
     */
    private List<RunRecord> oldestFirst(LocalDate from, LocalDate to, List<String> notes) {
        List<RunRecord> inRange = new ArrayList<>(ledger.query(RunQuery.between(from, to)));
        Collections.reverse(inRange);
        List<RunRecord> originals = inRange.stream().filter(run -> Texts.blank(run.replayOfRunId())).toList();
        int replays = inRange.size() - originals.size();
        if (replays > 0) {
            notes.add((replays == 1 ? "1 run in that date range is itself a replay and was"
                    : replays + " runs in that date range are themselves replays and were")
                    + " not selected. A date range runs the original runs again, so that repeating a replay does not send twice as much "
                    + "each time. To run a replay again, name it by its run id.");
        }
        return originals;
    }

    private List<RunRecord> byId(List<String> ids, boolean ranged, LocalDate from, LocalDate to) {
        Map<String, RunRecord> inRange = new LinkedHashMap<>();
        if (ranged) {
            ledger.query(RunQuery.between(from, to)).forEach(run -> inRange.put(run.runId(), run));
        }
        List<RunRecord> selected = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<FieldError> outside = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            RunRecord run = inRange.get(id);
            if (run == null) {
                Optional<RunRecord> found = ledger.find(id);
                if (found.isEmpty()) {
                    unknown.add(id);
                    continue;
                }
                if (ranged) {
                    outside.add(new FieldError("runIds[" + i + "]", "This run is outside the date range given with it. Leave the range out to "
                            + "run it again, or correct the range."));
                    continue;
                }
                run = found.get();
            }
            selected.add(run);
        }
        if (!unknown.isEmpty()) {
            // An id came from the request body, so none is repeated back: the count says enough.
            throw new NotFoundException(unknown.size() == 1 ? "There is no run with that id. Nothing was sent."
                    : unknown.size() + " of the run ids given are not in the ledger. Nothing was sent.");
        }
        if (!outside.isEmpty()) {
            throw new ValidationException(outside.size() + " of the run ids given are outside the date range given with them. Nothing was sent.",
                    outside);
        }
        return selected;
    }

    private ReplayEntry replayOne(RunRecord original, CaseRunner caseRunner, Long epoch) {
        String refusal = whyNotReplayable(original);
        if (refusal != null) {
            return ReplayEntry.withNoRecord(original, ReplayStatus.NOT_REPLAYABLE, refusal);
        }
        CaseOutcome outcome;
        try {
            outcome = caseRunner.run(specFor(original, epoch));
        } catch (RuntimeException e) {
            String reference = UUID.randomUUID().toString();
            // Logs the kinds of exception and where they were thrown. Never a message, which can quote a request or a response.
            log.error("Running a recorded run again failed inside this tool, reference {}: {}", reference, Faults.describe(e));
            return ReplayEntry.withNoRecord(original, ReplayStatus.ERROR, "This tool failed while running this again, so nothing can be said "
                    + "about the answer. A request may have been sent. Report the reference " + reference + ".");
        }
        if (outcome == null) {
            return ReplayEntry.withNoRecord(original, ReplayStatus.ERROR, "The runner gave no answer for this run, so nothing can be said about "
                    + "it. A request may have been sent.");
        }
        RunRecord replayed = outcome.finalRun();
        if (outcome.status() == null) {
            // Whatever the runner did hand back is still shown: the record of a run that was made, and the ledger's word on it.
            return new ReplayEntry(original.runId(), ReplayStatus.ERROR, "The runner did not say how this run ended, so nothing can be said "
                    + "about the answer. A request may have been sent.", replayed == null ? null : replayed.runId(), List.of(),
                    outcome.notRecorded(), original, replayed);
        }
        return switch (outcome.status()) {
            case COMPLETED -> compared(original, replayed, outcome);
            case BLOCKED -> notCompleted(original, ReplayStatus.BLOCKED, outcome, "The production guardrail refused to send this run again.");
            case NOT_CONFIGURED -> notCompleted(original, ReplayStatus.NOT_CONFIGURED, outcome,
                    "The tool is not configured to send this run again.");
            case INVALID -> notCompleted(original, ReplayStatus.INVALID, outcome, "The inputs on this line were found not valid.");
            case NOT_RUNNABLE -> notCompleted(original, ReplayStatus.NOT_REPLAYABLE, outcome,
                    "This run cannot be run again from what its line holds.");
        };
    }

    /** Why the line alone cannot rebuild the request that was sent, or null when it can. */
    private static String whyNotReplayable(RunRecord original) {
        if (!original.operation().outbound()) {
            return "This line records something that arrived, or a step completed inside the tool (" + original.operation().label()
                    + "). Nothing was sent when it was made, so there is nothing to send again.";
        }
        if (!original.unrecordedInputs().isEmpty()) {
            List<String> words = original.unrecordedInputs().stream().map(RunReplay::inWords).toList();
            return "This run sent something its ledger line does not hold: " + String.join("; ", words) + ". Running it again from the "
                    + "line would send a different request, and the two answers could not be compared.";
        }
        if (Texts.blank(original.memberId()) && Texts.present(original.memberToken())) {
            return "The member ID of this run was not recorded. The line holds a token in its place, and a token cannot be turned back "
                    + "into the ID, so the same request cannot be sent again. Member IDs are kept only while tracking.record-member-id is true, "
                    + "and on the mock profile only for this tool's own P32H- sentinels and S123456789-style placeholders.";
        }
        String needed = SENT_FROM_CORRELATION.get(original.operation());
        String sent = needed == null ? null : original.correlation().get(needed);
        if (sent != null && (NOT_RECORDED.matcher(sent).matches() || NOT_A_URL.matcher(sent).find())) {
            return "This run sent " + (RunRecord.CORR_CLAIM_ID.equals(needed) ? "a claim id" : "the questionnaire addresses it asked for")
                    + ", and its line holds a statement that something was there in its place, because what was there did not have the "
                    + "shape of one. Running it again without it would send a different request, and the two answers could not be compared.";
        }
        if (original.procedureCodes().stream().anyMatch(code -> CODE_NOT_KEPT.matcher(code).matches())) {
            return "A procedure code of this run was not recorded, because what was sent did not have the shape of a code. The line "
                    + "holds a statement that something was there and not the code, so the same request cannot be sent again.";
        }
        return null;
    }

    private static String inWords(String input) {
        String words = INPUTS_IN_WORDS.get(input);
        // A name a later version wrote. It is a name and never a value, so it can be shown, but only if it reads as one.
        return words != null ? words : RunIds.plain(input) ? "an input this version does not know (" + input + ")"
                : "an input this version does not know";
    }

    /** The case that sends the same request again, built from the line and nothing else. */
    static CaseSpec specFor(RunRecord original, Long epoch) {
        Map<String, String> correlation = new LinkedHashMap<>();
        original.correlation().forEach((key, value) -> {
            // An authorization number the ledger tokenised is no longer the number, and a value the
            // record refused never was an identifier. Neither can be sent anywhere.
            boolean tokenised = RunRecord.CORR_AUTHORIZATION_REF.equals(key) && LedgerTokens.looksLikeToken(value);
            if (!tokenised && !NOT_RECORDED.matcher(value).matches()) {
                correlation.put(key, value);
            }
        });
        return CaseSpec.builder()
                .chain(false)
                .operation(original.operation())
                .memberId(original.memberId())
                .npi(original.npi())
                .procedureCodes(original.procedureCodes())
                .dateOfService(original.dateOfService())
                .scenarioId(original.scenarioId())
                .expected(original.expected())
                .testerRef(original.testerRef())
                .replayOfRunId(original.runId())
                .correlation(correlation)
                .configEpoch(epoch)
                .build();
    }

    private static ReplayEntry notCompleted(RunRecord original, ReplayStatus status, CaseOutcome outcome, String fallback) {
        String reason = Texts.present(outcome.reason()) ? outcome.reason().strip() : fallback + " The runner gave no further reason.";
        boolean namesAProvider = original.protocol() == Protocol.CRD || original.protocol() == Protocol.DTR || original.protocol() == Protocol.PAS;
        if ((status == ReplayStatus.INVALID || status == ReplayStatus.NOT_REPLAYABLE) && namesAProvider && Texts.blank(original.npi())) {
            // The line cannot say whether an NPI was sent and not kept, or never sent. The tester can.
            reason += " This line holds no NPI. If the original run had one, it was not recorded (tracking.record-npi), and that is "
                    + "why the same request cannot be sent again.";
        }
        RunRecord replayed = outcome.finalRun();
        return new ReplayEntry(original.runId(), status, reason, replayed == null ? null : replayed.runId(), List.of(), outcome.notRecorded(),
                original, replayed);
    }

    private static ReplayEntry compared(RunRecord asRead, RunRecord asReturned, CaseOutcome outcome) {
        if (asReturned == null) {
            return new ReplayEntry(asRead.runId(), ReplayStatus.ERROR, "The runner reported that this run was sent again and returned no "
                    + "record of it, so there is nothing to compare.", null, List.of(), outcome.notRecorded(), asRead, null);
        }
        // Both in the form a ledger line reads back in, so a record that has been through the
        // ledger and one that has not are compared like for like. See TrackingPolicy.settled.
        RunRecord original = TrackingPolicy.settled(asRead);
        RunRecord replayed = TrackingPolicy.settled(asReturned);
        if (replayed.operation() != original.operation()) {
            return new ReplayEntry(original.runId(), ReplayStatus.ERROR, "The runner answered with the record of another operation ("
                    + replayed.operation().label() + "), so the two cannot be compared.", replayed.runId(), List.of(),
                    outcome.notRecorded(), original, replayed);
        }
        List<ReplayDifference> differences = new ArrayList<>();
        List<String> notKept = new ArrayList<>();
        compare(differences, "outcome", original.outcome(), replayed.outcome());
        // The same codes in another order are the same answer, so the order is not compared.
        if (!sorted(original.coverageCodes()).equals(sorted(replayed.coverageCodes()))) {
            differences.add(new ReplayDifference("coverageCodes", joined(original.coverageCodes()), joined(replayed.coverageCodes())));
        } else if (original.coverageCodes().stream().anyMatch(RunReplay::holdsTextNotKept)) {
            notKept.add("coverageCodes");
        }
        if (!Objects.equals(original.delegatedVendor(), replayed.delegatedVendor())) {
            differences.add(new ReplayDifference("delegatedVendor", original.delegatedVendor(), replayed.delegatedVendor()));
        } else if (holdsTextNotKept(original.delegatedVendor())) {
            notKept.add("delegatedVendor");
        }
        compare(differences, "httpStatus", text(original.httpStatus()), text(replayed.httpStatus()));
        compare(differences, "errorType", original.errorType(), replayed.errorType());
        compare(differences, "result", original.result().wire(), replayed.result().wire());
        compare(differences, "nextAction", original.nextAction(), replayed.nextAction());

        ReplayStatus status = !differences.isEmpty() ? ReplayStatus.CHANGED : notKept.isEmpty() ? ReplayStatus.SAME : ReplayStatus.NOT_COMPARABLE;
        // Two answers that were not kept as text can read alike on the line. Calling them the same
        // would be a comparison that passed because nothing was compared.
        String reason = status != ReplayStatus.NOT_COMPARABLE ? null : "What the ledger kept is the same for both runs, but part of the "
                + "answer was not kept as text (" + String.join(", ", notKept) + "): each line holds a statement that something was "
                + "there and not the text. The two cannot be shown to be the same, so they are not reported as the same. The text is "
                + "shown on the result of a run when it is made, and is never kept.";
        return new ReplayEntry(original.runId(), status, reason, replayed.runId(), differences, outcome.notRecorded(), original, replayed);
    }

    private static void compare(List<ReplayDifference> differences, String field, String was, String now) {
        if (!Objects.equals(was, now)) {
            differences.add(new ReplayDifference(field, was, now));
        }
    }

    private static boolean holdsTextNotKept(String value) {
        return value != null && TEXT_NOT_KEPT.matcher(value).find();
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private static String joined(List<String> values) {
        return values.isEmpty() ? null : String.join("; ", values);
    }

    private static String text(Integer value) {
        return value == null ? null : value.toString();
    }
}
