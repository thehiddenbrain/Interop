package com.thehiddenbrain.interop.extract.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEvent {
    public String id;
    public String at;
    public String actor;
    public String actorName;
    public String role;
    public String action;
    public String targetType;
    public String targetId;
    public String definitionId;
    public String vendorCode;
    public boolean phi;
    public Map<String, Object> details;
}
