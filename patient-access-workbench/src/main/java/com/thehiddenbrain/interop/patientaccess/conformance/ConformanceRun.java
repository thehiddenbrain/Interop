package com.thehiddenbrain.interop.patientaccess.conformance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A run of the suite (or a subset of groups) against one environment, as stored on disk. */
public record ConformanceRun(String id, String environmentId, String environmentName, String patientId, List<String> groups,
                             String status, Instant startedAt, Instant finishedAt, int totalChecks, int completedChecks,
                             Map<String, Integer> counts, List<CheckResult> results, String error) {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public record Summary(String id, String environmentId, String environmentName, String patientId, List<String> groups, String status,
                          Instant startedAt, Instant finishedAt, int totalChecks, int completedChecks, Map<String, Integer> counts, String error) {
    }

    public Summary summary() {
        return new Summary(id, environmentId, environmentName, patientId, groups, status, startedAt, finishedAt, totalChecks, completedChecks, counts, error);
    }
}
