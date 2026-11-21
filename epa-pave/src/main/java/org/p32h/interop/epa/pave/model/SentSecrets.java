package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The secret values a response is masked with, each with the mask that stands for it: those one
 * request carried, and every other secret this tool holds for the configuration the request was
 * sent under, kept so that a response which repeats one of them is not shown with it.
 *
 * <p>A service can send a secret back. A FHIR server returns a Subscription with the header its
 * notifications are to carry, and an error page can quote the request it refused. The request side
 * hides a secret by the name of the variable it came from. This applies the same rule to what
 * comes back: the values held here are the ones this tool already knows to be secret by their
 * names, and a place in a response is masked only because it repeats one of them. Nothing here
 * judges a value by what it looks like. A service can also repeat a secret the request did not
 * carry, such as the client secret in an error answer to a call sent without sign-in, so the
 * secrets this tool holds and did not send are searched for as well.
 *
 * <p>A place repeats a secret when it holds the same characters, also where it writes some of them
 * as JSON escapes. A JSON body is where a secret usually comes back, and a JSON writer is free to
 * write a slash as {@code \/}, or a plus sign as a backslash followed by {@code u002B}. A secret
 * that comes back changed in any other way, such as percent-encoded or base64-encoded, is not
 * found.
 *
 * <p>Every secret is searched for in the same single pass over the text, along a tree of their
 * characters, so the cost of masking an answer of megabytes does not grow with the number of
 * secrets held.
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

    private static final SentSecrets NONE = new SentSecrets(List.of(), List.of(), false);

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

        /** Where the hit is, and nothing of the text around it. */
        @Override
        public String toString() {
            return "Hit[" + start + ", " + end + "]";
        }
    }

    private final List<Entry> searched;
    /** Kept, never searched for, so that a set merged into another counts each short value once. */
    private final List<Entry> shortOnes;
    private final boolean incomplete;
    private final Tree tree;

    private SentSecrets(List<Entry> searched, List<Entry> shortOnes, boolean incomplete) {
        this.searched = List.copyOf(searched);
        this.shortOnes = List.copyOf(shortOnes);
        this.incomplete = incomplete;
        this.tree = new Tree(this.searched);
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

    /** How many secrets, carried or held, are too short to be searched for. */
    public int tooShort() {
        return shortOnes.size();
    }

    /**
     * True when the request is known to have carried at least one secret whose value could not be
     * worked out: its shown form hides something, and what it hides could not be established. A
     * response to such a request cannot be searched for that secret, and the wire view has to say
     * so, because a view that shows the secret and says nothing would pass for one that was checked.
     */
    public boolean incomplete() {
        return incomplete;
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
        List<Hit> hits = tree.find(text, searched);
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

    /**
     * The secrets as one tree of their characters: a node per prefix that some secret starts with,
     * and at a node where a secret ends, that secret. Walking it from each place in a text meets
     * every secret that starts there in one walk, whatever the number of secrets.
     */
    private static final class Tree {

        private final char[][] keys;
        private final int[][] kids;
        private final int[] ends;
        private final java.util.BitSet first = new java.util.BitSet();

        private Tree(List<Entry> searched) {
            List<java.util.TreeMap<Character, Integer>> building = new ArrayList<>();
            List<Integer> ending = new ArrayList<>();
            building.add(new java.util.TreeMap<>());
            ending.add(-1);
            for (int index = 0; index < searched.size(); index++) {
                String value = searched.get(index).value();
                int node = 0;
                for (int i = 0; i < value.length(); i++) {
                    Integer next = building.get(node).get(value.charAt(i));
                    if (next == null) {
                        next = building.size();
                        building.get(node).put(value.charAt(i), next);
                        building.add(new java.util.TreeMap<>());
                        ending.add(-1);
                    }
                    node = next;
                }
                // Values are unique, so no two secrets end at the same node.
                ending.set(node, index);
            }
            keys = new char[building.size()][];
            kids = new int[building.size()][];
            ends = new int[building.size()];
            for (int node = 0; node < building.size(); node++) {
                java.util.TreeMap<Character, Integer> children = building.get(node);
                keys[node] = new char[children.size()];
                kids[node] = new int[children.size()];
                int at = 0;
                for (Map.Entry<Character, Integer> child : children.entrySet()) {
                    keys[node][at] = child.getKey();
                    kids[node][at] = child.getValue();
                    at++;
                }
                ends[node] = ending.get(node);
            }
            for (char c : keys[0]) {
                first.set(c);
            }
        }

        private int child(int node, char c) {
            int at = java.util.Arrays.binarySearch(keys[node], c);
            return at < 0 ? -1 : kids[node][at];
        }

        /**
         * Every place where the text holds a secret, by where it starts. One secret's places do not
         * overlap: from where one ends, that secret is looked for again. Where the text holds a
         * secret from one position in more than one reading, the reading that takes a JSON escape
         * as the character it stands for comes first, and the first reading met is the one taken.
         *
         * <p>The walk keeps its own stack of what is left to try, so its depth never takes a frame
         * of the thread's stack; it is at most the length of the longest secret. A position and a
         * node met twice from one start lead to nothing new and are not walked again, which is what
         * keeps a text of many backslashes from being read in every way it can be read.
         */
        private List<Hit> find(String text, List<Entry> searched) {
            List<Hit> hits = new ArrayList<>();
            if (keys[0].length == 0) {
                return hits;
            }
            int[] nextStart = new int[searched.size()];
            int[] nodes = new int[16];
            int[] positions = new int[16];
            int length = text.length();
            for (int start = 0; start < length; start++) {
                char here = text.charAt(start);
                if (here != '\\' && !first.get(here)) {
                    continue;
                }
                java.util.Set<Long> walked = null;
                nodes[0] = 0;
                positions[0] = start;
                int top = 1;
                while (top > 0) {
                    top--;
                    int node = nodes[top];
                    int at = positions[top];
                    if (walked != null && !walked.add(((long) node << 32) | at)) {
                        continue;
                    }
                    int ending = ends[node];
                    if (ending >= 0 && nextStart[ending] <= start) {
                        hits.add(new Hit(start, at, searched.get(ending).mask()));
                        nextStart[ending] = at;
                    }
                    if (at >= length) {
                        continue;
                    }
                    char c = text.charAt(at);
                    int plain = child(node, c);
                    int escape = -1;
                    int escapeLength = 0;
                    if (c == '\\') {
                        int read = escaped(text, at);
                        if (read >= 0) {
                            escape = child(node, (char) (read & 0xFFFF));
                            escapeLength = read >>> 16;
                        }
                    }
                    if (plain >= 0 && escape >= 0 && walked == null) {
                        walked = new java.util.HashSet<>();
                    }
                    if (top + 2 > nodes.length) {
                        nodes = java.util.Arrays.copyOf(nodes, nodes.length * 2);
                        positions = java.util.Arrays.copyOf(positions, positions.length * 2);
                    }
                    // Pushed last, so tried first: the escape read as the character it stands for.
                    if (plain >= 0) {
                        nodes[top] = plain;
                        positions[top] = at + 1;
                        top++;
                    }
                    if (escape >= 0) {
                        nodes[top] = escape;
                        positions[top] = at + escapeLength;
                        top++;
                    }
                }
            }
            return hits;
        }
    }

    /**
     * The JSON escape at a position: the character it stands for in the low 16 bits and its length
     * above them, or -1 when no escape starts there.
     */
    private static int escaped(String text, int at) {
        if (text.charAt(at) != '\\' || at + 1 >= text.length()) {
            return -1;
        }
        char kind = text.charAt(at + 1);
        if (kind == 'u') {
            if (at + 6 > text.length()) {
                return -1;
            }
            int code = 0;
            for (int i = at + 2; i < at + 6; i++) {
                int digit = Character.digit(text.charAt(i), 16);
                if (digit < 0) {
                    return -1;
                }
                code = code * 16 + digit;
            }
            return (6 << 16) | code;
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
        return stands < 0 ? -1 : (2 << 16) | stands;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SentSecrets that && shortOnes.equals(that.shortOnes) && incomplete == that.incomplete
                && searched.equals(that.searched);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * searched.hashCode() + shortOnes.hashCode()) + Boolean.hashCode(incomplete);
    }

    /** Prints the counts and nothing else. */
    @Override
    public String toString() {
        return "SentSecrets[" + searched.size() + " searched for, " + shortOnes.size() + " too short to search for"
                + (incomplete ? ", and at least one not known]" : "]");
    }

    /**
     * Collects the secrets of one request and those held beside it. A value that is given twice
     * keeps the name it was first given under, so the secrets a request carried are given first.
     */
    public static final class Builder {

        private final Map<String, String> maskByValue = new LinkedHashMap<>();
        private boolean incomplete;

        private Builder() {
        }

        /**
         * Says that the request carried a secret whose value could not be worked out, so the
         * values given here are not all the secrets it sent.
         */
        public Builder incomplete() {
            this.incomplete = true;
            return this;
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

        /**
         * Every secret of another set, kept under the mask it has there: the secrets this tool
         * holds for the configuration a request was sent under, whether or not the request
         * carried them. A value already given keeps its first mask, and a short one is counted once.
         */
        public Builder held(SentSecrets held) {
            if (held == null) {
                return this;
            }
            held.searched.forEach(entry -> add(entry.mask(), entry.value()));
            held.shortOnes.forEach(entry -> add(entry.mask(), entry.value()));
            incomplete |= held.incomplete;
            return this;
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
            List<Entry> shortOnes = new ArrayList<>();
            for (Map.Entry<String, String> secret : maskByValue.entrySet()) {
                Entry entry = new Entry(secret.getValue(), secret.getKey());
                if (secret.getKey().length() < SHORTEST_SEARCHED) {
                    shortOnes.add(entry);
                } else {
                    searched.add(entry);
                }
            }
            return searched.isEmpty() && shortOnes.isEmpty() && !incomplete ? NONE : new SentSecrets(searched, shortOnes, incomplete);
        }

        /** Prints how many values it holds and none of them. */
        @Override
        public String toString() {
            return "SentSecrets.Builder[" + maskByValue.size() + "]";
        }
    }
}
