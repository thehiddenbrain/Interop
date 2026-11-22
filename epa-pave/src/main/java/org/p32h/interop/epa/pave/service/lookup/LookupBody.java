package org.p32h.interop.epa.pave.service.lookup;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * The body template of a POST lookup (R-11): JSON holding {@code {memberId}} or {@code {npi}}, and
 * optionally {@code {dateOfService}}.
 *
 * <p>A token inside a JSON string is replaced by the value JSON-escaped; a token standing where a
 * value goes is replaced by the value as a JSON string. So a member ID holding a quote or a
 * backslash can never change the body's shape. A {@code {{placeholder}}} is left for the gateway,
 * which resolves it before the tokens are filled, so nothing typed into a card field is ever
 * resolved as a placeholder (a member ID written as {@code {{clientSecret}}} stays that text).
 *
 * <p>One pass over the characters, no pattern and no recursion (R-23).
 */
public final class LookupBody {

    private LookupBody() {
    }

    /** The single-brace tokens the template holds, in order, each once. */
    public static Set<String> tokens(String template) {
        Set<String> found = new LinkedHashSet<>();
        walk(template, null, found, false);
        return found;
    }

    /**
     * The template with each token of {@code values} replaced. A token with no value, and every
     * {@code {{placeholder}}}, stay as written.
     */
    public static String fill(String template, Map<String, String> values) {
        return walk(template, values, null, false);
    }

    /**
     * Why the template cannot be a body for this kind, or null when it can: it must hold the
     * identifier's token and be JSON once every token and placeholder is given a value.
     */
    public static String problem(String template, LookupKind kind) {
        if (Texts.blank(template)) {
            return "A POST lookup needs a body template.";
        }
        Set<String> tokens = tokens(template);
        if (!tokens.contains(kind.token())) {
            return "Put {" + kind.token() + "} where the " + (kind == LookupKind.MEMBER ? "member ID" : "NPI") + " goes.";
        }
        for (String token : tokens) {
            if (!token.equals(kind.token()) && !token.equals(LookupKind.DATE_TOKEN)) {
                return "{" + token + "} is not a token PAVE fills. The tokens are {" + kind.token() + "} and {" + LookupKind.DATE_TOKEN + "}.";
            }
        }
        String probe = walk(template, Map.of(kind.token(), "x", LookupKind.DATE_TOKEN, "x"), null, true);
        JsonSafe.Parsed parsed = JsonSafe.parse(probe);
        if (parsed == null || parsed.node() == null || parsed.error() != null) {
            return "The body template is not JSON once {" + kind.token() + "} is put in. Check its quotes, commas and braces.";
        }
        return null;
    }

    /**
     * @param values           token name to value; null to only collect the tokens
     * @param found            collects the token names; may be null
     * @param placeholdersToo  also give a {{placeholder}} outside a string a value, for the JSON check
     */
    private static String walk(String template, Map<String, String> values, Set<String> found, boolean placeholdersToo) {
        if (template == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        boolean inString = false;
        int length = template.length();
        int i = 0;
        while (i < length) {
            char c = template.charAt(i);
            if (inString && c == '\\' && i + 1 < length) {
                out.append(c).append(template.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                i++;
                continue;
            }
            if (c == '{' && i + 1 < length && template.charAt(i + 1) == '{') {
                int close = template.indexOf("}}", i + 2);
                int end = close < 0 ? length : close + 2;
                if (placeholdersToo && !inString && close >= 0) {
                    out.append("\"x\"");
                } else {
                    out.append(template, i, end);
                }
                i = end;
                continue;
            }
            if (c == '{') {
                int j = i + 1;
                while (j < length && isTokenChar(template.charAt(j))) {
                    j++;
                }
                if (j > i + 1 && j < length && template.charAt(j) == '}') {
                    String name = template.substring(i + 1, j);
                    if (found != null) {
                        found.add(name);
                    }
                    String value = values == null ? null : values.get(name);
                    if (value != null) {
                        out.append(inString ? escaped(value) : "\"" + escaped(value) + "\"");
                    } else {
                        out.append(template, i, j + 1);
                    }
                    i = j + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** The text as the inside of a JSON string. */
    static String escaped(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
