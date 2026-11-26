package org.p32h.interop.epa.pave.model.chain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.Protocol;

/**
 * One stage of the journey: CRD, DTR or PAS.
 *
 * @param name      the stage in plain words
 * @param stateLine one plain sentence saying where the stage stands and, when it did not run, why
 * @param latest    the most recent run of the stage, which is the one that states where it stands.
 *                  Null when the stage has not run
 * @param runs      every run of the stage in this chain, oldest first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChainStage(Protocol protocol, String name, ChainStageState state, String stateLine, ChainRun latest,
                         List<ChainRun> runs) {

    public ChainStage {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }
}
