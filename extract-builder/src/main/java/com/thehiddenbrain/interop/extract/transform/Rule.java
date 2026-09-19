package com.thehiddenbrain.interop.extract.transform;

import java.util.List;
import java.util.Map;

/**
 * One transformation type in the closed set. A rule is a pure function from inputs and params to a value.
 * The registry serves each rule's param schema to the browser, which renders the form from it, so adding a
 * rule is one Java class and no UI work. Type ids are never renamed or removed.
 */
public interface Rule {

    String type();

    String label();

    String description();

    /** VALUE for single-input rules, COMBINER for rules that take every input of the field. */
    default String category() { return "VALUE"; }

    default boolean isCombiner() { return "COMBINER".equals(category()); }

    List<Param> params();

    /** Types this rule accepts as input, or ANY. */
    default List<String> accepts() { return List.of("ANY"); }

    /** The type the rule produces given its params. */
    default String produces(Map<String, Object> params, String inputType) { return "TEXT"; }

    /** Apply the rule. For a VALUE rule inputs has one element; for a COMBINER, every input of the field. */
    Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx);

    /** Validate params; return a list of problems, empty when fine. */
    default List<String> validate(Map<String, Object> params) { return List.of(); }

    /** A short human summary of a configured instance, shown as a chip in the layout grid. */
    default String summarize(Map<String, Object> params) { return type(); }

    static String str(Map<String, Object> p, String key, String def) {
        Object v = p == null ? null : p.get(key);
        return v == null || v.toString().isEmpty() ? def : v.toString();
    }

    static int integer(Map<String, Object> p, String key, int def) {
        Object v = p == null ? null : p.get(key);
        if (v == null || v.toString().isBlank()) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean bool(Map<String, Object> p, String key, boolean def) {
        Object v = p == null ? null : p.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }
}
