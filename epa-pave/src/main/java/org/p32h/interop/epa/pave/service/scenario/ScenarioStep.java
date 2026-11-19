package org.p32h.interop.epa.pave.service.scenario;

import org.p32h.interop.epa.pave.model.ScenarioBehavior;

/**
 * What a mock does for one call: answer with a status and a body, never answer, or be unreachable.
 *
 * @param httpStatus  the status to answer with. Null unless the behavior is respond
 * @param delayMillis how long the mock waits first when mock latency is enabled. Never null
 * @param body        the exact text to return, or null for no body
 * @param expect      what this step claims to produce, for a scenario whose calls each have their
 *                    own answer, such as a submission that pends and an inquiry that then approves.
 *                    Null when the step makes no claim of its own
 */
public record ScenarioStep(ScenarioBehavior behavior, Integer httpStatus, long delayMillis, String body, ScenarioExpect expect) {
}
