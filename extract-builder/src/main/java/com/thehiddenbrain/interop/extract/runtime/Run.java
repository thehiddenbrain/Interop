package com.thehiddenbrain.interop.extract.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.thehiddenbrain.interop.extract.definition.ProductionConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One execution of a definition version: what ran, what it produced, where it went. Every file can be explained from its run. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Run {

    public String id;
    public String definitionId;
    public String definitionName;
    public String vendorCode;
    public int versionNo;
    /** SAMPLE, PRODUCTION or PREVIEW. */
    public String mode;
    /** MANUAL, SCHEDULE, RETRY, RERUN, RELEASE or API. */
    public String trigger;
    /** QUEUED, RUNNING, WRITTEN, HELD, DELIVERED, TRANSFERRED, FAILED, SKIPPED_EMPTY, SKIPPED_CONTRACT, DELETED. */
    public String status;
    public String startedAt;
    public String finishedAt;
    public String startedBy;
    public String businessDate;
    public String windowFrom;
    public String windowTo;
    public Integer rowCount;
    public Integer scanned;
    public Integer matched;
    public String fileName;
    public String filePath;
    public String sha256;
    public Long bytes;
    public String sqlText;
    public Map<String, Object> boundParams;
    public List<Warning> warnings = new ArrayList<>();
    public List<Finding> qualityFindings = new ArrayList<>();
    public List<String> maskedFields = new ArrayList<>();
    public Map<String, FieldStat> fieldStats = new LinkedHashMap<>();
    public List<String> previewLines = new ArrayList<>();
    public List<Map<String, String>> previewRows = new ArrayList<>();
    public boolean masked;
    public boolean synthetic;
    public String route;
    public String deliveryPath;
    public String deliveryReceipt;
    public String deliveredAt;
    public String transferredAt;
    public Integer vendorReceivedCount;
    public String ackPath;
    public String error;
    public String heldReason;
    public String releasedBy;
    public String retryOfRunId;
    public String specHash;
    public String catalogChecksum;
    public String engineVersion;
    public Map<String, String> lookupSnapshots = new LinkedHashMap<>();
    public ProductionConfig configSnapshot;
    public List<DeliveryAttempt> attempts = new ArrayList<>();
    public long durationMs;
    public boolean archived;
    public String gate;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Warning {
        public String field;
        public String code;
        public int count;
        public String example;
        public String severity;

        public Warning() {}

        public Warning(String field, String code, int count, String example, String severity) {
            this.field = field; this.code = code; this.count = count; this.example = example; this.severity = severity;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Finding {
        public String code;
        public String severity;
        public String message;
        public Object observed;
        public Object expected;

        public Finding() {}

        public Finding(String code, String severity, String message, Object observed, Object expected) {
            this.code = code; this.severity = severity; this.message = message; this.observed = observed; this.expected = expected;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldStat {
        public String header;
        public int rows;
        public int nulls;
        public int truncated;
        public int maxLength;
        public String sum;
        public double nullRatePct;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeliveryAttempt {
        public int attemptNo;
        public String at;
        public String status;
        public String receipt;
        public String message;
    }
}
