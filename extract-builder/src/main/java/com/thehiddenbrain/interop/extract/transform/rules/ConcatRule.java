package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConcatRule implements Rule {

    private static final Pattern HOLE = Pattern.compile("\\{(\\d+)}");

    @Override public String type() { return "CONCAT"; }
    @Override public String label() { return "Combine text"; }
    @Override public String description() { return "Joins the inputs into one value. Use a template such as {1}, {2} {3}. for full control, or a separator. With one input it is the prefix and suffix tool."; }
    @Override public String category() { return "COMBINER"; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.text("template", "Template", "Placeholders {1}, {2} ... stand for the inputs in order. Leave blank to join with the separator. Example: ACM{1} or {1}, {2} {3}."),
                Param.text("separator", "Separator", "Used when the template is blank. Example: a single space."),
                Param.bool("skipBlank", "Skip blank inputs", true, "Blank inputs are dropped so no dangling separators or holes remain.")
        );
    }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        String template = Rule.str(params, "template", "");
        String sep = Rule.str(params, "separator", "");
        boolean skipBlank = Rule.bool(params, "skipBlank", true);
        List<String> parts = new ArrayList<>();
        for (Object in : inputs) parts.add(Values.text(in));
        if (template.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (skipBlank && (p == null || p.isBlank())) continue;
                if (sb.length() > 0) sb.append(sep);
                sb.append(p == null ? "" : p);
            }
            return sb.toString();
        }
        Matcher m = HOLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean anyFilled = false;
        while (m.find()) {
            sb.append(template, last, m.start());
            int idx = Integer.parseInt(m.group(1)) - 1;
            String p = idx >= 0 && idx < parts.size() ? parts.get(idx) : null;
            if (p != null && !p.isBlank()) {
                sb.append(p);
                anyFilled = true;
            } else if (skipBlank) {
                // drop the literal that immediately followed the previous hole for this blank part
                sb.setLength(trimTrailingSeparator(sb));
            }
            last = m.end();
        }
        sb.append(template.substring(last));
        String out = sb.toString();
        if (skipBlank) out = out.replaceAll("\\s{2,}", " ").replaceAll("(\\s|,)+$", "").replaceAll("^(\\s|,)+", "");
        if (!anyFilled && skipBlank && parts.stream().allMatch(p -> p == null || p.isBlank())) return "";
        return out;
    }

    private static int trimTrailingSeparator(StringBuilder sb) {
        int len = sb.length();
        while (len > 0 && (sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == ',')) len--;
        return len;
    }

    @Override
    public String summarize(Map<String, Object> params) {
        String template = Rule.str(params, "template", "");
        if (!template.isEmpty()) return "\"" + template + "\"";
        String sep = Rule.str(params, "separator", "");
        return "join with \"" + sep + "\"";
    }
}
