package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class ConstantRule implements Rule {

    @Override public String type() { return "CONSTANT"; }
    @Override public String label() { return "Constant or run token"; }
    @Override public String description() { return "A fixed value on every row, or a token such as the run date, vendor code or file sequence. Needs no source column."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("token", "Value comes from", List.of("LITERAL", "RUN_DATE", "BUSINESS_DATE", "VENDOR_CODE", "FILE_SEQ", "VERSION", "ROW_NUMBER"), "LITERAL", "A literal you type, or a token filled in at run time."),
                Param.text("value", "Value", "The literal text.").when("token=LITERAL"),
                Param.choice("valueType", "Treat as", List.of("TEXT", "NUMBER", "DATE"), "TEXT", "How later rules see the literal.").when("token=LITERAL"),
                Param.choice("pattern", "Date pattern", List.of("yyyyMMdd", "MM/dd/yyyy", "yyyy-MM-dd", "MMddyyyy", "ddMMyyyy"), "yyyyMMdd", "How a date token is rendered.").when("token=RUN_DATE,BUSINESS_DATE"),
                Param.integer("padWidth", "Pad to width", 0, "Zero-pad numeric tokens to this width; 0 for none.").when("token=FILE_SEQ,ROW_NUMBER,VERSION")
        );
    }

    @Override public List<String> accepts() { return List.of("ANY", "NONE"); }

    @Override
    public String produces(Map<String, Object> params, String inputType) {
        String token = Rule.str(params, "token", "LITERAL");
        if (token.equals("LITERAL")) {
            String vt = Rule.str(params, "valueType", "TEXT");
            return vt.equals("NUMBER") ? "DECIMAL" : vt.equals("DATE") ? "DATE" : "TEXT";
        }
        return "TEXT";
    }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        String token = Rule.str(params, "token", "LITERAL");
        String pattern = Rule.str(params, "pattern", "yyyyMMdd");
        int pad = Rule.integer(params, "padWidth", 0);
        switch (token) {
            case "RUN_DATE": return ctx.runDate.format(DateTimeFormatter.ofPattern(pattern));
            case "BUSINESS_DATE": return ctx.businessDate.format(DateTimeFormatter.ofPattern(pattern));
            case "VENDOR_CODE": return ctx.vendorCode;
            case "FILE_SEQ": return pad(ctx.fileSeq, pad);
            case "VERSION": return pad(ctx.versionNo, pad);
            case "ROW_NUMBER": return pad(ctx.rowNumber, pad);
            default: {
                String value = Rule.str(params, "value", "");
                String vt = Rule.str(params, "valueType", "TEXT");
                if (vt.equals("NUMBER")) return value.isEmpty() ? null : new BigDecimal(value);
                if (vt.equals("DATE")) return value.isEmpty() ? null : Values.date(value, null);
                return value;
            }
        }
    }

    private static String pad(long n, int width) {
        String s = Long.toString(n);
        return width > s.length() ? "0".repeat(width - s.length()) + s : s;
    }

    @Override
    public String summarize(Map<String, Object> params) {
        String token = Rule.str(params, "token", "LITERAL");
        return token.equals("LITERAL") ? "\"" + Rule.str(params, "value", "") + "\"" : token.toLowerCase().replace('_', ' ');
    }
}
