package org.p32h.interop.epa.pave.model.suite;

/**
 * How the cases of a suite run divide. Every case is in exactly one of the buckets from
 * {@code passed} to {@code waiting}, so they add up to {@code total}.
 *
 * @param done          cases attempted so far, whatever came of them
 * @param passed        cases that ran and matched what they declared
 * @param failed        cases that ran and did not match
 * @param unverified    cases that ran and declared nothing to compare with
 * @param blocked       cases the production guardrail refused. Not failures
 * @param invalid       cases whose inputs are not valid. Nothing was sent
 * @param notConfigured cases the tool has no target for. Nothing was sent
 * @param notRunnable   cases that cannot be run from a suite row. Nothing was sent
 * @param toolFailed    cases this tool failed on. Whether a request left is not known, and none of
 *                      them is counted as not runnable
 * @param partly        cases that sent some of their steps and then stopped before a step that was
 *                      due, because it was refused or not configured. Not counted as not valid
 * @param notRun        cases the suite stopped before reaching
 * @param waiting       cases not reached yet while the suite is running
 * @param notRecorded   runs the ledger did not keep
 * @param withFindings  cases with a finding a person should look at
 * @param slow          cases with a call that completed but took longer than the threshold
 * @param changed       cases whose outcome or result differs from the run compared with
 * @param failedByFault of the failed cases, those whose outcome is a fault of the service, such as
 *                      a timeout or an error status, rather than a determination
 */
public record SuiteCounts(int total, int done, int passed, int failed, int unverified, int blocked, int invalid,
                          int notConfigured, int notRunnable, int toolFailed, int partly, int notRun, int waiting, int notRecorded,
                          int withFindings, int slow, int changed, int failedByFault) {
}
