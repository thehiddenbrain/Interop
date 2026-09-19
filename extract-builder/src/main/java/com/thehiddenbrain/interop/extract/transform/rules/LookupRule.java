package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LookupRule implements Rule {

    @Override public String type() { return "LOOKUP"; }
    @Override public String label() { return "Look up in a table"; }
    @Override public String description() { return "Replaces the value with one from a registered lookup: the vendor's member ID from a crosswalk, a taxonomy description, a plan name."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.required("lookup", "Lookup table", "lookup", "A lookup registered in the catalog."),
                Param.text("returnColumn", "Column to return", "Defaults to the lookup's default column."),
                Param.of("extraKeyElement", "Second key element", "element", "For composite keys: another element of the row that forms the key."),
                Param.of("asOfElement", "As-of date element", "element", "For effective-dated tables: the row date to pick the right version."),
                Param.choice("onMiss", "When there is no match", List.of("BLANK", "PASSTHROUGH", "CONSTANT", "FAIL"), "BLANK", "FAIL stops the run, which is the safest choice for identifiers."),
                Param.text("missValue", "Value on miss", "Used when 'When there is no match' is CONSTANT.").when("onMiss=CONSTANT")
        );
    }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object given = inputs.isEmpty() ? null : inputs.get(0);
        boolean maskedInput = ctx.wasMasked(given);
        Object key = ctx.original(given);
        String lookupId = Rule.str(params, "lookup", "");
        RuleContext.LookupTable table = ctx.lookups.apply(lookupId);
        if (table == null) throw new IllegalStateException("Lookup not loaded: " + lookupId);
        List<Object> keys = new ArrayList<>();
        keys.add(key);
        String extra = Rule.str(params, "extraKeyElement", "");
        if (!extra.isEmpty()) keys.add(ctx.row.get(extra));
        LocalDate asOf = null;
        String asOfElement = Rule.str(params, "asOfElement", "");
        if (!asOfElement.isEmpty()) asOf = Values.date(ctx.row.get(asOfElement), null);
        Map<String, Object> hit = key == null ? null : table.find(keys, asOf);
        if (hit == null) {
            String onMiss = Rule.str(params, "onMiss", "BLANK");
            ctx.warn("LOOKUP_MISS", "No match in " + lookupId + " for " + Values.text(given));
            switch (onMiss) {
                case "PASSTHROUGH": return given;
                case "CONSTANT": return Rule.str(params, "missValue", "");
                case "FAIL": throw new PipelineFailure("Lookup " + lookupId + " has no entry for " + Values.text(given) + " and the rule is set to fail on a miss");
                default: return null;
            }
        }
        String col = Rule.str(params, "returnColumn", "");
        if (col.isEmpty()) col = String.valueOf(hit.getOrDefault("__default", ""));
        Object value = hit.get(col);
        if (value != null && maskedInput && ctx.masking) return ctx.mask(value);
        return value;
    }

    @Override
    public String summarize(Map<String, Object> params) {
        return Rule.str(params, "lookup", "?") + " · miss " + Rule.str(params, "onMiss", "BLANK").toLowerCase();
    }

    /** Thrown when a rule decides the run must stop. */
    public static class PipelineFailure extends RuntimeException {
        public PipelineFailure(String message) { super(message); }
    }
}
