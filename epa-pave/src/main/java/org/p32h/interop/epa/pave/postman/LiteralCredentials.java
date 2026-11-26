package org.p32h.interop.epa.pave.postman;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.VariableSource;

/**
 * Credentials typed straight into the text of a request: a url, a body, a saved example.
 *
 * <p>A secret that arrives through a variable is masked by the name of the variable. A vendor's
 * collection can also hold a credential that is no variable at all, written into a query
 * parameter, a body field or a saved example. It is recognised by the name of the place it sits
 * in, never by what the value looks like: a query parameter or a body field whose name reads as a
 * credential by {@link Secrets#looksSecret(String)} holds a literal credential when its value is
 * written out, with no placeholder in it.
 *
 * <p>So that everything that already masks a secret variable masks this as well, the value is
 * marked when the text is read: {@link #inUrl} and {@link #inBody} put a placeholder of its own in
 * its place, and {@link Marked#layer()} supplies that placeholder with the value as written, typed
 * as a secret. The text resolves to exactly what was written and is sent unchanged, while every
 * display form shows the mask of the place, for example {@code [secret client_secret as typed]}.
 */
public final class LiteralCredentials {

    /** Ends the name of the placeholder that stands for a literal credential, so that it is told apart from a variable. */
    public static final String SUFFIX = " as typed";

    /**
     * FHIR elements whose name reads as a credential and is not one. preAuthRef is the number of a
     * prior authorization on a Claim, a ClaimResponse or an ExplanationOfBenefit: the very value a
     * PAS tester reads, and nearly every saved PAS answer holds one. The names are FHIR's own and
     * are matched exactly, so a field a vendor names in its own way is still judged by its name.
     */
    private static final Set<String> FHIR_ELEMENTS_THAT_ARE_NOT_CREDENTIALS = Set.of("preAuthRef");

    /** One field of a form-encoded text: what precedes it, its name and its value. */
    private static final Pattern FORM_FIELD = Pattern.compile("(^|[&?])([^=&?#\\s]+)=([^&#\\s]*)");

    /**
     * A credential found, by the place it sits in. Never the value.
     *
     * @param place the place in words, for example "the query parameter client_secret of its url"
     */
    public record Found(String place) {
    }

    /**
     * A text with each literal credential replaced by a placeholder of its own.
     *
     * @param template the text to resolve: as written, with a placeholder in place of each literal credential
     * @param shown    the text as written with each literal credential masked, for showing
     * @param values   each placeholder name with the value it stands for. Never shown
     * @param found    each credential found, by its place
     */
    public record Marked(String template, String shown, Map<String, String> values, List<Found> found) {

        public Marked {
            values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
            found = found == null ? List.of() : List.copyOf(found);
        }

        /** True when nothing was found, so the text is unchanged. */
        public boolean none() {
            return values.isEmpty();
        }

        /**
         * The layer that supplies the placeholders, each typed as a secret, or null when there is
         * nothing to supply. It is asked before every other layer: the text was written into the
         * request, and no variable of the same name can change what it says.
         */
        public VariableLayer layer() {
            if (values.isEmpty()) {
                return null;
            }
            Map<String, VariableLayer.Entry> entries = new LinkedHashMap<>();
            values.forEach((name, value) -> entries.put(name, new VariableLayer.Entry(value, true)));
            return new VariableLayer(VariableSource.COLLECTION, "the text of the request, where it was typed", entries);
        }

        /** The layers with {@link #layer()} in front, or the layers as they were when there is nothing to supply. */
        public List<VariableLayer> before(List<VariableLayer> layers) {
            VariableLayer own = layer();
            if (own == null) {
                return layers;
            }
            List<VariableLayer> all = new ArrayList<>();
            all.add(own);
            all.addAll(layers);
            return all;
        }

        /** Prints the places, never a value. */
        @Override
        public String toString() {
            return "Marked[" + found + "]";
        }
    }

    private LiteralCredentials() {
    }

    /** A text that holds no literal credential, unchanged. */
    public static Marked unchanged(String text) {
        return new Marked(text, text, Map.of(), List.of());
    }

