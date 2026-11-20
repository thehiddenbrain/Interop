package org.p32h.interop.epa.pave.service.chain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.chain.ChainRun;
import org.p32h.interop.epa.pave.model.chain.ChainStage;
import org.p32h.interop.epa.pave.model.chain.ChainStageState;
import org.p32h.interop.epa.pave.model.chain.ChainView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Reads a chain back from the ledger: the runs made under one chain id, arranged as the journey
 * they belong to, with where each stage stands and what should happen next.
 *
 * <p>It works from ledger records alone. It never guesses a stage's state from the outcome code:
 * each run recorded what it said should happen next, and that is what places the stages after it.
 * A stage that did not run says why, in words, because "skipped" and "not reached yet" are
 * different facts and a blank would hide which one it is.
 */
@Service
public class ChainService {

    /** The stages of the journey, in order. */
    static final List<Protocol> STAGES = List.of(Protocol.CRD, Protocol.DTR, Protocol.PAS);

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final ObjectProvider<RunLedger> ledger;
    private final OutcomeCatalogue outcomes;

    public ChainService(ObjectProvider<RunLedger> ledger, OutcomeCatalogue outcomes) {
        this.ledger = ledger;
        this.outcomes = outcomes;
    }

    /** One chain with every run in it. */
    public ChainView view(String chainId) {
        String id = Texts.trimToNull(chainId);
        RunLedger store = ledger.getIfAvailable();
        List<RunRecord> records = id == null || store == null || !RunStamps.isChainId(id) ? List.of() : store.query(RunQuery.forChain(id));
        if (records.isEmpty()) {
            throw new NotFoundException("The ledger holds no run of a chain with that id. A chain exists once a run has been recorded "
                    + "under it, so check that the test run ledger is switched on and writable.");
        }
        ChainView built = build(id, records);
        Integer unreadable = unreadableLines(store);
        if (unreadable != null && unreadable > 0) {
            // A damaged line cannot be read to tell which chain it was, so the note says it may be this one's.
            List<String> notes = new ArrayList<>(built.notes());
            notes.add("The ledger has " + unreadable + (unreadable == 1 ? " line" : " lines") + " it cannot read. A run of this chain may be "
                    + "among them, so a step may be missing here.");
            built = new ChainView(built.chainId(), built.headline(), built.nextAction(), built.nextHeadline(), built.nextStage(), built.ended(),
                    built.startedAt(), built.lastRunAt(), built.environment(), built.environmentKind(), built.profile(), built.target(),
                    built.member(), built.procedureCodes(), built.stages(), built.otherRuns(), built.runCount(), built.elapsedMillis(), notes,
                    built.caseResult(), built.caseExpected(), built.caseLine(), built.replayNote());
        }
        return built;
    }

    /** How many ledger lines cannot be read, or null when the ledger cannot say. */
    private static Integer unreadableLines(RunLedger store) {
        try {
            return store.health().unreadableLines();
        } catch (RuntimeException e) {
            // The chain is still shown from what could be read. Whether lines were lost is then not known, and nothing is claimed.
            return null;
        }
    }

    /**
     * The chains with a run in the date range, most recent first, without their runs. Each chain
     * is read whole, so a chain that began before the range or went on after it is still stated
     * as it stands.
     *
     * @param limit the most chains to return. Null for the default
     */
    public List<ChainView> list(LocalDate from, LocalDate to, Integer limit) {
        return list(from, to, limit, null);
    }

