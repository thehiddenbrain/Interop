package org.p32h.interop.epa.pave.postman;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Resolution.Kind;
import org.p32h.interop.epa.pave.postman.Resolution.Piece;

/**
 * The work a url needs after its placeholders are resolved: filling its tokens.
 *
 * <p>A token is a single-brace {@code {name}} anywhere in the url, or a Postman path variable
 * {@code :name} that is a whole segment of the path. Each is filled from a value the call supplies
 * and is percent-encoded, so the value stays one path segment or one query value whatever it
 * holds. A member ID with a space, a slash or a percent sign in it cannot change the shape of the
 * url. This is separate from placeholder resolution, which substitutes as written, as Postman does.
 *
 * <p>Tokens are looked for in the text of the template and in the values of ordinary variables.
 * They are never looked for inside the value of a secret, and a secret's value is never read here.
 */
public final class UrlForms {

    private static final char HOLE = '\u0000';
    private static final Pattern BRACE_TOKEN = Pattern.compile("(?<!\\{)\\{([A-Za-z_][A-Za-z0-9_.-]*)}(?!})");
    private static final Pattern COLON_TOKEN = Pattern.compile(":([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern SCHEME = Pattern.compile("^\\s*[A-Za-z][A-Za-z0-9+.-]*://");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UrlForms() {
    }

