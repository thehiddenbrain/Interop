package com.thehiddenbrain.interop.extract.engine;

import com.thehiddenbrain.interop.extract.catalog.CatalogModel.*;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleRegistry;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Turns a spec plus the catalog into a {@link Compiled} definition. Every analyst mistake surfaces here, before any
 * query runs: unknown elements, entities the grain cannot reach, rule chains whose types do not line up, filters on
 * elements that are not filterable, fixed-width fields without a width.
 */
@Service
public class Compiler {

    private static final Set<String> FILTER_OPS = Set.of("EQ", "NE", "IN", "NOT_IN", "GT", "GTE", "LT", "LTE", "BETWEEN", "IS_NULL", "IS_NOT_NULL", "STARTS_WITH", "CONTAINS");

    private final CatalogService catalog;
    private final RuleRegistry registry;

    public Compiler(CatalogService catalog, RuleRegistry registry) {
        this.catalog = catalog;
        this.registry = registry;
    }

    public Compiled compile(Definition def, Spec spec) {
        Compiled c = new Compiled();
        c.spec = spec;
        c.grain = def.grain;
        c.subjectArea = def.subjectArea;
        if (spec == null) {
            c.error("spec", "The definition has no layout yet");
            return c;
        }
        Entity root;
        try {
            root = catalog.entity(def.grain);
        } catch (RuntimeException e) {
            c.error("grain", "Unknown grain entity: " + def.grain);
            return c;
        }
        for (String pk : root.primaryKeys()) {
            catalog.elementsOf(root.id()).stream().filter(e -> e.column().equals(pk)).findFirst().ifPresent(e -> c.elementsNeeded.add(e.id()));
        }

        if (spec.fields == null || spec.fields.isEmpty()) c.error("fields", "Add at least one output field");
        Set<String> headers = new HashSet<>();
        int pos = 0;
        for (Spec.Field f : spec.fields == null ? List.<Spec.Field>of() : spec.fields) {
            pos++;
            if (f.id == null || f.id.isBlank()) f.id = "f" + pos;
            f.position = pos;
            String where = "field " + f.position + (f.header == null ? "" : " (" + f.header + ")");
            if (f.header == null || f.header.isBlank()) c.error(where, "Every field needs an output header");
            else if (!headers.add(f.header.trim().toUpperCase())) c.error(where, "Duplicate header " + f.header);
            c.fields.add(compileField(c, f, where));
        }

        // joins: every referenced entity other than the root needs a join path from the root
        Map<String, Compiled.Join> joins = new LinkedHashMap<>();
        for (Spec.JoinSpec j : spec.joins == null ? List.<Spec.JoinSpec>of() : spec.joins) {
            Optional<JoinPath> jp = catalog.joinPath(j.path);
            if (jp.isEmpty()) {
                c.error("joins", "Unknown join path " + j.path);
                continue;
            }
            if (!jp.get().from().equals(root.id())) {
                c.error("joins", "Join " + j.path + " does not start at the grain entity " + root.id());
                continue;
            }
            joins.put(jp.get().to(), new Compiled.Join(jp.get(), j.select));
        }
        for (String elementId : new ArrayList<>(c.elementsNeeded)) {
            Optional<Element> el = catalog.findElement(elementId);
            if (el.isEmpty()) continue;
            String entityId = el.get().entity();
            if (entityId.equals(root.id()) || joins.containsKey(entityId)) continue;
            Optional<JoinPath> jp = catalog.joinPath(root.id(), entityId);
            if (jp.isEmpty()) {
                c.error("joins", "Element " + elementId + " belongs to " + entityId + ", which the grain " + root.id() + " cannot reach. Change the grain or ask for a join path in the catalog.");
                continue;
            }
            String selector = null;
            if ("MANY".equals(jp.get().cardinality())) {
                if (jp.get().selectors() != null && !jp.get().selectors().isEmpty()) {
                    selector = jp.get().selectors().get(0).id();
                    c.warn("joins", entityId + " has many rows per " + root.id() + "; using \"" + jp.get().selectors().get(0).label() + "\". Change it on the Joins tab if that is not what the vendor wants.");
                } else {
                    c.error("joins", entityId + " has many rows per " + root.id() + " and no selector is available; change the grain to " + entityId);
                    continue;
                }
            }
            joins.put(entityId, new Compiled.Join(jp.get(), selector));
        }
        for (Compiled.Join j : joins.values()) {
            if ("MANY".equals(j.path().cardinality())) {
                boolean valid = j.selector() != null && j.path().selectors() != null && j.path().selectors().stream().anyMatch(s -> s.id().equals(j.selector()));
                if (!valid) c.error("joins", "Join " + j.path().id() + " needs a selector such as " + (j.path().selectors() == null || j.path().selectors().isEmpty() ? "?" : j.path().selectors().get(0).id()));
            }
        }
        c.joins.addAll(joins.values());

        // filters
        for (Spec.FilterSpec f : spec.filters == null ? List.<Spec.FilterSpec>of() : spec.filters) {
            if (f.template != null && !f.template.isBlank()) {
                try {
                    FilterTemplate t = catalog.template(f.template);
                    for (TemplateParam p : t.params() == null ? List.<TemplateParam>of() : t.params()) {
                        Object v = f.params == null ? null : f.params.get(p.name());
                        if (v == null && p.defaultValue() == null) c.error("filters", "Filter \"" + t.label() + "\" needs a value for " + p.label());
                    }
                    collectTemplateElements(t.clauses(), c);
                } catch (RuntimeException e) {
                    c.error("filters", e.getMessage());
                }
                continue;
            }
            if (f.element == null || f.element.isBlank()) {
                c.error("filters", "A filter has no element");
                continue;
            }
            Optional<Element> el = catalog.findElement(f.element);
            if (el.isEmpty()) {
                c.error("filters", "Unknown element in filter: " + f.element);
                continue;
            }
            if (!el.get().isFilterable()) c.warn("filters", el.get().name() + " is not marked filterable in the catalog; the filter will run but may be slow on the real database");
            String op = f.op == null ? "EQ" : f.op.toUpperCase();
            if (!FILTER_OPS.contains(op)) c.error("filters", "Unknown operator " + f.op + " on " + el.get().name());
            boolean needsValue = !op.equals("IS_NULL") && !op.equals("IS_NOT_NULL");
            if (needsValue && (f.value == null || (f.value instanceof String s && s.isBlank()) || (f.value instanceof List<?> l && l.isEmpty()))) {
                c.error("filters", "Filter on " + el.get().name() + " needs a value");
            }
            c.elementsNeeded.add(f.element);
        }

        // sort
        for (Spec.SortSpec s : spec.sort == null ? List.<Spec.SortSpec>of() : spec.sort) {
            if (catalog.findElement(s.element).isEmpty()) c.error("sort", "Unknown sort element " + s.element);
            else c.elementsNeeded.add(s.element);
        }

        // scope
        if (spec.scope != null && "INCREMENTAL".equals(spec.scope.mode)) {
            if (spec.scope.watermarkElements == null || spec.scope.watermarkElements.isEmpty()) {
                c.error("scope", "Incremental scope needs at least one watermark element");
            } else {
                for (String w : spec.scope.watermarkElements) {
                    Optional<Element> el = catalog.findElement(w);
                    if (el.isEmpty()) c.error("scope", "Unknown watermark element " + w);
                    else if (!el.get().isWatermark()) c.error("scope", el.get().name() + " is not marked as a watermark column in the catalog");
                    else c.elementsNeeded.add(w);
                }
            }
        }

        // file format
        if (spec.fileFormat != null && "FIXED_WIDTH".equals(spec.fileFormat.type)) {
            for (Spec.Field f : spec.fields) {
                if (f.width == null || f.width <= 0) c.error("field " + f.position, "Fixed-width layout: " + f.header + " needs a width");
            }
        }
        if (spec.fileFormat != null && "DELIMITED".equals(spec.fileFormat.type) && (spec.fileFormat.delimiter == null || spec.fileFormat.delimiter.isEmpty())) {
            c.error("fileFormat", "A delimited layout needs a delimiter");
        }

        // re-check entities referenced by rules (conditional, arithmetic, lookup keys) are reachable
        for (String elementId : new ArrayList<>(c.elementsNeeded)) {
            Optional<Element> el = catalog.findElement(elementId);
            if (el.isEmpty()) continue;
            String entityId = el.get().entity();
            if (!entityId.equals(root.id()) && !joins.containsKey(entityId)) {
                Optional<JoinPath> jp = catalog.joinPath(root.id(), entityId);
                if (jp.isPresent() && !"MANY".equals(jp.get().cardinality())) {
                    Compiled.Join j = new Compiled.Join(jp.get(), null);
                    joins.put(entityId, j);
                    c.joins.add(j);
                } else if (jp.isEmpty()) {
                    c.error("rules", "Element " + elementId + " used by a rule belongs to " + entityId + ", which the grain cannot reach");
                }
            }
        }

        c.sqlText = SqlText.render(catalog, def, spec, c);
        return c;
    }

