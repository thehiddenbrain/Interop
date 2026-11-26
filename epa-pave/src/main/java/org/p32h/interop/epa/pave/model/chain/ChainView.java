package org.p32h.interop.epa.pave.model.chain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;

/**
 * A chain: one member, one order, followed through CRD, DTR and PAS as far as the journey went.
 * Built from the ledger, so it reads the same after a restart.
 *
 * @param headline      one plain sentence saying where the journey stands
 * @param nextAction    what the most recent run said should happen next
 * @param nextHeadline  that instruction in plain words
 * @param nextStage     the stage the next step belongs to, or null when the journey has ended or
 *                      the next step is not a stage
 * @param ended         true when the most recent run said the journey is complete
 * @param member        the member as the ledger shows it: the ID when recording it is configured,
 *                      otherwise the token and last characters. Null when the runs disagree
 * @param stages        CRD, DTR and PAS, in that order, each with where it stands
 * @param otherRuns     runs in the chain that belong to no stage, such as a subscription read
 * @param elapsedMillis the time every call in the chain took, added up
 * @param notes         what a reader should know about the chain as a whole
 * @param caseResult    when the chain was run as a case, the result of the case as a whole, from
 *                      the case line the ledger holds for its last step. A chain that expected an
 *                      answer from a step it never reached has failed although every step it made
 *                      passed. Null when the chain was not run as a case
 * @param caseExpected  what that case declared for the chain as a whole
 * @param caseLine      one plain sentence saying what the case result means. Null with no case result
 * @param replayNote    for a chain with a step after CRD, that such a step cannot be replayed by
 *                      itself and how to run the journey again. Null otherwise
 * @param steps         the four steps the Workbench draws (Coverage check, Questionnaire, Submit,
 *                      Status), each with its chip and its latest run, beside the three stages,
 *                      which stay as they are. Null only for a view built without them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChainView(
        String chainId,
        String headline,
        NextAction nextAction,
        String nextHeadline,
        Protocol nextStage,
        boolean ended,
        String startedAt,
        String lastRunAt,
        String environment,
        EnvironmentKind environmentKind,
        String profile,
        String target,
        String member,
        List<String> procedureCodes,
        List<ChainStage> stages,
        List<ChainRun> otherRuns,
        int runCount,
        long elapsedMillis,
        List<String> notes,
        org.p32h.interop.epa.pave.model.TestResult caseResult,
        String caseExpected,
        String caseLine,
        String replayNote,
        List<ChainStep> steps) {

    public ChainView {
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        stages = stages == null ? null : List.copyOf(stages);
        otherRuns = otherRuns == null ? List.of() : List.copyOf(otherRuns);
        notes = notes == null ? List.of() : List.copyOf(notes);
        steps = steps == null ? null : List.copyOf(steps);
    }

    /** A chain without steps, as the callers written before the four steps existed build one. */
    public ChainView(String chainId, String headline, NextAction nextAction, String nextHeadline, Protocol nextStage, boolean ended,
                     String startedAt, String lastRunAt, String environment, EnvironmentKind environmentKind, String profile, String target,
                     String member, List<String> procedureCodes, List<ChainStage> stages, List<ChainRun> otherRuns, int runCount,
                     long elapsedMillis, List<String> notes, org.p32h.interop.epa.pave.model.TestResult caseResult, String caseExpected,
                     String caseLine, String replayNote) {
        this(chainId, headline, nextAction, nextHeadline, nextStage, ended, startedAt, lastRunAt, environment, environmentKind, profile,
                target, member, procedureCodes, stages, otherRuns, runCount, elapsedMillis, notes, caseResult, caseExpected, caseLine,
                replayNote, null);
    }

    /** The same chain without its runs, for a list of chains. Each stage keeps its state and its latest run, each step its chip. */
    public ChainView summary() {
        List<ChainStage> brief = stages == null ? null : stages.stream()
                .map(stage -> new ChainStage(stage.protocol(), stage.name(), stage.state(), stage.stateLine(), stage.latest(), List.of()))
                .toList();
        return new ChainView(chainId, headline, nextAction, nextHeadline, nextStage, ended, startedAt, lastRunAt, environment,
                environmentKind, profile, target, member, procedureCodes, brief, List.of(), runCount, elapsedMillis, notes, caseResult,
                caseExpected, caseLine, replayNote, steps);
    }

    /** Prints the chain id and the count. The member is named here as the ledger allows, which is still a member. */
    @Override
    public String toString() {
        return "ChainView[" + chainId + ", " + runCount + " runs]";
    }
}
