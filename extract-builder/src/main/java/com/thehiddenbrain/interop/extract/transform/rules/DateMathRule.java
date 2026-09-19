package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Component
public class DateMathRule implements Rule {

    @Override public String type() { return "DATE_MATH"; }
    @Override public String label() { return "Date arithmetic"; }
    @Override public String description() { return "Adds days or months, moves to the start or end of the month, or computes an age or a number of days between two dates."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("op", "Operation", List.of("ADD_DAYS", "ADD_MONTHS", "START_OF_MONTH", "END_OF_MONTH", "AGE_AT", "DAYS_BETWEEN"), "ADD_DAYS", "AGE_AT and DAYS_BETWEEN produce a number."),
                Param.integer("n", "Amount", 1, "Days or months to add; negative subtracts.").when("op=ADD_DAYS,ADD_MONTHS"),
                Param.choice("relativeTo", "Relative to", List.of("RUN_DATE", "ELEMENT", "CONSTANT"), "RUN_DATE", "The second date for AGE_AT and DAYS_BETWEEN.").when("op=AGE_AT,DAYS_BETWEEN"),
                Param.of("relativeElement", "Element", "element", "").when("relativeTo=ELEMENT"),
                Param.text("relativeDate", "Date", "ISO date such as 2026-12-31.").when("relativeTo=CONSTANT")
        );
    }

    @Override public List<String> accepts() { return List.of("DATE", "TIMESTAMP", "TEXT"); }

    @Override
    public String produces(Map<String, Object> params, String inputType) {
        String op = Rule.str(params, "op", "ADD_DAYS");
        return op.equals("AGE_AT") || op.equals("DAYS_BETWEEN") ? "DECIMAL" : "DATE";
    }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        if (in == null) return null;
        LocalDate d;
        try {
            d = Values.date(in, null);
        } catch (RuntimeException e) {
            ctx.warn("INVALID_DATE", "Not a date: " + Values.text(in));
            return null;
        }
        String op = Rule.str(params, "op", "ADD_DAYS");
        int n = Rule.integer(params, "n", 1);
        switch (op) {
            case "ADD_DAYS": return d.plusDays(n);
            case "ADD_MONTHS": return d.plusMonths(n);
            case "START_OF_MONTH": return d.withDayOfMonth(1);
            case "END_OF_MONTH": return d.withDayOfMonth(d.lengthOfMonth());
            case "AGE_AT": {
                LocalDate other = other(params, ctx);
                return other == null ? null : BigDecimal.valueOf(ChronoUnit.YEARS.between(d, other));
            }
            case "DAYS_BETWEEN": {
                LocalDate other = other(params, ctx);
                return other == null ? null : BigDecimal.valueOf(ChronoUnit.DAYS.between(d, other));
            }
            default: return d;
        }
    }

    private LocalDate other(Map<String, Object> params, RuleContext ctx) {
        String rel = Rule.str(params, "relativeTo", "RUN_DATE");
        switch (rel) {
            case "ELEMENT": return Values.date(ctx.row.get(Rule.str(params, "relativeElement", "")), null);
            case "CONSTANT": return Values.date(Rule.str(params, "relativeDate", ""), null);
            default: return ctx.runDate;
        }
    }

    @Override public String summarize(Map<String, Object> params) {
        String op = Rule.str(params, "op", "ADD_DAYS");
        if (op.startsWith("ADD")) return op.toLowerCase().replace('_', ' ') + " " + Rule.integer(params, "n", 1);
        return op.toLowerCase().replace('_', ' ');
    }
}
