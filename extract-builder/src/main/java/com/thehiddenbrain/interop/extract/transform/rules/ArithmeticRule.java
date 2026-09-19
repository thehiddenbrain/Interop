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
public class ArithmeticRule implements Rule {

    @Override public String type() { return "ARITHMETIC"; }
    @Override public String label() { return "Arithmetic"; }
    @Override public String description() { return "Adds, subtracts, multiplies or divides the value by another element or a constant. Patient responsibility equals allowed minus paid."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("op", "Operation", List.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "PERCENT_OF", "NEGATE", "ABS"), "SUBTRACT", ""),
                Param.choice("operandKind", "Other operand", List.of("ELEMENT", "CONSTANT"), "ELEMENT", "").when("op=ADD,SUBTRACT,MULTIPLY,DIVIDE,PERCENT_OF"),
                Param.of("operandElement", "Element", "element", "").when("operandKind=ELEMENT"),
                Param.text("operandValue", "Constant", "A number such as 100.").when("operandKind=CONSTANT"),
                Param.integer("scale", "Decimal places", 2, "Rounding applied to the result."),
                Param.choice("divideByZero", "On divide by zero", List.of("BLANK", "ZERO", "FAIL"), "BLANK", "").when("op=DIVIDE,PERCENT_OF")
        );
    }

    @Override public List<String> accepts() { return List.of("DECIMAL", "TEXT"); }
    @Override public String produces(Map<String, Object> params, String inputType) { return "DECIMAL"; }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        BigDecimal a;
        try {
            a = Values.decimal(in);
        } catch (IllegalArgumentException e) {
            ctx.warn("NOT_A_NUMBER", "Not a number: " + Values.text(in));
            return null;
        }
        if (a == null) a = BigDecimal.ZERO;
        String op = Rule.str(params, "op", "SUBTRACT");
        int scale = Rule.integer(params, "scale", 2);
        if (op.equals("NEGATE")) return a.negate().setScale(scale, RoundingMode.HALF_UP);
        if (op.equals("ABS")) return a.abs().setScale(scale, RoundingMode.HALF_UP);
        BigDecimal b;
        if ("CONSTANT".equals(Rule.str(params, "operandKind", "ELEMENT"))) {
            b = Values.decimal(Rule.str(params, "operandValue", "0"));
        } else {
            Object other = ctx.row.get(Rule.str(params, "operandElement", ""));
            try {
                b = Values.decimal(other);
            } catch (IllegalArgumentException e) {
                ctx.warn("NOT_A_NUMBER", "Not a number: " + Values.text(other));
                return null;
            }
        }
        if (b == null) b = BigDecimal.ZERO;
        switch (op) {
            case "ADD": return a.add(b).setScale(scale, RoundingMode.HALF_UP);
            case "MULTIPLY": return a.multiply(b).setScale(scale, RoundingMode.HALF_UP);
            case "DIVIDE":
            case "PERCENT_OF": {
                if (b.signum() == 0) {
                    String dz = Rule.str(params, "divideByZero", "BLANK");
                    if (dz.equals("FAIL")) throw new LookupRule.PipelineFailure("Division by zero in field " + ctx.currentFieldId);
                    return dz.equals("ZERO") ? BigDecimal.ZERO.setScale(scale) : null;
                }
                BigDecimal q = a.divide(b, scale + 4, RoundingMode.HALF_UP);
                if (op.equals("PERCENT_OF")) q = q.multiply(BigDecimal.valueOf(100));
                return q.setScale(scale, RoundingMode.HALF_UP);
            }
            default: return a.subtract(b).setScale(scale, RoundingMode.HALF_UP);
        }
    }

    @Override public String summarize(Map<String, Object> params) {
        String op = Rule.str(params, "op", "SUBTRACT");
        String operand = "CONSTANT".equals(Rule.str(params, "operandKind", "ELEMENT")) ? Rule.str(params, "operandValue", "0") : Rule.str(params, "operandElement", "?").replaceAll("^[^.]+\\.", "");
        return op.equals("NEGATE") || op.equals("ABS") ? op.toLowerCase() : op.toLowerCase().replace('_', ' ') + " " + operand;
    }
}
