package org.p32h.interop.epa.pave.model;

/**
 * Member ID normalisation.
 *
 * <p>Member IDs do not share one format. Some lines of business write the ID as nine characters,
 * three spaces and a two digit suffix; others write it with no spaces. Which form a destination
 * accepts is a property of that destination, so the environment states it as a {@link MemberIdRule}.
 * The no-space form stays the key the tool keeps a member under, whatever is sent, so one member is
 * one key. Normalising means removing whitespace and nothing else: case and every other character
 * are left exactly as typed, because changing them would be guessing.
 */
public final class MemberIds {

    private MemberIds() {
    }

    /** Both forms of a typed member ID under the packed rule. A null or blank input yields a blank sent form. */
    public static MemberIdForms forms(String typed) {
        return forms(typed, MemberIdRule.PACKED);
    }

    /**
     * Both forms of a typed member ID under a rule, with the rule named and, when it could not
     * apply, the note saying so. A null rule is read as packed, the rule every environment had
     * before rules could be chosen.
     */
    public static MemberIdForms forms(String typed, MemberIdRule rule) {
        MemberIdRule applied = rule == null ? MemberIdRule.PACKED : rule;
        String original = typed == null ? "" : typed;
        String sent = applied.apply(original);
        return new MemberIdForms(original, sent, !original.equals(sent), applied.word(), applied.note(original));
    }

    /** The ID as it goes on the wire under a rule. A null rule is read as packed. */
    public static String sent(String typed, MemberIdRule rule) {
        return (rule == null ? MemberIdRule.PACKED : rule).apply(typed);
    }

    /** The ID with every whitespace character removed. Null in, empty string out. */
    public static String noSpace(String typed) {
        if (typed == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(typed.length());
        typed.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).forEach(out::appendCodePoint);
        return out.toString();
    }
}
