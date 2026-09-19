package com.thehiddenbrain.interop.extract.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Notification {
    public String id;
    public String at;
    /** FAILED, HELD, DELIVERED, TRANSFERRED, SLA_MISSED, APPROVAL_REQUESTED, APPROVED, REJECTED, CONTRACT_ENDING, CATALOG_DRIFT, INFO. */
    public String event;
    /** INFO, WARN or CRITICAL. */
    public String severity;
    public String title;
    public String message;
    public String definitionId;
    public String runId;
    public List<String> recipients;
    public boolean read;
}