    /**
     * The literal credentials of a url: the value of each query parameter whose name reads as a
     * credential, and a password written into the url before its host.
     */
    public static Marked inUrl(String url) {
        if (url == null || url.isEmpty()) {
            return unchanged(url);
        }
        Builder builder = new Builder(url);
        int scheme = url.indexOf("://");
        int pathStart = scheme > 0 ? firstOf(url, "/?#", scheme + 3) : -1;
        if (scheme > 0) {
            int authorityEnd = pathStart < 0 ? url.length() : pathStart;
            int at = url.lastIndexOf('@', authorityEnd - 1);
            int colon = at > scheme ? url.indexOf(':', scheme + 3) : -1;
            if (at > scheme && colon > 0 && colon < at && literal(url.substring(colon + 1, at))) {
                builder.mark(colon + 1, at, "password", "the password written into its url");
            }
        }
        int query = url.indexOf('?', Math.max(0, pathStart));
        if (query >= 0) {
            int end = firstOf(url, "#", query);
            String text = url.substring(query, end < 0 ? url.length() : end);
            Matcher field = FORM_FIELD.matcher(text);
            while (field.find()) {
                String name = decoded(field.group(2));
                if (Secrets.looksSecret(name) && literal(field.group(3))) {
                    builder.mark(query + field.start(3), query + field.end(3), name, "the query parameter " + Names.shown(name) + " of its url");
                }
            }
        }
        return builder.build();
    }

    /**
     * The literal credentials of a body: the string value of each JSON member whose name reads as a
     * credential, or, in a body that is not JSON or XML, the value of each form field whose name does.
     */
    public static Marked inBody(String body) {
        if (body == null || body.isBlank()) {
            return unchanged(body);
        }
        Builder builder = new Builder(body);
        String start = body.strip();
        if (start.startsWith("{") || start.startsWith("[")) {
            for (JsonMember member : jsonMembers(body)) {
                String name = body.substring(member.nameStart(), member.nameEnd());
                String value = body.substring(member.valueStart(), member.valueEnd());
                if (Secrets.looksSecret(name) && !FHIR_ELEMENTS_THAT_ARE_NOT_CREDENTIALS.contains(name) && literal(value)) {
                    builder.mark(member.valueStart(), member.valueEnd(), name, "the field " + Names.shown(name) + " of its body");
                }
            }
        } else if (!start.startsWith("<")) {
            Matcher field = FORM_FIELD.matcher(body);
            while (field.find()) {
                String name = decoded(field.group(2));
                if (Secrets.looksSecret(name) && literal(field.group(3))) {
                    builder.mark(field.start(3), field.end(3), name, "the form field " + Names.shown(name) + " of its body");
                }
            }
        }
        return builder.build();
    }

    /**
     * A JSON member whose value is a string, by where it starts and ends and where its name and its
     * value stand between their quotes.
     */
    record JsonMember(int start, int end, int nameStart, int nameEnd, int valueStart, int valueEnd) {
    }

    /**
     * Every JSON member whose value is a string, read from left to right as the regular expression
     * {@code "((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"} found them with find(): the same
     * members at the same places, in a text that is JSON or only looks like it.
     *
     * <p>That expression made java.util.regex call itself once per character of a quoted text, so
     * a text of some 1,300 characters overflowed the stack. Written possessively it no longer
     * recursed, but find() still tried again from every quote, and each try read on to the end of
     * the quoted text: a text full of escaped quotes, such as JSON carried inside a string, took
     * seconds for a few tens of kilobytes and hours for a megabyte, inside the lock of the store.
     * This reads each character a bounded number of times. A quote inside a quoted text that was
     * already read was escaped there, so a try that starts at it reads on exactly as that text did
     * and ends where it ended; that is remembered instead of read again.
     */
    static List<JsonMember> jsonMembers(String text) {
        List<JsonMember> members = new ArrayList<>();
        QuotedTexts quoted = new QuotedTexts(text);
        int restFrom = -1;
        int[] rest = null;
        int at = 0;
        while (at < text.length()) {
            if (text.charAt(at) != '"') {
                at++;
                continue;
            }
            int nameEnd = quoted.end(at + 1);
            if (nameEnd != restFrom) {
                restFrom = nameEnd;
                rest = rest(text, nameEnd, quoted);
            }
            if (rest == null) {
                at++;
                continue;
            }
            members.add(new JsonMember(at, rest[1] + 1, at + 1, nameEnd, rest[0], rest[1]));
            at = rest[1] + 1;
        }
        return members;
    }

