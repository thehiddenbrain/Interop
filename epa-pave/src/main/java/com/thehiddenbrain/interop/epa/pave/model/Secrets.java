package com.thehiddenbrain.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which variables are secrets and how a secret is shown.
 *
 * <p>Redaction is by variable name, never by matching the value. A value-based rule misses a
 * secret that does not look like one, and a name-based rule cannot.
 */
public final class Secrets {

    /** Shown in place of a bearer token on the wire view. */
    public static final String REDACTED_BEARER = "Bearer [redacted]";

    /** Shown in place of any header value that is a credential in its own right. */
    public static final String REDACTED = "[redacted]";

    private static final Set<String> SECRET_WORDS = Set.of("secret", "password", "passwd", "pwd", "token", "key",
            "apikey", "credential", "credentials", "private", "authorization", "bearer", "signature", "jwt", "assertion");

    /** A name ending in one of these describes where or what a secret is, and is not itself one. */
    private static final Set<String> DESCRIPTOR_WORDS = Set.of("url", "uri", "endpoint", "host", "path", "id", "type",
            "name", "scope", "scopes", "ttl", "expiry", "enabled", "header", "prefix", "algorithm", "alg");

    private Secrets() {
    }

    /**
     * True when a variable name reads as a credential, for example clientSecret, api_key or
     * access-token. A name such as tokenUrl or keyId is not: its last word says it is a locator or
     * a label. Postman's own secret type always marks a variable as secret regardless of this.
     */
    public static boolean looksSecret(String variableName) {
        List<String> words = words(variableName);
        if (words.isEmpty()) {
            return false;
        }
        if (DESCRIPTOR_WORDS.contains(words.get(words.size() - 1))) {
            return false;
        }
        return words.stream().anyMatch(SECRET_WORDS::contains);
    }

    /** True when a header carries a credential by its own name, whatever variable its value came from. */
    public static boolean credentialHeader(String headerName) {
        if (Texts.blank(headerName)) {
            return false;
        }
        String name = headerName.strip().toLowerCase(Locale.ROOT);
        return name.equals("authorization") || name.equals("proxy-authorization") || name.equals("cookie")
                || name.equals("set-cookie") || looksSecret(headerName);
    }

    /** The text shown wherever the value of the named secret would otherwise appear. */
    public static String mask(String variableName) {
        return "[secret " + variableName + "]";
    }

    private static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        if (Texts.blank(name)) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        char previous = 0;
        for (char c : name.strip().toCharArray()) {
            boolean boundary = !Character.isLetterOrDigit(c)
                    || (Character.isUpperCase(c) && Character.isLowerCase(previous));
            if (boundary && current.length() > 0) {
                out.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            }
            previous = c;
        }
        if (current.length() > 0) {
            out.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
