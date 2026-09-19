package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class MapValuesRule implements Rule {

    @Override public String type() { return "MAP_VALUES"; }
    @Override public String label() { return "Map codes"; }
    @Override public String description() { return "Translates codes through a grid you type in: M to 1, F to 2. Ranges cover bands such as age 0 to 17 equals C."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.of("map", "Code map", "map", "Source value on the left, output on the right."),
                Param.of("ranges", "Ranges", "ranges", "For numbers and dates: from (inclusive), to (exclusive), output."),
                Param.text("defaultValue", "Otherwise", "Output when nothing matches. Leave blank for empty."),
                Param.bool("passthroughUnmapped", "Keep unmapped values as they are", false, "Overrides 'Otherwise' when on."),
                Param.bool("caseInsensitive", "Ignore case", true, "m and M both match.")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        boolean ci = Rule.bool(params, "caseInsensitive", true);
        Object mapObj = params.get("map");
        if (mapObj instanceof Map<?, ?> map) {
            String key = Values.text(in);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (key == null) {
                    if (k.isEmpty() || k.equalsIgnoreCase("null")) return e.getValue();
                    continue;
                }
                if (ci ? k.equalsIgnoreCase(key) : k.equals(key)) return e.getValue();
            }
        }
        Object rangesObj = params.get("ranges");
        if (rangesObj instanceof List<?> ranges && in != null) {
            for (Object r : ranges) {
                if (!(r instanceof Map<?, ?> range)) continue;
                Object from = range.get("from");
                Object to = range.get("to");
                boolean geFrom = from == null || String.valueOf(from).isEmpty() || Values.compare(in, coerceLike(in, from)) >= 0;
                boolean ltTo = to == null || String.valueOf(to).isEmpty() || Values.compare(in, coerceLike(in, to)) < 0;
                if (geFrom && ltTo) return range.get("value");
            }
        }
        if (Rule.bool(params, "passthroughUnmapped", false)) return in;
        String def = Rule.str(params, "defaultValue", "");
        return def.isEmpty() ? null : def;
    }

    private static Object coerceLike(Object sample, Object bound) {
        try {
            if (sample instanceof BigDecimal) return Values.decimal(bound);
            if (sample instanceof java.time.LocalDate) return Values.date(bound, null);
        } catch (RuntimeException ignored) {
        }
        return bound;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String summarize(Map<String, Object> params) {
        Object mapObj = params.get("map");
        StringBuilder sb = new StringBuilder();
        if (mapObj instanceof Map<?, ?> map) {
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (i++ == 3) { sb.append(" …"); break; }
                if (sb.length() > 0) sb.append(" · ");
                sb.append(e.getKey()).append("→").append(e.getValue());
            }
        }
        Object ranges = params.get("ranges");
        if (ranges instanceof List<?> l && !l.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(l.size()).append(" ranges");
        String def = Rule.str(params, "defaultValue", "");
        if (!def.isEmpty()) sb.append(" · else ").append(def);
        return sb.length() == 0 ? "map codes" : sb.toString();
    }
}
