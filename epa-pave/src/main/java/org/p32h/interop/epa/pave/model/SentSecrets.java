package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The secret values one request carried, each with the mask that stands for it, kept so that a
 * response which repeats one of them is not shown with it.
 *
 * <p>A service can send a secret back. A FHIR server returns a Subscription with the header its
 * notifications are to carry, and an error page can quote the request it refused. The request side
 * hides a secret by the name of the variable it came from. This applies the same rule to what
 * comes back: the values held here are the ones this tool already knows to be secret by their
 * names, and a place in a response is masked only because it repeats one of them. Nothing here
 * judges a value by what it looks like.
 *
 * <p>A place repeats a secret when it holds the same characters, also where it writes some of them
 * as JSON escapes. A JSON body is where a secret usually comes back, and a JSON writer is free to
 * write a slash as {@code \/} or a plus sign as {@code +}. A secret that comes back changed in
 * any other way, such as percent-encoded or base64-encoded, is not found.
 *
 * <p>This holds real secret values. It is never serialised and never printed, and it has no method
 * that hands a value back: all it offers is to mask a text.
 */
@JsonIgnoreType
public final class SentSecrets {

    /**
     * A secret shorter than this is not searched for. A value of a few characters would be found
     * by accident inside ordinary words, codes and numbers, and masking those would hide what the
     * service said while protecting nothing. How many secrets were left out for this reason is
     * kept, so the wire view can say so.
     */
    public static final int SHORTEST_SEARCHED = 6;

    private static final SentSecrets NONE = new SentSecrets(List.of(), 0);

    /**
     * A text as it may be shown.
     *
     * @param text   the text with every place that repeats a secret replaced by that secret's mask
     * @param places how many places were replaced
     */
    public record Masked(String text, int places) {

        /** Prints the count only. The text is a response body or a header value, which can hold member data. */
        @Override
        public String toString() {
            return "Masked[" + places + " places]";
        }
    }

    private record Entry(String mask, String value) {

        @Override
        public String toString() {
            return "Entry[" + mask + "]";
        }
    }

    private record Hit(int start, int end, String mask) {
    }

    private final List<Entry> searched;
    private final int tooShort;

    private SentSecrets(List<Entry> searched, int tooShort) {
        this.searched = List.copyOf(searched);
        this.tooShort = tooShort;
    }

    /** A request that carried no secret. */
    public static SentSecrets none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** How many secrets a response is searched for. */
    public int searched() {
        return searched.size();
    }

    /** How many secrets the request carried that are too short to be searched for. */
    public int tooShort() {
        return tooShort;
    }

