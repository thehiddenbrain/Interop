package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class MaskRule implements Rule {

    @Override public String type() { return "MASK"; }
    @Override public String label() { return "Mask"; }
    @Override public String description() { return "De-identifies the value. Placed by you when a vendor wants hashed IDs; also injected automatically on PHI fields in a masked sample."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("style", "Style", List.of("LAST4", "FIRST_N", "FIXED", "HASH", "FORMAT_PRESERVING", "DATE_SHIFT", "NULL"), "LAST4", "FORMAT_PRESERVING keeps length and character class, so widths still validate."),
                Param.integer("keep", "Characters to keep", 4, "").when("style=LAST4,FIRST_N"),
                Param.text("maskChar", "Mask character", "Default *.").when("style=LAST4,FIRST_N,FIXED"),
                Param.integer("hashLength", "Hash length", 16, "0 for the full SHA-256.").when("style=HASH"),
                Param.integer("shiftDays", "Shift within days", 180, "").when("style=DATE_SHIFT")
        );
    }

    @Override
    public String produces(Map<String, Object> params, String inputType) {
        return "DATE_SHIFT".equals(Rule.str(params, "style", "LAST4")) ? "DATE" : "TEXT";
    }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        if (in == null) return null;
        String style = Rule.str(params, "style", "LAST4");
        String mc = Rule.str(params, "maskChar", "*");
        char maskChar = mc.isEmpty() ? '*' : mc.charAt(0);
        switch (style) {
            case "NULL": return null;
            case "DATE_SHIFT": {
                try {
                    LocalDate d = Values.date(in, null);
                    return Masking.shiftDate(d, ctx.maskSalt, Rule.integer(params, "shiftDays", 180));
                } catch (RuntimeException e) {
                    return Masking.formatPreserving(Values.text(in), ctx.maskSalt);
                }
            }
            case "FORMAT_PRESERVING": return Masking.formatPreserving(Values.text(in), ctx.maskSalt);
            case "HASH": return Masking.hash(Values.text(in), ctx.maskSalt, Rule.integer(params, "hashLength", 16));
            case "FIXED": return Masking.fixed(Values.text(in), maskChar);
            case "FIRST_N": return Masking.firstN(Values.text(in), Rule.integer(params, "keep", 4), maskChar);
            default: return Masking.lastN(Values.text(in), Rule.integer(params, "keep", 4), maskChar);
        }
    }

    @Override public String summarize(Map<String, Object> params) {
        return Rule.str(params, "style", "LAST4").toLowerCase().replace('_', ' ');
    }
}
