package org.p32h.interop.epa.pave.model;

/**
 * How a member ID is written in the requests of one environment.
 *
 * <p>Destinations disagree: the vendor's sandbox routes only the packed form of a card ID that is
 * printed with spaces, and other destinations want the card form or its first nine characters. A
 * wrong form fails as a routing error far from its cause, so the rule is chosen per environment and
 * named on every call rather than guessed.
 *
 * <p>Spaced and split are defined only for an ID whose packed form has 11 characters. Any other ID
 * is sent packed under them, and {@link #note(String)} says so, because shortening or padding an ID
 * of another length would invent a member.
 *
 * <p>No form produced here is ever a FHIR resource id: the spaced form holds spaces, which a
 * resource id cannot, and the patient keeps its own id.
 */
public enum MemberIdRule {
    PACKED("packed", "packed: spaces removed before sending"),
    SPACED("spaced", "spaced: nine characters, three spaces, then the last two, as on the card"),
    SPLIT("split", "split: the first nine characters of an 11-character ID");

    /** The only packed length spaced and split are defined for: nine characters and a two character suffix. */
    public static final int CARD_LENGTH = 11;
    private static final int PREFIX_LENGTH = 9;
    private static final String CARD_GAP = "   ";

    private final String word;
    private final String words;

    MemberIdRule(String word, String words) {
        this.word = word;
        this.words = words;
    }

    /** The rule's name as the screens and the wire view write it: packed, spaced or split. */
    public String word() {
        return word;
    }

    /** The rule in a few words, for the option a tester picks it from. */
    public String words() {
        return words;
    }

    /**
     * The ID as it is sent under this rule. Null in, empty string out, as {@link MemberIds#noSpace}
     * does, so a missing ID never turns into the text "null".
     */
    public String apply(String typed) {
        String packed = MemberIds.noSpace(typed);
        if (this == PACKED || packed.codePointCount(0, packed.length()) != CARD_LENGTH) {
            return packed;
        }
        int cut = packed.offsetByCodePoints(0, PREFIX_LENGTH);
        return this == SPLIT ? packed.substring(0, cut) : packed.substring(0, cut) + CARD_GAP + packed.substring(cut);
    }

    /**
     * Why the rule did not apply to this ID, or null when it applied. Packed always applies. A blank
     * ID has no note: nothing is sent for it, so there is nothing to explain.
     */
    public String note(String typed) {
        if (this == PACKED) {
            return null;
        }
        String packed = MemberIds.noSpace(typed);
        int length = packed.codePointCount(0, packed.length());
        if (length == 0 || length == CARD_LENGTH) {
            return null;
        }
        return "The " + word + " rule applies to an " + CARD_LENGTH + "-character ID; this one has " + length
                + ", so it was sent packed.";
    }

    /**
     * The rule a stored or posted name names, or null when it names none. Unknown stays unknown:
     * the caller refuses it at its field, never reads it as packed.
     */
    public static MemberIdRule parse(String name) {
        if (Texts.blank(name)) {
            return null;
        }
        String wanted = name.strip();
        for (MemberIdRule rule : values()) {
            if (rule.name().equalsIgnoreCase(wanted)) {
                return rule;
            }
        }
        return null;
    }
}