    private void collectTemplateElements(List<Clause> clauses, Compiled c) {
        if (clauses == null) return;
        for (Clause cl : clauses) {
            if (cl.anyOf() != null) collectTemplateElements(cl.anyOf(), c);
            if (cl.element() != null) c.elementsNeeded.add(cl.element());
        }
    }

    private Compiled.Field compileField(Compiled c, Spec.Field f, String where) {
        List<Compiled.Input> inputs = new ArrayList<>();
        boolean phi = false;
        for (Spec.Input in : f.inputs == null ? List.<Spec.Input>of() : f.inputs) {
            if (in.element != null && !in.element.isBlank()) {
                Optional<Element> el = catalog.findElement(in.element);
                if (el.isEmpty()) {
                    c.error(where, "Unknown element " + in.element);
                    continue;
                }
                c.elementsNeeded.add(in.element);
                List<Compiled.RuleInstance> preRules = compileRules(c, in.rules, where + " input " + el.get().name(), el.get().type()).rules;
                boolean elPhi = el.get().isPhi();
                phi |= elPhi;
                if (el.get().isRestricted()) {
                    boolean masked = (f.rules != null && f.rules.stream().anyMatch(r -> "MASK".equals(r.type))) || (in.rules != null && in.rules.stream().anyMatch(r -> "MASK".equals(r.type)));
                    if (!masked) c.warn(where, el.get().name() + " is a restricted element. Sending it unmasked needs privacy sign-off; the approver will see this.");
                }
                inputs.add(new Compiled.Input(in.element, el.get(), null, preRules, elPhi));
            } else {
                inputs.add(new Compiled.Input(null, null, in.constant == null ? "" : in.constant, List.of(), false));
            }
        }
        Compiled.RuleInstance combiner = null;
        String currentType;
        if (inputs.size() > 1) {
            if (f.combiner == null || f.combiner.type == null) {
                c.error(where, "The field has " + inputs.size() + " inputs; choose how to combine them");
                currentType = "TEXT";
            } else {
                Optional<Rule> r = registry.find(f.combiner.type);
                if (r.isEmpty() || !r.get().isCombiner()) {
                    c.error(where, "Combiner must be CONCAT or COALESCE");
                    currentType = "TEXT";
                } else {
                    combiner = new Compiled.RuleInstance(r.get(), f.combiner.params == null ? Map.of() : f.combiner.params);
                    currentType = r.get().produces(combiner.params(), inputType(inputs.get(0)));
                }
            }
        } else if (inputs.size() == 1) {
            currentType = inputType(inputs.get(0));
            if (f.combiner != null && f.combiner.type != null && "CONCAT".equals(f.combiner.type)) {
                Optional<Rule> r = registry.find("CONCAT");
                combiner = new Compiled.RuleInstance(r.get(), f.combiner.params == null ? Map.of() : f.combiner.params);
                currentType = "TEXT";
            }
        } else {
            currentType = "NONE";
            boolean ok = f.rules != null && !f.rules.isEmpty() && ("CONSTANT".equals(f.rules.get(0).type) || "SEQUENCE".equals(f.rules.get(0).type));
            if (!ok) c.error(where, "A field without inputs must start with a Constant or Sequence rule");
        }
        ChainResult chain = compileRules(c, f.rules, where, currentType);
        if (chain.outputType.equals("DATE") || chain.outputType.equals("TIMESTAMP")) {
            c.warn(where, "The field is a date with no Format date rule; it will be written as ISO yyyy-MM-dd");
        }
        if (chain.outputType.equals("BOOLEAN")) {
            c.info(where, "The field is true/false; add Format number to write Y/N or 1/0");
        }
        return new Compiled.Field(f, inputs, combiner, chain.rules, chain.outputType, phi);
    }

