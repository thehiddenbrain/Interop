package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SubstringRule implements Rule {

    @Override public String type() { return "SUBSTRING"; }
    @Override public String label() { return "Part of the text"; }
    @Override public String description() { return "A slice by position (1-based, like a spreadsheet), or the n-th token of a delimited value. ZIP 9 to ZIP 5, or the last name from SMITH,JOHN."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("mode", "Mode", List.of("POSITION", "TOKEN"), "POSITION", ""),
                Param.integer("start", "Start at", 1, "1 is the first character.").when("mode=POSITION"),
                Param.integer("length", "Length", 0, "0 means to the end.").when("mode=POSITION"),
                Param.bool("fromEnd", "Count from the end", false, "With length 4: the last four characters.").when("mode=POSITION"),
                Param.text("delimiter", "Delimiter", "For example a comma.").when("mode=TOKEN"),
                Param.integer("tokenIndex", "Token number", 1, "1 is the first token.").when("mode=TOKEN"),
                Param.choice("onShort", "If the text is shorter", List.of("BLANK", "PASSTHROUGH"), "PASSTHROUGH", "")
        );
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        if ("POSITION".equals(Rule.str(params, "mode", "POSITION")) && Rule.integer(params, "start", 1) < 1) return List.of("Start must be 1 or more");
        return List.of();
    }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        if (in == null) return null;
        String s = Values.text(in);
        String onShort = Rule.str(params, "onShort", "PASSTHROUGH");
        if ("TOKEN".equals(Rule.str(params, "mode", "POSITION"))) {
            String delim = Rule.str(params, "delimiter", ",");
            String[] tokens = s.split(Pattern.quote(delim), -1);
            int idx = Rule.integer(params, "tokenIndex", 1) - 1;
            if (idx < 0 || idx >= tokens.length) return onShort.equals("BLANK") ? null : s;
            return tokens[idx].trim();
        }
        int start = Math.max(1, Rule.integer(params, "start", 1));
        int length = Rule.integer(params, "length", 0);
        if (Rule.bool(params, "fromEnd", false)) {
            int len = length <= 0 ? s.length() : length;
            if (len > s.length()) return onShort.equals("BLANK") ? null : s;
            return s.substring(s.length() - len);
        }
        if (start > s.length()) return onShort.equals("BLANK") ? null : s;
        int end = length <= 0 ? s.length() : Math.min(s.length(), start - 1 + length);
        return s.substring(start - 1, end);
    }

    @Override public String summarize(Map<String, Object> params) {
        if ("TOKEN".equals(Rule.str(params, "mode", "POSITION"))) return "token " + Rule.integer(params, "tokenIndex", 1) + " by \"" + Rule.str(params, "delimiter", ",") + "\"";
        int len = Rule.integer(params, "length", 0);
        if (Rule.bool(params, "fromEnd", false)) return "last " + len;
        return "from " + Rule.integer(params, "start", 1) + (len > 0 ? " for " + len : "");
    }
}
