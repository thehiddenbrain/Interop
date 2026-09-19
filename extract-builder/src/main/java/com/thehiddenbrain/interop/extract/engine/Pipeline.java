package com.thehiddenbrain.interop.extract.engine;

import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import com.thehiddenbrain.interop.extract.transform.rules.LookupRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per row, per output field: gather inputs (masked when the run says so), shape each input with its own rules,
 * combine, apply the field's rules, apply the field's default, max length and overflow policy, then render to text.
 * Pure Java, no I/O; the same object serves preview, sample and production.
 */
public class Pipeline {

    public static class FieldStats {
        public int nulls;
        public int truncated;
        public int rows;
        public java.math.BigDecimal sum;
        public int maxLength;
    }

    private final Compiled compiled;
    private final RuleContext ctx;
    private final Map<String, FieldStats> stats = new LinkedHashMap<>();
    private final List<String> maskedFields = new ArrayList<>();

    public Pipeline(Compiled compiled, RuleContext ctx) {
        this.compiled = compiled;
        this.ctx = ctx;
        for (Compiled.Field f : compiled.fields) {
            stats.put(f.id(), new FieldStats());
            if (ctx.masking && f.phi()) maskedFields.add(f.header());
        }
    }

    public Map<String, FieldStats> stats() { return stats; }
    public List<String> maskedFields() { return maskedFields; }
    public RuleContext context() { return ctx; }

    /** Produce one output record (one text per field) for a source row keyed by element id. */
    public String[] apply(long rowNumber, Map<String, Object> row) {
        ctx.resetRow(rowNumber, row);
        String[] out = new String[compiled.fields.size()];
        int i = 0;
        for (Compiled.Field f : compiled.fields) {
            ctx.currentFieldId = f.id();
            Object value = evaluate(f, row);
            String text = render(f, value);
            FieldStats st = stats.get(f.id());
            st.rows++;
            if (text == null || text.isEmpty()) st.nulls++;
            else {
                st.maxLength = Math.max(st.maxLength, text.length());
                if (value instanceof java.math.BigDecimal b) st.sum = st.sum == null ? b : st.sum.add(b);
            }
            out[i++] = text;
        }
        return out;
    }

    private Object evaluate(Compiled.Field f, Map<String, Object> row) {
        List<Object> inputs = new ArrayList<>(f.inputs().size());
        for (Compiled.Input in : f.inputs()) {
            Object v;
            if (in.elementId() != null) {
                v = row.get(in.elementId());
                if (ctx.masking && in.phi()) v = ctx.mask(v);
            } else {
                v = in.constant();
            }
            for (Compiled.RuleInstance r : in.rules()) v = r.rule().apply(one(v), r.params(), ctx);
            inputs.add(v);
        }
        Object current;
        if (f.combiner() != null) {
            current = f.combiner().rule().apply(inputs, f.combiner().params(), ctx);
        } else {
            current = inputs.isEmpty() ? null : inputs.get(0);
        }
        for (Compiled.RuleInstance r : f.rules()) current = r.rule().apply(one(current), r.params(), ctx);
        return current;
    }

    private static List<Object> one(Object v) {
        List<Object> l = new ArrayList<>(1);
        l.add(v);
        return l;
    }

    private String render(Compiled.Field f, Object value) {
        Spec.Field spec = f.spec();
        String text = Values.text(value);
        if ((text == null || text.isEmpty()) && spec.defaultValue != null && !spec.defaultValue.isEmpty()) {
            text = spec.defaultValue;
            if (value == null) {
                // a typed default such as 9999-12-31 should still pass through a trailing FORMAT_DATE; re-run the last date rule if present
                for (Compiled.RuleInstance r : f.rules()) {
                    if (r.rule().type().equals("FORMAT_DATE")) {
                        try {
                            Object formatted = r.rule().apply(one(Values.date(spec.defaultValue, null)), r.params(), ctx);
                            text = Values.text(formatted);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
        }
        if (text == null) return null;
        if (spec.maxLength != null && spec.maxLength > 0 && text.length() > spec.maxLength) {
            if ("FAIL".equals(spec.onOverflow)) throw new LookupRule.PipelineFailure("Field " + spec.header + " is longer than " + spec.maxLength + " characters: " + text);
            text = text.substring(0, spec.maxLength);
            stats.get(f.id()).truncated++;
            ctx.warn("TRUNCATED", "Value cut to " + spec.maxLength + " characters");
        }
        return text;
    }
}
