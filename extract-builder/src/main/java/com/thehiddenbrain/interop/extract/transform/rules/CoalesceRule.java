package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CoalesceRule implements Rule {

    @Override public String type() { return "COALESCE"; }
    @Override public String label() { return "First non-blank"; }
    @Override public String description() { return "Takes the first input that has a value. Mobile phone, else home phone."; }
    @Override public String category() { return "COMBINER"; }

    @Override
    public List<Param> params() {
        return List.of(Param.bool("treatBlankAsNull", "Treat blank text as empty", true, "A value of only spaces counts as missing."));
    }

    @Override
    public String produces(Map<String, Object> params, String inputType) { return inputType == null ? "TEXT" : inputType; }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        boolean blankIsNull = Rule.bool(params, "treatBlankAsNull", true);
        for (Object in : inputs) {
            if (in == null) continue;
            if (blankIsNull && Values.isBlank(in)) continue;
            return in;
        }
        return null;
    }

    @Override public String summarize(Map<String, Object> params) { return "first non-blank"; }
}
