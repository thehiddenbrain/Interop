package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SequenceRule implements Rule {

    @Override public String type() { return "SEQUENCE"; }
    @Override public String label() { return "Sequence number"; }
    @Override public String description() { return "A running number within the file, or within a group such as dependents under a subscriber. The group element must lead the sort order."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("scope", "Restart", List.of("FILE", "GROUP"), "FILE", "GROUP restarts when the group element changes."),
                Param.of("groupElement", "Group element", "element", "").when("scope=GROUP"),
                Param.integer("start", "Start at", 1, ""),
                Param.integer("step", "Step", 1, ""),
                Param.integer("padWidth", "Pad with zeros to width", 0, "0 for none.")
        );
    }

    @Override public List<String> accepts() { return List.of("ANY", "NONE"); }
    @Override public String produces(Map<String, Object> params, String inputType) { return "TEXT"; }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        String scope = Rule.str(params, "scope", "FILE");
        Object group = scope.equals("GROUP") ? Values.text(ctx.row.get(Rule.str(params, "groupElement", ""))) : "__file__";
        long n = ctx.nextSequence(ctx.currentFieldId, group, Rule.integer(params, "start", 1), Rule.integer(params, "step", 1));
        String s = Long.toString(n);
        int pad = Rule.integer(params, "padWidth", 0);
        return pad > s.length() ? "0".repeat(pad - s.length()) + s : s;
    }

    @Override public String summarize(Map<String, Object> params) {
        return "GROUP".equals(Rule.str(params, "scope", "FILE")) ? "within " + Rule.str(params, "groupElement", "?").replaceAll("^[^.]+\\.", "") : "file sequence";
    }
}