    private String inputType(Compiled.Input in) {
        if (in.element() == null) return "TEXT";
        String t = in.element().type();
        if (!in.rules().isEmpty()) {
            String cur = t;
            for (Compiled.RuleInstance r : in.rules()) cur = r.rule().produces(r.params(), cur);
            return cur;
        }
        return t.equals("INTEGER") ? "DECIMAL" : t;
    }

    private record ChainResult(List<Compiled.RuleInstance> rules, String outputType) {}

    private ChainResult compileRules(Compiled c, List<Spec.RuleSpec> specs, String where, String startType) {
        List<Compiled.RuleInstance> out = new ArrayList<>();
        String type = startType.equals("INTEGER") ? "DECIMAL" : startType;
        for (Spec.RuleSpec rs : specs == null ? List.<Spec.RuleSpec>of() : specs) {
            Optional<Rule> r = registry.find(rs.type);
            if (r.isEmpty()) {
                c.error(where, "Unknown rule " + rs.type);
                continue;
            }
            Map<String, Object> params = rs.params == null ? Map.of() : rs.params;
            for (String problem : r.get().validate(params)) c.error(where, r.get().label() + ": " + problem);
            List<String> accepts = r.get().accepts();
            if (!accepts.contains("ANY") && !type.equals("NONE") && !accepts.contains(type) && !type.equals("NULL")) {
                if (type.equals("DECIMAL") && (accepts.contains("TEXT"))) {
                    c.info(where, r.get().label() + " will treat the number as text");
                } else {
                    c.error(where, r.get().label() + " cannot be applied to a " + type.toLowerCase() + " value");
                }
            }
            for (Param needed : r.get().params()) {
                if (needed.required() && (params.get(needed.name()) == null || params.get(needed.name()).toString().isBlank())) {
                    c.error(where, r.get().label() + " needs " + needed.label());
                }
            }
            collectReferencedElements(r.get(), params, c, where);
            type = r.get().produces(params, type);
            out.add(new Compiled.RuleInstance(r.get(), params));
        }
        return new ChainResult(out, type);
    }

