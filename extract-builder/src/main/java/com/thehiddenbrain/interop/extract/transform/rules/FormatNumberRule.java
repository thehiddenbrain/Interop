package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Component
public class FormatNumberRule implements Rule {

    @Override public String type() { return "FORMAT_NUMBER"; }
    @Override public String label() { return "Format a number"; }
    @Override public String description() { return "Decimals, implied decimal for fixed-width vendors, sign style, leading zeros, and Y/N or 1/0 for booleans."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.integer("decimals", "Decimal places", 2, ""),
                Param.bool("impliedDecimal", "Implied decimal", false, "123.40 becomes 12340."),
                Param.bool("thousands", "Thousands separator", false, ""),
                Param.choice("negativeStyle", "Negative numbers", List.of("MINUS", "PARENS", "TRAILING_MINUS"), "MINUS", ""),
                Param.integer("leadingZeros", "Pad with zeros to width", 0, "0 for none."),
                Param.choice("booleanStyle", "Booleans as", List.of("YN", "10", "TF", "TRUEFALSE"), "YN", "Only when the input is true/false.")
        );
    }

    @Override public List<String> accepts() { return List.of("DECIMAL", "BOOLEAN", "TEXT"); }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        if (in == null) return null;
        if (in instanceof Boolean b) {
            switch (Rule.str(params, "booleanStyle", "YN")) {
                case "10": return b ? "1" : "0";
                case "TF": return b ? "T" : "F";
                case "TRUEFALSE": return b ? "TRUE" : "FALSE";
                default: return b ? "Y" : "N";
            }
        }
        BigDecimal n;
        try {
            n = Values.decimal(in);
        } catch (IllegalArgumentException e) {
            ctx.warn("NOT_A_NUMBER", "Not a number: " + Values.text(in));
            return Values.text(in);
        }
        if (n == null) return null;
        int decimals = Rule.integer(params, "decimals", 2);
        boolean implied = Rule.bool(params, "impliedDecimal", false);
        boolean negative = n.signum() < 0;
        BigDecimal abs = n.abs().setScale(decimals, RoundingMode.HALF_UP);
        String body;
        if (implied) {
            body = abs.movePointRight(decimals).setScale(0, RoundingMode.HALF_UP).toPlainString();
        } else if (Rule.bool(params, "thousands", false)) {
            body = String.format("%,." + decimals + "f", abs);
        } else {
            body = abs.toPlainString();
        }
        int width = Rule.integer(params, "leadingZeros", 0);
        if (width > body.length()) body = "0".repeat(width - body.length()) + body;
        if (!negative) return body;
        switch (Rule.str(params, "negativeStyle", "MINUS")) {
            case "PARENS": return "(" + body + ")";
            case "TRAILING_MINUS": return body + "-";
            default: return "-" + body;
        }
    }

    @Override public String summarize(Map<String, Object> params) {
        int d = Rule.integer(params, "decimals", 2);
        return (Rule.bool(params, "impliedDecimal", false) ? "implied " : "") + d + " decimals";
    }
}