    /**
     * What follows a name that ends at the given place: its closing quote, white space, a colon,
     * white space and a quoted value. The start and end of the value, or null when that is not there.
     */
    private static int[] rest(String text, int nameEnd, QuotedTexts quoted) {
        if (nameEnd >= text.length() || text.charAt(nameEnd) != '"') {
            return null;
        }
        int colon = afterWhiteSpace(text, nameEnd + 1);
        if (colon >= text.length() || text.charAt(colon) != ':') {
            return null;
        }
        int open = afterWhiteSpace(text, colon + 1);
        if (open >= text.length() || text.charAt(open) != '"') {
            return null;
        }
        int valueEnd = quoted.end(open + 1);
        return valueEnd < text.length() && text.charAt(valueEnd) == '"' ? new int[] {open + 1, valueEnd} : null;
    }

    /** The white space of a regular expression's \s: space, tab, line feed, vertical tab, form feed and carriage return. */
    private static int afterWhiteSpace(String text, int from) {
        int at = from;
        while (at < text.length() && " \t\n\013\f\r".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        return at;
    }

    /**
     * Reads the inside of quoted texts: from just after an opening quote to the first quote that is
     * not escaped, to a backslash that escapes nothing (one before a line terminator or at the
     * end), or to the end of the text. The two texts read last are remembered: a read that starts
     * inside one of them, just after a quote that was escaped in it, ends where it ended.
     */
    private static final class QuotedTexts {
        private final String text;
        private int lastStart = -1;
        private int lastEnd = -1;
        private int earlierStart = -1;
        private int earlierEnd = -1;

        QuotedTexts(String text) {
            this.text = text;
        }

        int end(int from) {
            if (lastStart >= 0 && from >= lastStart && from <= lastEnd) {
                return lastEnd;
            }
            if (earlierStart >= 0 && from >= earlierStart && from <= earlierEnd) {
                return earlierEnd;
            }
            int at = from;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (at + 1 >= text.length() || lineTerminator(text.charAt(at + 1))) {
                        break;
                    }
                    at += 2;
                } else {
                    at++;
                }
            }
            int end = Math.min(at, text.length());
            earlierStart = lastStart;
            earlierEnd = lastEnd;
            lastStart = from;
            lastEnd = end;
            return end;
        }

        /** What the regular expression's dot does not match. */
        private static boolean lineTerminator(char c) {
            return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
        }
    }

    /** True when a value was written out: something there, and no placeholder in it. */
    private static boolean literal(String value) {
        return value != null && !value.isBlank() && !value.contains("{{");
    }

    private static String decoded(String name) {
        try {
            return java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // An escape that is not one: the name as written is what there is to read.
            return name;
        }
    }

    private static int firstOf(String text, String characters, int from) {
        for (int i = Math.max(0, from); i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Collects the stretches to mark, in order, and builds the two texts. */
    private static final class Builder {

        private final String text;
        private final StringBuilder template = new StringBuilder();
        private final StringBuilder shown = new StringBuilder();
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<Found> found = new ArrayList<>();
        private int copied;

        Builder(String text) {
            this.text = text;
        }

        void mark(int start, int end, String name, String place) {
            // A name with a brace in it could not stand inside a placeholder. Such a place is left as it is.
            if (start < copied || name.indexOf('{') >= 0 || name.indexOf('}') >= 0) {
                return;
            }
            String placeholder = name + SUFFIX;
            for (int n = 2; values.containsKey(placeholder); n++) {
                placeholder = name + SUFFIX + " " + n;
            }
            template.append(text, copied, start).append("{{").append(placeholder).append("}}");
            shown.append(text, copied, start).append(Secrets.mask(placeholder));
            values.put(placeholder, text.substring(start, end));
            found.add(new Found(place));
            copied = end;
        }

        Marked build() {
            if (values.isEmpty()) {
                return unchanged(text);
            }
            template.append(text, copied, text.length());
            shown.append(text, copied, text.length());
            return new Marked(template.toString(), shown.toString(), values, found);
        }
    }
}