    /**
     * Percent-encodes a value so that it is one path segment or one query value. Everything except
     * letters, digits, hyphen, full stop, underscore and tilde is encoded, as UTF-8.
     */
    public static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return out.toString();
    }

    /**
     * Where the first occurrence of a marker that ends a path segment ends in the text, or -1 when
     * there is none. The marker ends a segment when the text stops there or goes on with a slash,
     * a question mark or a hash. Letters are compared ignoring case, in place: lower-casing the
     * whole text first can change its length, and the position would then point somewhere else.
     */
    public static int endOfSegment(String text, String marker) {
        if (text == null || marker == null || marker.isEmpty()) {
            return -1;
        }
        for (int at = 0; at + marker.length() <= text.length(); at++) {
            int end = at + marker.length();
            if (text.regionMatches(true, at, marker, 0, marker.length()) && (end == text.length() || "/?#".indexOf(text.charAt(end)) >= 0)) {
                return end;
            }
        }
        return -1;
    }

    /**
     * A url with its tokens filled.
     *
     * @param send          the url to send to, or null when a placeholder or a token has no value
     * @param display       the url to show, secrets masked, or null when anything has no value
     * @param shown         the url to show with whatever did resolve filled in, and every
     *                      placeholder and token that did not left as written
     * @param probe         the same as {@code shown} but with the real value of each secret. It is
     *                      what operation detection reads, because the guardrail judges what would
     *                      be sent. It is never displayed, stored or logged
     * @param missingTokens the tokens no value was supplied for
     */
    public record Filled(String send, String display, String shown, String probe, List<String> missingTokens) {

        public boolean complete() {
            return send != null;
        }

        /** Prints nothing of the url: every form of it can hold an identifier, and one holds secrets. */
        @Override
        public String toString() {
            return "Filled[" + (complete() ? "complete" : "incomplete") + "]";
        }
    }

    /**
     * One segment of the path.
     *
     * @param text        the segment as it stands, with a hole where a secret or a missing value sits
     * @param placeholder the variable whose whole value is this segment, or null when the segment
     *                    is literal text or is built from more than one thing
     * @param readable    false when the segment holds a secret or a missing value, so its text
     *                    cannot be compared with anything
     */
    public record Segment(String text, String placeholder, boolean readable) {
    }

    private record Token(String name, int start, int end) {
    }

    /** The resolved pieces of a url laid out as one text, with a hole wherever a secret or a missing value sits. */
    public static Skeleton skeleton(Resolution resolution) {
        return new Skeleton(resolution.pieces());
    }

    public static final class Skeleton {

        private final List<Piece> pieces;
        private final String text;
        private final Map<Integer, Integer> holes = new HashMap<>();
        private final int[] pieceStart;
        private final int[] pieceEnd;

        private Skeleton(List<Piece> pieces) {
            this.pieces = pieces;
            this.pieceStart = new int[pieces.size()];
            this.pieceEnd = new int[pieces.size()];
            StringBuilder laidOut = new StringBuilder();
            for (int i = 0; i < pieces.size(); i++) {
                Piece piece = pieces.get(i);
                pieceStart[i] = laidOut.length();
                if (piece.opaque()) {
                    holes.put(laidOut.length(), i);
                    laidOut.append(HOLE);
                } else {
                    laidOut.append(piece.send());
                }
                pieceEnd[i] = laidOut.length();
            }
            this.text = laidOut.toString();
        }

        private Skeleton(Skeleton whole, int length) {
            this.pieces = whole.pieces;
            this.text = whole.text.substring(0, length);
            this.pieceStart = whole.pieceStart;
            this.pieceEnd = whole.pieceEnd;
            whole.holes.forEach((position, piece) -> {
                if (position < length) {
                    holes.put(position, piece);
                }
            });
        }

        /** True when a placeholder in the url has no value, so part of the url cannot be seen. */
        public boolean hasMissing() {
            return holes.values().stream().anyMatch(index -> pieces.get(index).kind() == Kind.MISSING);
        }

        /** The names of the tokens the url holds. */
        public Set<String> tokenNames() {
            Set<String> names = new LinkedHashSet<>();
            tokens().forEach(token -> names.add(token.name()));
            return names;
        }

        /**
         * Fills each token from the values supplied. A token whose value is missing or blank is
         * left as written and listed: an empty string in its place would send a request to the
         * wrong address and look like a network problem.
         */
        public Filled fill(Map<String, String> urlValues) {
            StringBuilder send = new StringBuilder();
            StringBuilder display = new StringBuilder();
            StringBuilder shown = new StringBuilder();
            StringBuilder probe = new StringBuilder();
            List<String> missing = new ArrayList<>();
            boolean complete = true;
            int position = 0;
            for (Token token : tokens()) {
                complete &= copy(position, token.start(), send, display, shown, probe);
                String value = urlValues == null ? null : urlValues.get(token.name());
                if (Texts.present(value)) {
                    String encoded = encode(value);
                    send.append(encoded);
                    display.append(encoded);
                    shown.append(encoded);
                    probe.append(encoded);
                } else {
                    complete = false;
                    if (!missing.contains(token.name())) {
                        missing.add(token.name());
                    }
                    shown.append(text, token.start(), token.end());
                    probe.append(text, token.start(), token.end());
                }
                position = token.end();
            }
            complete &= copy(position, text.length(), send, display, shown, probe);
            return new Filled(complete ? send.toString() : null, complete ? display.toString() : null, shown.toString(),
                    probe.toString(), missing);
        }

        private boolean copy(int from, int to, StringBuilder send, StringBuilder display, StringBuilder shown, StringBuilder probe) {
            boolean complete = true;
            for (int i = from; i < to; i++) {
                Integer hole = holes.get(i);
                if (hole == null) {
                    char c = text.charAt(i);
                    send.append(c);
                    display.append(c);
                    shown.append(c);
                    probe.append(c);
                    continue;
                }
                Piece piece = pieces.get(hole);
                if (piece.kind() == Kind.SECRET) {
                    send.append(piece.send());
                    probe.append(piece.send());
                    display.append(piece.display());
                    shown.append(piece.display());
                } else {
                    complete = false;
                    shown.append("{{").append(piece.name()).append("}}");
                    probe.append("{{").append(piece.name()).append("}}");
                }
            }
            return complete;
        }

        /**
         * The path segment that follows the first segment with the given name, or null when there
         * is none. Used to find where a FHIR resource id sits: the segment after
         * {@code Subscription}.
         */
        public Segment segmentAfter(String resource) {
            List<int[]> segments = segments();
            for (int i = 0; i + 1 < segments.size(); i++) {
                int[] here = segments.get(i);
                int[] next = segments.get(i + 1);
                if (readable(here) && text.substring(here[0], here[1]).equalsIgnoreCase(resource) && next[1] > next[0]) {
                    String placeholder = null;
                    for (int piece = 0; piece < pieces.size(); piece++) {
                        Piece candidate = pieces.get(piece);
                        boolean leaf = candidate.whole() && candidate.kind() != Kind.LITERAL && candidate.name() != null;
                        if (leaf && pieceStart[piece] == next[0] && pieceEnd[piece] == next[1]) {
                            placeholder = candidate.name();
                        }
                    }
                    return new Segment(text.substring(next[0], next[1]), placeholder, readable(next));
                }
            }
            return null;
        }

        /**
         * This url cut off just after a marker that ends a path segment, or null when the marker is
         * not there. Used to derive the CDS Hooks discovery url from a hook url: everything up to
         * and including {@code /cds-services}.
         */
        public Skeleton upTo(String marker) {
            int end = endOfSegment(text, marker);
            return end < 0 ? null : new Skeleton(this, end);
        }

        private boolean readable(int[] range) {
            for (int i = range[0]; i < range[1]; i++) {
                if (holes.containsKey(i)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The path segments as start and end positions. A segment is whatever follows a slash, up
         * to the next slash or the end of the path. The scheme and the host are not part of the
         * path, so a port such as {@code :8080} is never taken for a path variable.
         */
        private List<int[]> segments() {
            int from = 0;
            Matcher scheme = SCHEME.matcher(text);
            if (scheme.find()) {
                from = scheme.end();
                while (from < text.length() && "/?#".indexOf(text.charAt(from)) < 0) {
                    from++;
                }
            }
            int to = from;
            while (to < text.length() && text.charAt(to) != '?' && text.charAt(to) != '#') {
                to++;
            }
            List<int[]> segments = new ArrayList<>();
            for (int i = from; i < to; i++) {
                if (text.charAt(i) == '/' && !holes.containsKey(i)) {
                    int end = i + 1;
                    while (end < to && !(text.charAt(end) == '/' && !holes.containsKey(end))) {
                        end++;
                    }
                    segments.add(new int[] {i + 1, end});
                }
            }
            return segments;
        }

        private List<Token> tokens() {
            List<Token> tokens = new ArrayList<>();
            Matcher brace = BRACE_TOKEN.matcher(text);
            while (brace.find()) {
                tokens.add(new Token(brace.group(1), brace.start(), brace.end()));
            }
            for (int[] segment : segments()) {
                Matcher colon = COLON_TOKEN.matcher(text.substring(segment[0], segment[1]));
                if (readable(segment) && colon.matches()) {
                    tokens.add(new Token(colon.group(1), segment[0], segment[1]));
                }
            }
            tokens.sort(Comparator.comparingInt(Token::start));
            return tokens;
        }

        /** Prints nothing of the url. */
        @Override
        public String toString() {
            return "Skeleton[" + pieces.size() + " pieces]";
        }
    }
}
