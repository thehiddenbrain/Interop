package org.p32h.interop.epa.pave.service.compare;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One reference to a resource, read from the text it was written as.
 *
 * <p>Two requests can name the same resource in different forms, and can name different
 * resources in the same form, so the form is kept apart from what is pointed at. The text is read
 * exactly as written: a reference with a space before or after it is not a reference, and is
 * reported as text this tool does not read as one.
 *
 * @param type    the resource type pointed at, or null when the text does not say
 * @param id      the id pointed at, or null when the text does not say
 * @param version the version named after the id, or null when none is
 * @param base    for an absolute url, everything before the type and id
 * @param raw     the text as written
 */
public record ReferenceText(Form form, String type, String id, String version, String base, String raw) {

    /** How a reference is written. */
    public enum Form {
        /** {@code Patient/123}. */
        RELATIVE("a resource type and an id"),
        /** {@code https://example.org/fhir/Patient/123}. */
        ABSOLUTE("an absolute url"),
        /** {@code urn:uuid:...}, which names the entry of a Bundle that carries the same full url. */
        URN("a urn"),
        /** An id on its own, where the place it sits says which resource type is meant. */
        BARE("an id on its own"),
        /** {@code #x}, a resource carried inside the one that refers to it. */
        CONTAINED("a pointer to a contained resource"),
        UNRECOGNISED("text this tool does not read as a reference");

        private final String words;

        Form(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    private static final String ID = "[^\\s/?#]+";
    private static final String TAIL = "([A-Z][A-Za-z]+)/(" + ID + ")(?:/_history/(" + ID + "))?";
    private static final Pattern RELATIVE = Pattern.compile(TAIL);
    // The base has a scheme and a host of its own, so the host is never taken for a resource type.
    private static final Pattern ABSOLUTE_TO_RESOURCE = Pattern.compile("((?i:https?)://[^/\\s]+(?:/\\S*?)?)/" + TAIL);
    private static final Pattern ABSOLUTE = Pattern.compile("(?i:https?)://\\S+");
    private static final Pattern URN = Pattern.compile("(?i:urn):\\S+");
    private static final Pattern BARE = Pattern.compile(ID);

    /**
     * @param bareType the resource type an id on its own stands for at this place, or null when a
     *                 bare id is not a reference there
     */
    public static ReferenceText parse(String raw, String bareType) {
        String text = raw == null ? "" : raw;
        if (text.startsWith("#") && BARE.matcher(text.substring(1)).matches()) {
            return new ReferenceText(Form.CONTAINED, null, null, null, null, raw);
        }
        if (URN.matcher(text).matches()) {
            return new ReferenceText(Form.URN, null, null, null, null, raw);
        }
        Matcher absolute = ABSOLUTE_TO_RESOURCE.matcher(text);
        if (absolute.matches()) {
            return new ReferenceText(Form.ABSOLUTE, absolute.group(2), absolute.group(3), absolute.group(4), absolute.group(1), raw);
        }
        if (ABSOLUTE.matcher(text).matches()) {
            return new ReferenceText(Form.ABSOLUTE, null, null, null, text, raw);
        }
        Matcher relative = RELATIVE.matcher(text);
        if (relative.matches()) {
            return new ReferenceText(Form.RELATIVE, relative.group(1), relative.group(2), relative.group(3), null, raw);
        }
        if (bareType != null && !bareType.isBlank() && BARE.matcher(text).matches()) {
            return new ReferenceText(Form.BARE, bareType, text, null, null, raw);
        }
        return new ReferenceText(Form.UNRECOGNISED, null, null, null, null, raw);
    }

    public boolean recognised() {
        return form != Form.UNRECOGNISED;
    }

    /**
     * True when the two are written the same way, so that any difference between them lies in
     * what they point at: the same form, the same version or none, and for two absolute urls the
     * same base.
     */
    public boolean sameFormAs(ReferenceText other) {
        return other != null && form == other.form && Objects.equals(version, other.version)
                && (form != Form.ABSOLUTE || Objects.equals(base, other.base));
    }

    /** Prints the form and never the text: an id is a label, and a sender can still put anything in one. */
    @Override
    public String toString() {
        return "ReferenceText[" + form + "]";
    }
}
