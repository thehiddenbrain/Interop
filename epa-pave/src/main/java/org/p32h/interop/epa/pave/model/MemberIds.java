package org.p32h.interop.epa.pave.model;

/**
 * Member ID normalisation.
 *
 * <p>Member IDs do not share one format. Some lines of business write the ID as nine characters,
 * three spaces and a two digit suffix; others write it with no spaces. The service accepts only the
 * no-space form, which is a constraint of that platform. Normalising means removing whitespace and
 * nothing else: case and every other character are left exactly as typed, because changing them
 * would be guessing.
 */
public final class MemberIds {

    private MemberIds() {
    }

    /** Both forms of a typed member ID. A null or blank input yields a blank sent form. */
    public static MemberIdForms forms(String typed) {
        String original = typed == null ? "" : typed;
        String sent = noSpace(original);
        return new MemberIdForms(original, sent, !original.equals(sent));
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