    @SuppressWarnings("unchecked")
    private void collectReferencedElements(Rule r, Map<String, Object> params, Compiled c, String where) {
        for (Param p : r.params()) {
            if ("element".equals(p.kind())) {
                Object v = params.get(p.name());
                if (v != null && !v.toString().isBlank()) {
                    if (catalog.findElement(v.toString()).isEmpty()) c.error(where, "Unknown element " + v + " in " + r.label());
                    else c.elementsNeeded.add(v.toString());
                }
            }
            if ("lookup".equals(p.kind())) {
                Object v = params.get(p.name());
                if (v != null && !v.toString().isBlank()) {
                    try {
                        catalog.lookup(v.toString());
                        c.lookupsUsed.add(v.toString());
                    } catch (RuntimeException e) {
                        c.error(where, "Unknown lookup " + v);
                    }
                }
            }
            if ("conditions".equals(p.kind())) {
                Object v = params.get(p.name());
                if (v instanceof List<?> rules) {
                    for (Object ro : rules) {
                        if (!(ro instanceof Map<?, ?> rule)) continue;
                        Object conds = rule.get("conditions");
                        if (conds instanceof List<?> cl) {
                            for (Object co : cl) {
                                if (co instanceof Map<?, ?> cond) {
                                    Object cmp = cond.get("compare");
                                    if (cmp != null && !"CURRENT".equals(cmp.toString())) {
                                        if (catalog.findElement(cmp.toString()).isEmpty()) c.error(where, "Unknown element " + cmp + " in a condition");
                                        else c.elementsNeeded.add(cmp.toString());
                                    }
                                }
                            }
                        }
                        if ("ELEMENT".equals(String.valueOf(rule.get("thenKind"))) && rule.get("thenValue") != null) c.elementsNeeded.add(rule.get("thenValue").toString());
                    }
                }
                if ("ELEMENT".equals(Rule.str(params, "elseKind", "")) && params.get("elseValue") != null) c.elementsNeeded.add(params.get("elseValue").toString());
            }
        }
    }
}
