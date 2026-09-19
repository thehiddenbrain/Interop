package com.thehiddenbrain.interop.extract.definition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole vendor layout as data. The screens edit this, the compiler consumes it, and the same compiled result
 * drives the sample and the production run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Spec {

    public List<Field> fields = new ArrayList<>();
    public List<JoinSpec> joins = new ArrayList<>();
    public List<FilterSpec> filters = new ArrayList<>();
    public List<SortSpec> sort = new ArrayList<>();
    public Scope scope = new Scope();
    public FileFormat fileFormat = new FileFormat();
    public SampleSpec sample = new SampleSpec();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Field {
        public String id;
        public int position;
        public String header;
        public String description;
        public List<Input> inputs = new ArrayList<>();
        /** Required when there is more than one input: CONCAT or COALESCE. */
        public RuleSpec combiner;
        public List<RuleSpec> rules = new ArrayList<>();
        public Integer maxLength;
        /** TRUNCATE or FAIL. */
        public String onOverflow;
        /** Text or typed default used when the value is empty after the rules. */
        public String defaultValue;
        /** Fixed width only. */
        public Integer width;
        public String align;
        public String padChar;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Input {
        public String element;
        public String constant;
        public List<RuleSpec> rules = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RuleSpec {
        public String type;
        public Map<String, Object> params = new LinkedHashMap<>();

        public RuleSpec() {}

        public RuleSpec(String type, Map<String, Object> params) {
            this.type = type;
            this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JoinSpec {
        /** Catalog join path id, e.g. member->coverage. */
        public String path;
        /** Selector id for MANY paths, e.g. CURRENT_AS_OF_RUN_DATE. */
        public String select;
    }

    /** Either an element/op/value predicate or a reference to a catalog filter template with params. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FilterSpec {
        public String element;
        public String op;
        /** A literal, a list of literals, or a token object {kind: TOKEN, token: RUN_DATE, offsetDays: -30}. */
        public Object value;
        public String template;
        public Map<String, Object> params;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SortSpec {
        public String element;
        public String direction = "ASC";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Scope {
        /** FULL or INCREMENTAL. */
        public String mode = "FULL";
        public List<String> watermarkElements = new ArrayList<>();
        public int lagMinutes = 15;
        public String initialWatermark;
        /** Only rows changed since the previous delivered snapshot; emits add/change/terminate flags. Roadmap feature, surfaced but disabled. */
        public Boolean deltaMode;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileFormat {
        /** DELIMITED or FIXED_WIDTH. */
        public String type = "DELIMITED";
        public String delimiter = "|";
        /** NONE, MINIMAL or ALL. */
        public String quoteMode = "NONE";
        public boolean headerRow = true;
        /** CRLF or LF. */
        public String lineEnding = "CRLF";
        public String encoding = "UTF-8";
        public String nullText = "";
        /** STRIP, REPLACE or FAIL when a value contains the delimiter. */
        public String delimiterInValue = "STRIP";
        public String headerRecord;
        public String trailerRecord;
        public String extension = "txt";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SampleSpec {
        public int maxRows = 200;
        /** Optional list of root primary keys to sample, e.g. "these 25 test members". */
        public List<String> cohort = new ArrayList<>();
        /** Build the sample from catalog example values instead of real data. */
        public boolean synthetic;
    }
}
