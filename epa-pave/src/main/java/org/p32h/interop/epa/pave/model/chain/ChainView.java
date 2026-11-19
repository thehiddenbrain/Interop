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
        List<String> notes) {

    public ChainView {
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        stages = stages == null ? null : List.copyOf(stages);
        otherRuns = otherRuns == null ? List.of() : List.copyOf(otherRuns);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The same chain without its runs, for a list of chains. Each stage keeps its state and its latest run. */
    public ChainView summary() {
        List<ChainStage> brief = stages == null ? null : stages.stream()
                .map(stage -> new ChainStage(stage.protocol(), stage.name(), stage.state(), stage.stateLine(), stage.latest(), List.of()))
                .toList();
        return new ChainView(chainId, headline, nextAction, nextHeadline, nextStage, ended, startedAt, lastRunAt, environment,
                environmentKind, profile, target, member, procedureCodes, brief, List.of(), runCount, elapsedMillis, notes);
    }
}
