package org.p32h.interop.epa.pave.service.scenario;

import java.util.Map;

/**
 * What a scenario file claims it produces, in a form a test can check.
 *
 * <p>Every bundled scenario is driven through the real mock client and the real mapper by a test
 * that compares the result with this block. A scenario file that stops producing the determination
 * it claims fails that test.
 *
 * @param outcome         the outcome code the scenario must produce
 * @param errorType       the failure type name it must produce, or null when the file does not say
 * @param httpStatus      the HTTP status it must produce, or null when there is none or the file does not say
 * @param codes           standard codes the response must carry, by element name
 * @param delegatedVendor the delegated vendor the response must name, or null
 */
public record ScenarioExpect(String outcome, String errorType, Integer httpStatus, Map<String, String> codes,
                             String delegatedVendor) {

    public ScenarioExpect {
        codes = codes == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(codes));
    }
}
