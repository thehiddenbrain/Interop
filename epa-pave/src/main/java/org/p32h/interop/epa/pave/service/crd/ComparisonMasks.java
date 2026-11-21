package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks what a comparison would otherwise show of a credential typed into a request.
 *
 * <p>A pasted vendor sample often carries {@code fhirAuthorization.access_token} as written. A run
 * of the same body masks it in the wire view, by the name of the element that holds it, and the
 * comparison is shown and shared as much as a run is. So the same rule masks it here: the one
 * {@link LiteralCredentials#inBody} applies to a body a run sends, with the same mask text. The
 * two requests are compared on the values as typed before anything is masked, so two different
 * credentials are still a difference; only what is shown of them changes.
 *
 * <p>Used once per comparison, because it counts what it masked.
 */
final class ComparisonMasks {

    private int masked;

    /** How many values were masked so far. */
    int count() {
        return masked;
    }

    /**
     * A whole request as shown, JSON or not, with each credential typed into it masked. A text
     * that reads as JSON, even broken JSON, is walked member by member, so its layout is kept and
     * a long stretch with no quote in it cannot exhaust the stack as a pattern over the whole text
     * can; each member is then judged by the rule alone. Any other text goes to the rule whole.
     */
    String body(String text) {
        if (text == null) {
            return null;
        }
        String start = text.strip();
        if (!start.startsWith("{") && !start.startsWith("[")) {
            return wholeText(text);
        }
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int at = 0;
        while (true) {
            int nameStart = text.indexOf('"', at);
            if (nameStart < 0) {
                break;
            }
            int nameEnd = closingQuote(text, nameStart);
            if (nameEnd < 0) {
                break;
            }
            int colon = skipSpace(text, nameEnd + 1);
            if (colon >= text.length() || text.charAt(colon) != ':') {
                at = nameEnd + 1;
                continue;
            }
            int valueStart = skipSpace(text, colon + 1);
            if (valueStart >= text.length() || text.charAt(valueStart) != '"') {
                at = valueStart;
                continue;
            }
            int valueEnd = closingQuote(text, valueStart);
            if (valueEnd < 0) {
                break;
            }
            String name = unescaped(text.substring(nameStart + 1, nameEnd));
            String value = unescaped(text.substring(valueStart + 1, valueEnd));
            if (!isMask(value) && credential(name, value)) {
                out.append(text, copied, valueStart + 1).append(Secrets.mask(name + LiteralCredentials.SUFFIX));
                copied = valueEnd;
                masked++;
            }
            at = valueEnd + 1;
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    /** A text that is not JSON, judged whole by the rule a run applies to such a body. */
    private String wholeText(String text) {
        LiteralCredentials.Marked marked;
        try {
            marked = LiteralCredentials.inBody(text);
        } catch (StackOverflowError tooLong) {
            // The rule could not be applied to this text, so none of it is shown: showing it whole could show a credential.
            masked++;
            return "(Not shown: this text could not be checked for a credential typed into it.)";
        }
        if (marked.none()) {
            return text;
        }
        String shown = marked.template();
        for (var entry : marked.values().entrySet()) {
            shown = shown.replace("{{" + entry.getKey() + "}}", Secrets.mask(entry.getKey()));
            masked++;
        }
        return shown;
    }

    /** True when the rule a run applies to a body masks this member: judged on the member alone. */
    private static boolean credential(String name, String value) {
        if (!Secrets.looksSecret(name)) {
            return false;
        }
        ObjectNode alone = JsonNodeFactory.instance.objectNode();
        alone.put(name, value);
        try {
            return !LiteralCredentials.inBody(alone.toString()).none();
        } catch (StackOverflowError tooLong) {
            // A value too long for the rule to read, under a name that reads as a credential, is masked.
            return true;
        }
    }

    private static int closingQuote(String text, int open) {
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int skipSpace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The text of a JSON string as written between its quotes, or as written when its escapes cannot be read. */
    private static String unescaped(String written) {
        if (written.indexOf('\\') < 0) {
            return written;
        }
        JsonNode node = org.p32h.interop.epa.pave.model.JsonSafe.parse("\"" + written + "\"").node();
        return node != null && node.isString() ? node.stringValue() : written;
    }

    private static boolean isMask(String value) {
        java.util.List<String> names = Secrets.maskedNames(value);
        return names.size() == 1 && value.equals(Secrets.mask(names.get(0)));
    }

    /** The difference with each value masked that the rule masks, or the difference itself when it shows no credential. */
    Difference difference(Difference difference) {
        if (difference.kind() == DifferenceKind.DIFFERENT_KEY) {
            // Its two values are keys, which are names and never a credential.
            return difference;
        }
        String name = elementName(difference.yourPointer() != null ? difference.yourPointer() : difference.toolPointer());
        Shown tool = value(difference.toolValue(), difference.toolType(), name);
        Shown yours = value(difference.yourValue(), difference.yourType(), name);
        if (tool == difference.toolValue() && yours == difference.yourValue()) {
            return difference;
        }
        String explanation = difference.explanation();
        if (tool.hasValue() && tool.equals(yours) && tool != difference.toolValue() && yours != difference.yourValue()) {
            // Two equal masks would otherwise read as two equal values listed as a difference.
            explanation = explanation + " Both values are masked, because the name of the element reads as a credential, and they differ "
                    + "as typed.";
        }
        return new Difference(difference.path(), difference.toolPointer(), difference.yourPointer(), difference.kind(),
                difference.kindLabel(), difference.group(), tool, yours, difference.toolType(), difference.yourType(), difference.what(),
                explanation, difference.referencesFollowing());
    }

    private Shown value(Shown value, String type, String name) {
        if (value == null || !value.hasValue()) {
            return value;
        }
        if ("object".equals(type) || "array".equals(type)) {
            int before = masked;
            String shown = body(value.value());
            return masked == before ? value : Shown.of(shown);
        }
        if (!"string".equals(type) || name == null || isMask(value.value()) || !credential(name, value.value())) {
            return value;
        }
        masked++;
        return Shown.of(Secrets.mask(name + LiteralCredentials.SUFFIX));
    }

    /** The name of the member a JSON Pointer ends in, or null when it ends in a position in a list or names nothing. */
    static String elementName(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return null;
        }
        String last = pointer.substring(pointer.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
        return last.isEmpty() || last.chars().allMatch(Character::isDigit) ? null : last;
    }

    /** One sentence on how many values were masked and why, or null when none was. */
    String note() {
        if (masked == 0) {
            return null;
        }
        return (masked == 1 ? "1 value in this comparison is masked, because the name of the element that holds it reads"
                : masked + " values in this comparison are masked, because the name of the element that holds each reads")
                + " as a credential, as in the wire view of a run, while the two requests were compared on the values as typed.";
    }
}
