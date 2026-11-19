package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One mock scenario as the scenario selector lists it.
 *
 * @param number           the position in the numbered list testers refer to
 * @param sentinelMemberId the member ID that selects this scenario on mock, or null
 * @param sentinelNpi      the NPI that selects this scenario on mock, or null
 * @param expectedOutcome  the outcome code the scenario file claims to produce, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioSummary(String id, int number, String label, String description, String sentinelMemberId,
                              String sentinelNpi, ScenarioBehavior behavior, Integer httpStatus, String expectedOutcome) {
}
