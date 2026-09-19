package com.thehiddenbrain.interop.extract.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleState {
    public String definitionId;
    public int versionNo;
    public String nextFireAt;
    public String lastFiredAt;
    public String lastSlaCheckDate;
    public String note;
}
