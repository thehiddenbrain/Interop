package org.p32h.interop.epa.pave.postman;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code {{placeholder}}} references in text, the way Postman does.
 *
 * <p>A reference is two opening braces, a name that contains no brace, and two closing braces.
 * White space around the name is not part of it. A single-brace {@code {name}} is not a
 * placeholder: it is a url token, which is filled from a different place and percent-encoded.
 */
public final class Placeholders {

    private static final Pattern REFERENCE = Pattern.compile("\\{\\{([^{}]+?)}}");

    private Placeholders() {
    }

    /**
     * One stretch of a template: either literal text or one placeholder.
     *
     * @param literal the text as written. For a placeholder this is the reference, braces included
     * @param name    the placeholder name, or null for literal text
     */
    public record Part(String literal, String name) {

        public boolean placeholder() {
            return name != null;
        }
    }

    /** The template cut into literal text and placeholders, in order. Joined, the literals give the template back. */
    public static List<Part> parts(String template) {
        List<Part> parts = new ArrayList<>();
        if (template == null || template.isEmpty()) {
            return parts;
        }
        Matcher matcher = REFERENCE.matcher(template);
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1).strip();
            if (name.isEmpty()) {
                // Braces around nothing name no variable. They stay in the text as written.
                continue;
            }
            if (matcher.start() > last) {
                parts.add(new Part(template.substring(last, matcher.start()), null));
            }
            parts.add(new Part(matcher.group(), name));
            last = matcher.end();
        }
        if (last < template.length()) {
            parts.add(new Part(template.substring(last), null));
        }
        return parts;
    }

    /** The distinct placeholder names in a template, in the order they first appear. */
    public static List<String> names(String template) {
        Set<String> names = new LinkedHashSet<>();
        for (Part part : parts(template)) {
            if (part.placeholder()) {
                names.add(part.name());
            }
        }
        return List.copyOf(names);
    }

    public static boolean any(String template) {
        return !names(template).isEmpty();
    }

    /**
     * True when the text is made of placeholders and white space and nothing else. Such text holds
     * no value of its own: showing it shows only the names it refers to.
     */
    public static boolean referencesOnly(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean any = false;
        for (Part part : parts(text)) {
            if (part.placeholder()) {
                any = true;
            } else if (!part.literal().isBlank()) {
                return false;
            }
        }
        return any;
    }

    /** The placeholder name when the whole text, apart from surrounding white space, is one placeholder. Otherwise null. */
    public static String sole(String text) {
        if (text == null) {
            return null;
        }
        List<Part> parts = parts(text.strip());
        return parts.size() == 1 && parts.get(0).placeholder() ? parts.get(0).name() : null;
    }
}