    /** As {@link #list(LocalDate, LocalDate, Integer)}, with the days on the given clock. Null means the days the runs' own timestamps name. */
    public List<ChainView> list(LocalDate from, LocalDate to, Integer limit, java.time.ZoneId zone) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ValidationException.of("from", "The first day is after the last day.");
        }
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw ValidationException.of("limit", "The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        RunLedger store = ledger.getIfAvailable();
        if (store == null) {
            return List.of();
        }
        int most = limit == null ? DEFAULT_LIMIT : limit;
        Set<String> ids = new LinkedHashSet<>();
        // Newest first, so chains come out ordered by their most recent run.
        for (RunRecord record : store.query(RunQuery.between(from, to, zone))) {
            if (Texts.present(record.chainId()) && ids.size() < most) {
                ids.add(record.chainId());
            }
        }
        List<ChainView> chains = new ArrayList<>();
        for (String id : ids) {
            List<RunRecord> records = store.query(RunQuery.forChain(id));
            if (!records.isEmpty()) {
                chains.add(build(id, records).summary());
            }
        }
        return chains;
    }

    private ChainView build(String chainId, List<RunRecord> newestFirst) {
        List<RunRecord> records = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(records);

        Map<EpaOperation, String> latestOfKind = new EnumMap<>(EpaOperation.class);
        for (RunRecord record : records) {
            if (record.operation() != null) {
                latestOfKind.put(record.operation(), record.runId());
            }
        }
        Map<Protocol, List<ChainRun>> byStage = new EnumMap<>(Protocol.class);
        List<ChainRun> others = new ArrayList<>();
        long elapsed = 0;
        for (RunRecord record : records) {
            ChainRun run = run(record, record.runId().equals(latestOfKind.get(record.operation())));
            if (STAGES.contains(record.protocol())) {
                byStage.computeIfAbsent(record.protocol(), p -> new ArrayList<>()).add(run);
            } else {
                others.add(run);
            }
            elapsed += record.elapsedMillis() == null ? 0 : record.elapsedMillis();
        }

        List<ChainStage> stages = new ArrayList<>();
        for (Protocol protocol : STAGES) {
            stages.add(stage(protocol, byStage));
        }

        // The most recent run of a stage says where the journey stands now, even if it is a re-run of an early step.
        ChainRun last = null;
        for (int i = records.size() - 1; i >= 0 && last == null; i--) {
            if (STAGES.contains(records.get(i).protocol())) {
                List<ChainRun> runs = byStage.get(records.get(i).protocol());
                String runId = records.get(i).runId();
                last = runs.stream().filter(r -> r.runId().equals(runId)).findFirst().orElse(null);
            }
        }
        NextAction next = last == null ? null : last.nextAction();
        Protocol nextStage = last == null ? null : target(next, last.protocol());
        boolean ended = next == NextAction.FLOW_ENDS;
        String headline;
        if (last == null) {
            headline = "This chain holds no CRD, DTR or PAS run yet.";
        } else {
            String said = name(last.protocol()) + ": " + Texts.orElse(last.outcomeLabel(), "no outcome recorded") + ".";
            // "Nothing further" is not a next step, so it is not introduced as one.
            headline = ended ? said + " The journey ends here."
                    : next == null ? said + " The record does not say what should happen next."
                    : next == NextAction.NOTHING_FURTHER ? said + " " + instruction(next)
                    : said + " Next: " + lowerFirst(instruction(next));
        }

        RunRecord first = records.get(0);
        RunRecord latest = records.get(records.size() - 1);
        List<String> notes = new ArrayList<>();
        Set<String> members = new LinkedHashSet<>();
        Set<String> environments = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        for (RunRecord record : records) {
            String member = member(record);
            if (member != null) {
                members.add(member);
            }
            if (Texts.present(record.environment())) {
                environments.add(record.environment());
            }
            codes.addAll(record.procedureCodes());
        }
        if (members.size() > 1) {
            notes.add("The runs of this chain are not all for the same member. A chain follows one member and one order, so check "
                    + "which runs belong together.");
        }
        if (environments.size() > 1) {
            notes.add("The runs of this chain went to more than one environment: " + String.join(", ", environments) + ".");
        }
        // The newest case line says how the case as a whole came out. An older one was a case run before it.
        RunRecord asCase = null;
        for (int i = records.size() - 1; i >= 0 && asCase == null; i--) {
            asCase = records.get(i).caseResult() != null ? records.get(i) : null;
        }
        TestResult caseResult = asCase == null ? null : asCase.caseResult();
        String caseExpected = asCase == null ? null : asCase.caseExpected();
        return new ChainView(chainId, headline, next, next == null ? null : instruction(next), ended ? null : nextStage, ended,
                first.timestamp(), latest.timestamp(), latest.environment(), latest.environmentKind(), latest.profile(), latest.target(),
                members.size() == 1 ? members.iterator().next() : null, List.copyOf(codes), stages, others, records.size(), elapsed, notes,
                caseResult, caseExpected, caseLine(caseResult, caseExpected),
                // Replay refuses a line that used what an earlier step handed on, and only such a line. A chain started at
                // PAS or DTR followed nothing, so its first step replays as any run does.
                records.stream().anyMatch(r -> r.unrecordedInputs().contains(RunRecord.INPUT_PRIOR_STEP)) ? REPLAY_NOTE : null);
    }

    /** The sentence for a chain that has a step a replay cannot send again by itself. */
    static final String REPLAY_NOTE = "A step of this chain that used what the step before it handed on cannot be replayed by itself: "
            + "its ledger line does not hold what it was handed. To run the journey again, run it as a suite row with the protocol chain.";

    /** What the result of a chain run as a case means, in one sentence. */
    static String caseLine(TestResult result, String expected) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case PASS -> "Run as a case, the journey passed: every step the case expected something of ran and gave what it expected.";
            case FAIL -> "Run as a case, the journey failed against what the case expected. A step that did not give what was expected "
                    + "is marked failed on its run, and a step the case expected that has no run here was never reached.";
            case UNVERIFIED -> Texts.blank(expected) ? "Run as a case, the journey is unverified: the case declared no expected outcome."
                    : "Run as a case, the journey is unverified: what the case expected was not compared with an answer. Either a step "
                    + "gave no answer that could be compared, or the case stopped before a step that was due. The stages show how far it got.";
        };
    }

    private ChainStage stage(Protocol protocol, Map<Protocol, List<ChainRun>> byStage) {
        List<ChainRun> runs = byStage.getOrDefault(protocol, List.of());
        if (!runs.isEmpty()) {
            ChainRun latest = runs.get(runs.size() - 1);
            String said = Texts.orElse(latest.outcomeLabel(), "no outcome recorded");
            NextAction next = latest.nextAction();
            ChainStageState state;
            String line;
            if (next == null) {
                state = ChainStageState.DONE;
                line = "Ran: " + said + ". The record does not say what should happen next.";
            } else if (next == NextAction.FLOW_ENDS) {
                state = ChainStageState.DONE;
                line = "Done: " + said + ". The journey ends here.";
            } else if (next == NextAction.NOTHING_FURTHER) {
                // Done, and said apart from a stage that handed on: this one did not, and it did not end the journey either.
                state = ChainStageState.DONE;
                line = "Done: " + said + ". " + instruction(next);
            } else {
                Protocol target = target(next, protocol);
                if (target == protocol && attention(next)) {
                    state = ChainStageState.NEEDS_ATTENTION;
                    line = "Needs attention: " + said + ". " + instruction(next);
                } else if (target == protocol) {
                    state = ChainStageState.IN_PROGRESS;
                    line = "In progress: " + said + ". " + instruction(next);
                } else {
                    state = ChainStageState.DONE;
                    line = "Done: " + said + ".";
                }
            }
            return new ChainStage(protocol, name(protocol), state, line, latest, runs);
        }

        // The stage did not run. The nearest earlier stage that did run says why.
        int index = STAGES.indexOf(protocol);
        for (int i = index - 1; i >= 0; i--) {
            List<ChainRun> earlier = byStage.getOrDefault(STAGES.get(i), List.of());
            if (earlier.isEmpty()) {
                continue;
            }
            Protocol from = STAGES.get(i);
            ChainRun latest = earlier.get(earlier.size() - 1);
            NextAction next = latest.nextAction();
            String said = Texts.orElse(latest.outcomeLabel(), "no outcome recorded");
            if (next == NextAction.FLOW_ENDS) {
                return new ChainStage(protocol, name(protocol), ChainStageState.NOT_NEEDED, "Not needed: the journey ended at "
                        + shortName(from) + " with \"" + said + "\".", null, List.of());
            }
            if (next == NextAction.NOTHING_FURTHER) {
                // The earlier stage is not unfinished, so "has to finish first" would send a person to fix what is not
                // broken. And nothing said the journey can do without this stage, so it is not "not needed" either.
                return new ChainStage(protocol, name(protocol), ChainStageState.NOT_REACHED, "Not reached: " + shortName(from) + " answered \""
                        + said + "\" and called for nothing further, so nothing has led on to this stage.", null, List.of());
            }
            if (next == null) {
                // The record names no next step. Saying the earlier stage "has to finish" would claim it is unfinished.
                return new ChainStage(protocol, name(protocol), ChainStageState.NOT_REACHED, "Not reached: the " + shortName(from)
                        + " record does not say what should happen next, so nothing has led on to this stage.", null, List.of());
            }
            Protocol target = target(next, from);
            if (target == protocol) {
                return new ChainStage(protocol, name(protocol), ChainStageState.NEXT, "Next: " + lowerFirst(instruction(next)), null, List.of());
            }
            if (target != null && STAGES.indexOf(target) > index) {
                return new ChainStage(protocol, name(protocol), ChainStageState.NOT_NEEDED, "Not needed: " + shortName(from) + " answered \""
                        + said + "\" and went straight on to " + shortName(target) + ".", null, List.of());
            }
            if (target != null && STAGES.indexOf(target) > i) {
                // A stage between the two is the one the journey calls for now, so that stage comes first, not the one that ran.
                return new ChainStage(protocol, name(protocol), ChainStageState.NOT_REACHED, "Not reached: " + shortName(target)
                        + " comes first.", null, List.of());
            }
            return new ChainStage(protocol, name(protocol), ChainStageState.NOT_REACHED, "Not reached: " + shortName(from)
                    + " has to finish first. It stands at \"" + said + "\".", null, List.of());
        }
        boolean laterRan = STAGES.subList(index + 1, STAGES.size()).stream().anyMatch(p -> !byStage.getOrDefault(p, List.of()).isEmpty());
        return new ChainStage(protocol, name(protocol), ChainStageState.NOT_RUN, laterRan
                ? "Not run in this chain. The chain was started at a later step." : "Not run in this chain.", null, List.of());
    }

    private ChainRun run(RunRecord record, boolean latestOfItsKind) {
        return new ChainRun(record.runId(), record.timestamp(), record.protocol(), record.operation(),
                record.operation() == null ? null : record.operation().label(), record.outcome(),
                outcomes.label(record.protocol(), record.outcome()), outcomes.tone(record.protocol(), record.outcome()),
                record.expected(), record.result(), record.elapsedMillis(), Boolean.TRUE.equals(record.slow()), record.httpStatus(),
                nextAction(record), record.findings(), record.scenarioId(), latestOfItsKind, record.caseResult());
    }

    /**
     * The action a record names. A blocked run always needs the guardrail dealt with, whatever else
     * the record says; a blocked replay or suite row says so in its own action, because unlocking
     * cannot help it.
     */
    private static NextAction nextAction(RunRecord record) {
        if (RunOutcome.BLOCKED.name().equals(record.outcome())) {
            return NextAction.SEND_ON_ITS_OWN.name().equals(Texts.trimToNull(record.nextAction())) ? NextAction.SEND_ON_ITS_OWN
                    : NextAction.UNLOCK_OR_SWITCH;
        }
        if (Texts.blank(record.nextAction())) {
            return null;
        }
        try {
            return NextAction.valueOf(record.nextAction().strip());
        } catch (IllegalArgumentException e) {
            // Written by a later version of this tool. Shown as unknown, never mapped to a guess.
            return null;
        }
    }

    /** The stage an action belongs to. An action that fixes something belongs to the stage that asked for it. */
    static Protocol target(NextAction action, Protocol stage) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case START_DTR, ANSWER_NEXT_QUESTION, SUBMIT_RESPONSE -> Protocol.DTR;
            case START_PAS, CHECK_STATUS -> Protocol.PAS;
            // Neither names a stage. One says the journey is over, the other that nothing follows from the call.
            case FLOW_ENDS, NOTHING_FURTHER -> null;
            case FIX_TEST_DATA, FIX_REQUEST, SUPPLY_INFORMATION, CHECK_ENVIRONMENT, OPEN_CONFIG, UNLOCK_OR_SWITCH, SEND_ON_ITS_OWN, REVIEW -> stage;
        };
    }

    /**
     * True for an action that asks a person to fix or check something, as opposed to carrying on
     * with the stage. Every action is named and there is no default, so an action added later does
     * not compile until someone has decided which of the two it is.
     */
    static boolean attention(NextAction action) {
        return switch (action) {
            case FIX_TEST_DATA, FIX_REQUEST, SUPPLY_INFORMATION, CHECK_ENVIRONMENT, OPEN_CONFIG, UNLOCK_OR_SWITCH, SEND_ON_ITS_OWN, REVIEW -> true;
            case START_DTR, ANSWER_NEXT_QUESTION, SUBMIT_RESPONSE, START_PAS, CHECK_STATUS, FLOW_ENDS, NOTHING_FURTHER -> false;
        };
    }

    /**
     * The instruction for an action, in plain words. The run itself gave a fuller reason on screen;
     * the ledger keeps the action. The words are the action's own, so the chain view and the
     * vocabulary served to the History tab cannot differ.
     */
    public static String instruction(NextAction action) {
        return action.label();
    }

    static String name(Protocol protocol) {
        return switch (protocol) {
            case CRD -> "Coverage requirements (CRD)";
            case DTR -> "Documentation (DTR)";
            case PAS -> "Prior authorization request (PAS)";
            default -> protocol.label();
        };
    }

    private static String shortName(Protocol protocol) {
        return protocol.name();
    }

    private static String lowerFirst(String sentence) {
        return sentence.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + sentence.substring(1);
    }

    /** The member as the ledger line shows it. Never anything the line does not hold. */
    private static String member(RunRecord record) {
        if (Texts.present(record.memberId())) {
            return record.memberId();
        }
        if (Texts.present(record.memberToken())) {
            // The start of the token is enough to tell two members apart on a screen. The whole token is on the ledger line.
            String token = record.memberToken();
            return "token " + (token.length() > 12 ? token.substring(0, 12) : token)
                    + (Texts.present(record.memberIdLast4()) ? ", ending " + record.memberIdLast4() : "");
        }
        return null;
    }
}
