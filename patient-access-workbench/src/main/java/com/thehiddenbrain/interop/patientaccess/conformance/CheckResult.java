package com.thehiddenbrain.interop.patientaccess.conformance;

import java.util.ArrayList;
import java.util.List;

/** Result of one check with the history entries used as evidence. */
public record CheckResult(String checkId, String group, String title, Severity severity, CheckStatus status, String message,
                          List<String> details, List<String> requestIds, long durationMs) {

    public static Builder builder(Check check) {
        return new Builder(check);
    }

    public static final class Builder {
        private final Check check;
        private final List<String> details = new ArrayList<>();
        private final List<String> requestIds = new ArrayList<>();
        private long durationMs;

        Builder(Check check) {
            this.check = check;
        }

        public Builder detail(String d) {
            if (d != null) {
                details.add(d);
            }
            return this;
        }

        public Builder evidence(String requestId) {
            if (requestId != null && !requestIds.contains(requestId)) {
                requestIds.add(requestId);
            }
            return this;
        }

        public Builder evidence(List<String> ids) {
            if (ids != null) {
                ids.forEach(this::evidence);
            }
            return this;
        }

        public Builder duration(long ms) {
            this.durationMs = ms;
            return this;
        }

        public CheckResult pass(String message) {
            return build(CheckStatus.PASS, message);
        }

        /** Failure at the check's own severity (SHALL → FAIL, SHOULD → WARN, MAY → INFO). */
        public CheckResult fail(String message) {
            return build(check.severity().onFailure(), message);
        }

        public CheckResult warn(String message) {
            return build(CheckStatus.WARN, message);
        }

        public CheckResult info(String message) {
            return build(CheckStatus.INFO, message);
        }

        public CheckResult skip(String message) {
            return build(CheckStatus.SKIP, message);
        }

        public CheckResult error(String message) {
            return build(CheckStatus.ERROR, message);
        }

        public CheckResult build(CheckStatus status, String message) {
            return new CheckResult(check.id(), check.group(), check.title(), check.severity(), status, message, List.copyOf(details),
                    List.copyOf(requestIds), durationMs);
        }
    }
}
