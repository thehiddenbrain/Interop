package com.thehiddenbrain.interop.extract.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Last committed window end per definition. Advances only in the same step that marks a run delivered. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Watermark {
    public String definitionId;
    public String value;
    public List<String> elements;
    public String lastRunId;
    public String updatedAt;
}
