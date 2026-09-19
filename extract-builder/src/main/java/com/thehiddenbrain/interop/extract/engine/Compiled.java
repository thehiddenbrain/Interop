package com.thehiddenbrain.interop.extract.engine;

import com.thehiddenbrain.interop.extract.catalog.CatalogModel;
import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.transform.Rule;

import java.util.*;

/**
 * A spec after compilation: resolved elements, instantiated rules, the join plan and every problem found.
 * The same compiled object drives validate, preview, sample and production runs.
 */
public class Compiled {

    public record RuleInstance(Rule rule, Map<String, Object> params) {}

    public record Input(String elementId, CatalogModel.Element element, String constant, List<RuleInstance> rules, boolean phi) {}

    public record Field(Spec.Field spec, List<Input> inputs, RuleInstance combiner, List<RuleInstance> rules, String outputType, boolean phi) {
        public String id() { return spec.id; }
        public String header() { return spec.header; }
    }

    public record Join(CatalogModel.JoinPath path, String selector) {}

    public record Problem(String severity, String where, String message) {}

    public String grain;
    public String subjectArea;
    public List<Field> fields = new ArrayList<>();
    public List<Join> joins = new ArrayList<>();
    /** Every element the query must project, including filter, sort, watermark and rule-referenced elements. */
    public LinkedHashSet<String> elementsNeeded = new LinkedHashSet<>();
    public List<Problem> problems = new ArrayList<>();
    public String sqlText = "";
    public Set<String> lookupsUsed = new LinkedHashSet<>();
    public Spec spec;

    public List<Problem> errors() { return problems.stream().filter(p -> p.severity().equals("ERROR")).toList(); }
    public List<Problem> warnings() { return problems.stream().filter(p -> !p.severity().equals("ERROR")).toList(); }
    public boolean ok() { return errors().isEmpty(); }

    void error(String where, String message) { problems.add(new Problem("ERROR", where, message)); }
    void warn(String where, String message) { problems.add(new Problem("WARNING", where, message)); }
    void info(String where, String message) { problems.add(new Problem("INFO", where, message)); }
}
