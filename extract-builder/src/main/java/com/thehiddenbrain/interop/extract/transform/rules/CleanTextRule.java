package com.thehiddenbrain.interop.extract.transform.rules;

import com.thehiddenbrain.interop.extract.transform.Param;
import com.thehiddenbrain.interop.extract.transform.Rule;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;

@Component
public class CleanTextRule implements Rule {

    @Override public String type() { return "CLEAN_TEXT"; }
    @Override public String label() { return "Clean text"; }
    @Override public String description() { return "Trim, change case, strip characters, replace accented letters with plain ASCII, and literal find and replace. One form for the common text fixes."; }

    @Override
    public List<Param> params() {
        return List.of(
                Param.choice("trim", "Whitespace", List.of("COLLAPSE", "BOTH", "LEFT", "RIGHT", "NONE"), "COLLAPSE", "COLLAPSE trims the ends and squeezes runs of spaces."),
                Param.choice("textCase", "Case", List.of("NONE", "UPPER", "LOWER", "TITLE"), "NONE", ""),
                Param.choice("strip", "Strip", List.of("NONE", "NON_DIGITS", "DIGITS", "NON_ALNUM", "CONTROL", "NEWLINES"), "NONE", "NON_DIGITS keeps only digits: 312-555-0100 becomes 3125550100."),
                Param.bool("asciiOnly", "Plain ASCII letters", false, "José becomes Jose."),
                Param.text("find", "Find", "Literal text to replace."),
                Param.text("replaceWith", "Replace with", "")
        );
    }

    @Override
    public Object apply(List<Object> inputs, Map<String, Object> params, RuleContext ctx) {
        Object in = inputs.isEmpty() ? null : inputs.get(0);
        if (in == null) return null;
        String s = Values.text(in);
        if (Rule.bool(params, "asciiOnly", false)) {
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace("ß", "ss").replace("Ø", "O").replace("ø", "o").replace("Æ", "AE").replace("æ", "ae");
        }
        String find = Rule.str(params, "find", "");
        if (!find.isEmpty()) s = s.replace(find, Rule.str(params, "replaceWith", ""));
        switch (Rule.str(params, "strip", "NONE")) {
            case "NON_DIGITS" -> s = s.replaceAll("[^0-9]", "");
            case "DIGITS" -> s = s.replaceAll("[0-9]", "");
            case "NON_ALNUM" -> s = s.replaceAll("[^A-Za-z0-9 ]", "");
            case "CONTROL" -> s = s.replaceAll("\\p{Cntrl}", "");
            case "NEWLINES" -> s = s.replaceAll("[\\r\\n\\t]+", " ");
            default -> { }
        }
        switch (Rule.str(params, "trim", "COLLAPSE")) {
            case "COLLAPSE" -> s = s.trim().replaceAll("\\s{2,}", " ");
            case "BOTH" -> s = s.trim();
            case "LEFT" -> s = s.replaceAll("^\\s+", "");
            case "RIGHT" -> s = s.replaceAll("\\s+$", "");
            default -> { }
        }
        switch (Rule.str(params, "textCase", "NONE")) {
            case "UPPER" -> s = s.toUpperCase();
            case "LOWER" -> s = s.toLowerCase();
            case "TITLE" -> {
                StringBuilder sb = new StringBuilder(s.length());
                boolean up = true;
                for (char c : s.toCharArray()) {
                    sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                    up = !Character.isLetterOrDigit(c) && c != '\'';
                }
                s = sb.toString();
            }
            default -> { }
        }
        return s;
    }

    @Override public String summarize(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        String c = Rule.str(params, "textCase", "NONE");
        if (!c.equals("NONE")) sb.append(c.toLowerCase());
        String strip = Rule.str(params, "strip", "NONE");
        if (!strip.equals("NONE")) sb.append(sb.length() > 0 ? " · " : "").append("strip ").append(strip.toLowerCase().replace('_', ' '));
        if (Rule.bool(params, "asciiOnly", false)) sb.append(sb.length() > 0 ? " · " : "").append("ascii");
        if (!Rule.str(params, "find", "").isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append("replace \"").append(Rule.str(params, "find", "")).append("\"");
        String trim = Rule.str(params, "trim", "COLLAPSE");
        if (sb.length() == 0) sb.append(trim.equals("NONE") ? "clean" : "trim");
        return sb.toString();
    }
}
