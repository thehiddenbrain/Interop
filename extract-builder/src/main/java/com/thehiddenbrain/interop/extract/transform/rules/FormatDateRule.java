package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class FormatDateRule implements Rule {

    public static final List<String> PATTERNS = List.of("yyyyMMdd", "MM/dd/yyyy", "yyyy-MM-dd", "MMddyyyy", "ddMMyyyy", "MM-dd-yyyy", "yyyyMMddHHmmss", "yyyy-MM-dd'T'HH:mm:ss", "MMM d, yyyy", "CUSTOM");

    @Override public String type() { return "FORMAT_DATE"; }
    @Override public String label() { return "Format a date"; }
    @Override public String description() { return "Renders a date in the vendor's pattern. Pick from the list; a custom pattern needs admin approval."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("pattern", "Pattern", PATTERNS, "yyyyMMdd", "yyyyMMdd gives 19850307, MM/dd/yyyy gives 03/07/1985."),
                Param.text("customPattern", "Custom pattern", "A Java date pattern, for example dd.MM.yyyy.").when("pattern=CUSTOM"),
                Param.text("inputPattern", "Input pattern", "Only when the source is text in a known layout, for example MMddyyyy."),
                Param.choice("onInvalid", "If the value is not a date", List.of("BLANK", "FAIL", "PASSTHROUGH"), "BLANK", "")
        );
    }

    @Override public List<String> accepts() { return List.of("DATE", "TIMESTAMP", "TEXT"); }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        if (in == null) return null;
        String pattern = Rule.str(params, "pattern", "yyyyMMdd");
        if (pattern.equals("CUSTOM")) pattern = Rule.str(params, "customPattern", "yyyyMMdd");
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern);
            if (in instanceof LocalDateTime t) return t.format(f);
            if (pattern.contains("H") || pattern.contains("m") && pattern.contains("s")) {
                return Values.timestamp(in).format(f);
            }
            LocalDate d = Values.date(in, Rule.str(params, "inputPattern", ""));
            return d.format(f);
        } catch (RuntimeException e) {
            String onInvalid = Rule.str(params, "onInvalid", "BLANK");
            ctx.warn("INVALID_DATE", "Not a date: " + Values.text(in));
            if (onInvalid.equals("FAIL")) throw new LookupRule.PipelineFailure("Not a date: " + Values.text(in));
            return onInvalid.equals("PASSTHROUGH") ? Values.text(in) : null;
        }
    }

    @Override public String summarize(Map<String, Object> params) {
        String p = Rule.str(params, "pattern", "yyyyMMdd");
        return p.equals("CUSTOM") ? Rule.str(params, "customPattern", "custom") : p;
    }
}
