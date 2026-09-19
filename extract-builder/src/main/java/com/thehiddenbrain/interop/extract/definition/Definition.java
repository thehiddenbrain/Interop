package com.thehiddenbrain.interop.extract.definition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A vendor feed: one definition per vendor and layout, with a list of versions. Only a DRAFT version's spec is
 * editable; every later state is frozen and hashed so the sample, the approval and every production run refer to
 * exactly the same layout.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Definition {

    public String id;
    public String slug;
    public String name;
    public String vendorCode;
    public String subjectArea;
    /** The catalog entity that is the output grain: one row per instance of it. */
    public String grain;
    public String description;
    public boolean template;
    public String owner;
    public String createdAt;
    public String createdBy;
    public String updatedAt;
    public List<Version> versions = new ArrayList<>();

    @JsonIgnore
    public Optional<Version> version(int no) {
        return versions.stream().filter(v -> v.versionNo == no).findFirst();
    }

    @JsonIgnore
    public Version latest() {
        return versions.get(versions.size() - 1);
    }

    @JsonIgnore
    public Optional<Version> production() {
        return versions.stream().filter(v -> VersionStatus.PRODUCTION.name().equals(v.status)).findFirst();
    }

    @JsonIgnore
    public Optional<Version> open() {
        return versions.stream().filter(v -> !VersionStatus.PRODUCTION.name().equals(v.status) && !VersionStatus.RETIRED.name().equals(v.status)).findFirst();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Version {
        public int versionNo;
        public String status = VersionStatus.DRAFT.name();
        public Spec spec = new Spec();
        public String specHash;
        public String catalogChecksum;
        public String createdAt;
        public String createdBy;
        public String updatedAt;
        public String changeNote;
        public String sampledRunId;
        public String sampledAt;
        public String vendorAcceptance;
        public String approvalRequestedAt;
        public String approvalRequestedBy;
        public String approvalNote;
        public String approvedAt;
        public String approvedBy;
        public String rejectedAt;
        public String rejectedBy;
        public String rejectNote;
        public String productionizedAt;
        public String productionizedBy;
        public String retiredAt;
        public String retiredReason;
        public ProductionConfig productionConfig;
        public Boolean schedulePaused;
    }
}
