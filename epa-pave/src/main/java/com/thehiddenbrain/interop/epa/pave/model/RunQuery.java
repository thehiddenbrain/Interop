package com.thehiddenbrain.interop.epa.pave.model;

import java.time.LocalDate;

/**
 * A question to the ledger. Every component is optional; a null does not filter.
 *
 * @param from  first day to include
 * @param to    last day to include
 * @param limit the most records to return, newest first. Null or zero for no limit
 */
public record RunQuery(LocalDate from, LocalDate to, Protocol protocol, TestResult result, String outcome, String chainId,
                       String suiteRunId, Integer limit) {

    public static RunQuery all() {
        return new RunQuery(null, null, null, null, null, null, null, null);
    }

    public static RunQuery between(LocalDate from, LocalDate to) {
        return new RunQuery(from, to, null, null, null, null, null, null);
    }

    public static RunQuery forChain(String chainId) {
        return new RunQuery(null, null, null, null, null, chainId, null, null);
    }

    public static RunQuery forSuite(String suiteRunId) {
        return new RunQuery(null, null, null, null, null, null, suiteRunId, null);
    }
}
