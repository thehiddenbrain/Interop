package org.p32h.interop.epa.pave.model;

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

    private static final Set<String> SECRET_WORDS = Set.of("secret", "password", "passwd", "pwd", "pass", "pw", "passphrase",
            "token", "key", "apikey", "credential", "credentials", "creds", "private", "auth", "authorization", "authentication",
            "bearer", "cookie", "signature", "jwt", "assertion", "session", "sessionid", "passcode", "pin", "otp", "sas", "sig",
            "hmac", "dsn", "xsrf", "csrf", "pfx", "accesscode", "connectionstring");

    /**
     * Credentials whose usual name is two words, such as connectionString or access_code. The
     * words of the name are joined and looked for whole, so neither word alone is made a secret.
     */
    private static final List<String> TWO_WORD_SECRETS = List.of("connectionstring", "accesscode", "sharedaccesssignature");

    /**
     * Words long enough to be recognised inside a longer one, so a name written with no separator,
     * such as clientsecret, ACCESSTOKEN or JSESSIONID, is still masked.
     */
    private static final List<String> SECRET_ENDINGS = List.of("secret", "password", "passwd", "passphrase", "token", "apikey",
            "credential", "credentials", "bearer", "cookie", "signature", "assertion", "sessionid");

    /** The schemes whose name may be shown beside a redacted credential, since the scheme is not a secret. */
    private static final List<String> AUTH_SCHEMES = List.of("Basic", "Bearer", "Digest", "Negotiate", "NTLM", "Token", "ApiKey",
            "AWS4-HMAC-SHA256");

    /**
     * A name ending in one of these describes where or what a secret is, and is not itself one.
     * The list is kept short on purpose: masking a name that was harmless costs nothing, and
     * showing one that was a credential cannot be undone.
     */
    private static final Set<String> DESCRIPTOR_WORDS = Set.of("url", "uri", "endpoint", "host", "path", "id", "type",
            "name", "scope", "scopes", "ttl", "expiry", "enabled", "prefix", "algorithm", "alg", "method", "mode",
            "grant", "realm", "audience", "issuer", "format");

    private Secrets() {
    }

    /**
     * True when a variable name reads as a credential, for example clientSecret, api_key or
     * access-token. A name such as tokenUrl or keyId is not: its last word says it is a locator or
     * a label. Two things mark a variable as secret regardless of its name: Postman's own secret
     * type, and a value read from the operating system environment.
     */
    public static boolean looksSecret(String variableName) {
        List<String> words = words(variableName);
        if (words.isEmpty()) {
            return false;
        }
        String last = words.get(words.size() - 1);
        if (DESCRIPTOR_WORDS.contains(last)) {
            // "secret id" and "session id" are credentials whatever the last word says.
            String before = words.size() > 1 ? words.get(words.size() - 2) : "";
            boolean stillSecret = last.equals("id") && (before.equals("secret") || before.equals("session"));
            if (!stillSecret) {
                return false;
            }
        }
        String joined = String.join("", words);
        return words.stream().anyMatch(Secrets::secretWord) || TWO_WORD_SECRETS.stream().anyMatch(joined::contains);
    }

    /**
     * True when one word of a name reads as a credential: it is on the list, it ends with one of
     * the longer entries, or it is a longer word ending in "key". Where this errs, it errs toward
     * masking, because masking a harmless name costs nothing and showing a credential cannot be undone.
     */
    private static boolean secretWord(String word) {
        if (SECRET_WORDS.contains(word)) {
            return true;
        }
        if (word.length() > 3 && word.endsWith("key")) {
            return true;
        }
        return SECRET_ENDINGS.stream().anyMatch(word::endsWith);
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

    /**
     * The form in which the value of a credential header is shown. The value itself is never
     * shown, in whole or in part. For Authorization and Proxy-Authorization the scheme is kept
     * when it is one of the well known ones, because knowing whether Basic or Bearer went out
     * helps a tester and reveals nothing. Any other first word could be the credential itself,
     * so it is not kept. Applying this to a value that is already redacted leaves it unchanged.
     */
    public static String redactCredential(String headerName, String value) {
        if (value == null || Texts.blank(headerName)) {
            return REDACTED;
        }
        String name = headerName.strip().toLowerCase(Locale.ROOT);
        String text = value.strip();
        int space = text.indexOf(' ');
        if (space > 0 && (name.equals("authorization") || name.equals("proxy-authorization"))) {
            String first = text.substring(0, space);
            for (String scheme : AUTH_SCHEMES) {
                if (scheme.equalsIgnoreCase(first)) {
                    return scheme + " " + REDACTED;
                }
            }
        }
        return REDACTED;
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
            // A new word starts at a separator, at a capital after a lower case letter, and where
            // letters meet digits, so accessToken1 and token2 are read as token followed by a number.
            boolean boundary = !Character.isLetterOrDigit(c)
                    || (Character.isUpperCase(c) && Character.isLowerCase(previous))
                    || (Character.isDigit(c) != Character.isDigit(previous) && Character.isLetterOrDigit(previous));
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
