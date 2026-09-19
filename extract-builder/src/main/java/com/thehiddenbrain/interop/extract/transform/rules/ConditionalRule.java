package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * An if / else-if / else ladder built from dropdowns. Each rule has one or more conditions (ALL or ANY must hold)
 * and an outcome: a constant, keep the current value, or another element of the row.
 */
@Component
public class ConditionalRule implements Rule {

    @Override public String type() { return "CONDITIONAL"; }
    @Override public String label() { return "If / else"; }
    @Override public String description() { return "If another element compares a certain way, output one value, else another. Several rules make an else-if ladder. No expressions."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.of("rules", "Rules, first match wins", "conditions", "Each rule: conditions on the current value or another element, and what to output."),
                Param.choice("elseKind", "Otherwise", List.of("KEEP", "CONSTANT", "ELEMENT", "BLANK"), "KEEP", "What to output when no rule matches."),
                Param.text("elseValue", "Otherwise value", "Constant text, or an element id when 'Otherwise' is ELEMENT.").when("elseKind=CONSTANT,ELEMENT")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object current = inputs.isEmpty() ? null : inputs.get(0);
        Object rulesObj = params.get("rules");
        if (rulesObj instanceof List<?> rules) {
            for (Object ro : rules) {
                if (!(ro instanceof Map<?, ?> rule)) continue;
                boolean any = "ANY".equalsIgnoreCase(str(rule.get("match"), "ALL"));
                Object condsObj = rule.get("conditions");
                boolean matched = !any;
                if (condsObj instanceof List<?> conds && !conds.isEmpty()) {
                    matched = !any;
                    for (Object co : conds) {
                        if (!(co instanceof Map<?, ?> cond)) continue;
                        boolean ok = test(cond, current, ctx);
                        if (any && ok) { matched = true; break; }
                        if (!any && !ok) { matched = false; break; }
                    }
                }
                if (matched) return outcome(str(rule.get("thenKind"), "CONSTANT"), rule.get("thenValue"), current, ctx);
            }
        }
        return outcome(Rule.str(params, "elseKind", "KEEP"), params.get("elseValue"), current, ctx);
    }

    private static String str(Object v, String def) {
        return v == null || v.toString().isEmpty() ? def : v.toString();
    }

    private Object outcome(String kind, Object value, Object current, RuleContext ctx) {
        switch (kind.toUpperCase()) {
            case "KEEP": return current;
            case "BLANK": return null;
            case "ELEMENT": return ctx.row.get(String.valueOf(value));
            default: return value == null ? null : value;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean test(Map<?, ?> cond, Object current, RuleContext ctx) {
        String compare = str(cond.get("compare"), "CURRENT");
        Object left = "CURRENT".equalsIgnoreCase(compare) ? current : ctx.row.get(compare);
        String op = str(cond.get("op"), "EQ").toUpperCase();
        Object valuesObj = cond.get("values");
        List<Object> values = valuesObj instanceof List<?> l ? (List<Object>) l : valuesObj == null ? List.of() : List.of(valuesObj);
        Object first = values.isEmpty() ? null : values.get(0);
        switch (op) {
            case "EQ": return Values.equalsLoose(left, first);
            case "NE": return !Values.equalsLoose(left, first);
            case "IN": return values.stream().anyMatch(v -> Values.equalsLoose(left, v));
            case "NOT_IN": return values.stream().noneMatch(v -> Values.equalsLoose(left, v));
            case "BLANK": return Values.isBlank(left);
            case "NOT_BLANK": return !Values.isBlank(left);
            case "GT": return left != null && first != null && Values.compare(left, first) > 0;
            case "GTE": return left != null && first != null && Values.compare(left, first) >= 0;
            case "LT": return left != null && first != null && Values.compare(left, first) < 0;
            case "LTE": return left != null && first != null && Values.compare(left, first) <= 0;
            case "BETWEEN": return left != null && values.size() >= 2 && Values.compare(left, values.get(0)) >= 0 && Values.compare(left, values.get(1)) <= 0;
            case "STARTS_WITH": return left != null && first != null && Values.text(left).toUpperCase().startsWith(Values.text(first).toUpperCase());
            case "CONTAINS": return left != null && first != null && Values.text(left).toUpperCase().contains(Values.text(first).toUpperCase());
            default: return false;
        }
    }

    @Override
    public String summarize(Map<String, Object> params) {
        Object rulesObj = params.get("rules");
        int n = rulesObj instanceof List<?> l ? l.size() : 0;
        return n + (n == 1 ? " rule" : " rules") + " · else " + Rule.str(params, "elseKind", "KEEP").toLowerCase();
    }
}
