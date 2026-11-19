package org.p32h.interop.epa.pave.service.compare;

/** How a place in a document is written: for people as a dotted path, for a screen as a JSON Pointer. */
public final class Paths {

    private Paths() {
    }

    /** A key as it appears in a path. One that holds path punctuation is quoted, so the path stays unambiguous. */
    public static String display(String key) {
        boolean plain = !key.isEmpty() && key.chars().noneMatch(c -> c == '.' || c == '[' || c == ']' || c == '"' || Character.isWhitespace(c));
        return plain ? key : "\"" + key.replace("\"", "\\\"") + "\"";
    }

    public static String join(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }

    /** The JSON Pointer of a member, given the pointer of the object that holds it. */
    public static String member(String pointer, String key) {
        return pointer + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    /** The JSON Pointer of a list element, given the pointer of the list. */
    public static String element(String pointer, int index) {
        return pointer + "/" + index;
    }
}