    /**
     * The text with every place that repeats one of the secrets shown as that secret's mask.
     *
     * <p>Places are found in the text as it was received and are never looked for in a mask, so a
     * secret that happens to read like part of a mask cannot be found there. Where one secret sits
     * inside a longer one, the longer one's mask covers it. Where two overlap part way, the whole
     * stretch is replaced and carries both masks, so no part of either is left showing.
     */
    public Masked mask(String text) {
        if (text == null || text.isEmpty() || searched.isEmpty()) {
            return new Masked(text, 0);
        }
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : searched) {
            find(text, entry, hits);
        }
        if (hits.isEmpty()) {
            return new Masked(text, 0);
        }
        // By where it starts, and the longer first where two start together, so a secret inside a
        // longer one is met after it and is already covered.
        hits.sort(Comparator.comparingInt(Hit::start).thenComparing(Comparator.comparingInt(Hit::end).reversed()));
        StringBuilder out = new StringBuilder(text.length());
        int places = 0;
        int covered = 0;
        for (Hit hit : hits) {
            if (hit.end() <= covered) {
                continue;
            }
            if (hit.start() > covered) {
                out.append(text, covered, hit.start());
            }
            // A hit that starts inside the stretch before it and runs past its end adds its mask
            // straight after the other, and the stretch grows to the end of both.
            out.append(hit.mask());
            places++;
            covered = hit.end();
        }
        out.append(text, covered, text.length());
        return new Masked(out.toString(), places);
    }

    /** Adds every place where the text holds the secret, left to right, none overlapping another of the same secret. */
    private static void find(String text, Entry entry, List<Hit> hits) {
        String value = entry.value();
        char first = value.charAt(0);
        for (int start = 0; start < text.length(); start++) {
            char here = text.charAt(start);
            if (here != first && here != '\\') {
                continue;
            }
            int end = endOfSecret(text, start, value, 0);
            if (end > start) {
                hits.add(new Hit(start, end, entry.mask()));
                start = end - 1;
            }
        }
    }

    /**
     * Where the secret ends when the text holds it from a given position, or -1 when it does not.
     * Each character of the secret is met either as itself or as a JSON escape that stands for it.
     * Only a backslash can be read both ways, as itself and as the start of {@code \\}, and then
     * both readings are tried.
     *
     * @param from the position in the text to read from
     * @param done how many characters of the secret have been met already
     */
    private static int endOfSecret(String text, int from, String secret, int done) {
        int at = from;
        for (int i = done; i < secret.length(); i++) {
            if (at >= text.length()) {
                return -1;
            }
            char wanted = secret.charAt(i);
            int escaped = escapeLength(text, at, wanted);
            boolean plain = text.charAt(at) == wanted;
            if (escaped > 0 && plain) {
                int end = endOfSecret(text, at + escaped, secret, i + 1);
                if (end >= 0) {
                    return end;
                }
                at++;
            } else if (escaped > 0) {
                at += escaped;
            } else if (plain) {
                at++;
            } else {
                return -1;
            }
        }
        return at;
    }

    /** The length of a JSON escape at a position when it stands for the wanted character. Otherwise 0. */
    private static int escapeLength(String text, int at, char wanted) {
        if (text.charAt(at) != '\\' || at + 1 >= text.length()) {
            return 0;
        }
        char kind = text.charAt(at + 1);
        if (kind == 'u') {
            if (at + 6 > text.length()) {
                return 0;
            }
            int code = 0;
            for (int i = at + 2; i < at + 6; i++) {
                int digit = Character.digit(text.charAt(i), 16);
                if (digit < 0) {
                    return 0;
                }
                code = code * 16 + digit;
            }
            return code == wanted ? 6 : 0;
        }
        int stands = switch (kind) {
            case '"', '\\', '/' -> kind;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> -1;
        };
        return stands == wanted ? 2 : 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SentSecrets that && tooShort == that.tooShort && searched.equals(that.searched);
    }

    @Override
    public int hashCode() {
        return 31 * searched.hashCode() + tooShort;
    }

    /** Prints the counts and nothing else. */
    @Override
    public String toString() {
        return "SentSecrets[" + searched.size() + " searched for, " + tooShort + " too short to search for]";
    }

    /** Collects the secrets of one request. A value that is given twice keeps the name it was first given under. */
    public static final class Builder {

        private final Map<String, String> maskByValue = new LinkedHashMap<>();

        private Builder() {
        }

        /** The value of a secret variable, masked by the variable's name exactly as the request side masks it. */
        public Builder variable(String name, String value) {
            return add(Secrets.mask(name), value);
        }

        /** The access token the request carried as its bearer credential. It came from no variable, so it is named for what it is. */
        public Builder accessToken(String value) {
            return add(Secrets.mask("access token"), value);
        }

        /**
         * The credential a header carried, for a header that is a credential by its own name. It
         * may have been typed straight into a collection, so it is named after the header.
         */
        public Builder header(String headerName, String credential) {
            return add(Secrets.mask(Texts.orElse(headerName, "").strip() + " header"), credential);
        }

        private Builder add(String mask, String value) {
            if (Texts.present(value)) {
                // White space around a secret is not part of it, and a service that sends the
                // secret back may well leave it off.
                maskByValue.putIfAbsent(value.strip(), mask);
            }
            return this;
        }

        public SentSecrets build() {
            List<Entry> searched = new ArrayList<>();
            int tooShort = 0;
            for (Map.Entry<String, String> secret : maskByValue.entrySet()) {
                if (secret.getKey().length() < SHORTEST_SEARCHED) {
                    tooShort++;
                } else {
                    searched.add(new Entry(secret.getValue(), secret.getKey()));
                }
            }
            return searched.isEmpty() && tooShort == 0 ? NONE : new SentSecrets(searched, tooShort);
        }

        /** Prints how many values it holds and none of them. */
        @Override
        public String toString() {
            return "SentSecrets.Builder[" + maskByValue.size() + "]";
        }
    }
}
