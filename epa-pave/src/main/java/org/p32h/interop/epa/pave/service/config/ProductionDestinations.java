package org.p32h.interop.epa.pave.service.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.postman.Placeholders;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;

/**
 * The destinations an environment's own values point at: the scheme, host, port and base path of
 * every variable in force whose value, resolved against that environment alone, reads as a url and
 * forms the url of a request.
 *
 * <p>A variable whose name says it is the address of something a request is never sent to as a
 * call, such as the callback the vendor notifies or the endpoint an access token is fetched from,
 * is not a destination, unless a collection request builds its own url on it. Production and the
 * sandbox often share those two addresses, and treating them as production would refuse the
 * sandbox for an address no write goes to.
 *
 * <p>For a production environment these are the addresses a write must be unlocked for. For any
 * other environment they are compared with those, so that production cannot be uploaded again
 * under a sandbox's kind. Nothing from outside the environment is used: not the operating system,
 * whose values are secrets, and not a value typed for a run.
 *
 * <p>A secret of the environment never becomes part of an address that is shown or kept. Where a
 * secret sits in the path, the address ends before the segment that holds it, which is broader
 * and so errs toward production. Where a secret sits in the host or the port, the address is used
 * for judging while that environment is stored and is marked as secret, so that no view shows it
 * and the store never writes it down.
 */
final class ProductionDestinations {

    /** Resolves nested placeholders within one environment. It reads no operating system variable and generates nothing. */
    private static final VariableResolver WITHIN = new VariableResolver(name -> null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "");

    private ProductionDestinations() {
    }

    /**
     * Words that name an address requests are not sent to as calls: a callback, an authentication
     * endpoint, the audience a token is issued for, a page for people to read.
     */
    private static final Set<String> NOT_A_CALL = Set.of("callback", "callbacks", "notification", "notifications", "notify",
            "webhook", "webhooks", "redirect", "token", "oauth", "oauth2", "auth", "login", "issuer", "jwks", "sso", "idp", "openid",
            "audience", "aud", "authority", "realm", "scope", "scopes", "docs", "doc", "documentation", "portal", "homepage", "website",
            "help", "support", "logo", "terms", "privacy");

    /** Stands in for the text of a secret, so that where a secret starts in a value is known exactly. */
    private static final char SECRET_MARK = '\u0001';

    /**
     * One destination of an environment.
     *
     * @param destination scheme, host, port and base path
     * @param secret      true when a secret of the environment is part of the host or the port, so
     *                    the destination may be used for judging and is never shown or kept
     * @param variable    the name of the variable it was read from
     * @param written     the value of that variable as the file writes it, for quoting, or null
     *                    when the variable is a secret. See {@link UrlNormal#asWritten}
     */
    record Found(UrlNormal.Destination destination, boolean secret, String variable, String written) {

        Found(UrlNormal.Destination destination, boolean secret) {
            this(destination, secret, null, null);
        }

        /** Prints the variable name only: the destination can be one built from a secret. */
        @Override
        public String toString() {
            return "Found[" + variable + (secret ? ", secret" : "") + "]";
        }
    }

    /**
     * @param urlForming the names of variables that a collection request puts where its url names
     *                   its scheme, host and port. Such a variable is a destination whatever its name says
     */
    static List<Found> of(PostmanEnvironment environment, Set<String> urlForming) {
        return read(environment, urlForming, false);
    }

    /**
     * The destinations of a production environment, as they are remembered. The same as
     * {@link #of}, with one difference: an address with a scheme and a host that cannot be read
     * the way the HTTP client reads it is never dropped, and protects its whole host, every port
     * and every path on it. This tool cannot know which port and path the client would make of
     * it, and a destination that cannot be read is never "not production".
     */
    static List<Found> production(PostmanEnvironment environment, Set<String> urlForming) {
        return read(environment, urlForming, true);
    }

    private static List<Found> read(PostmanEnvironment environment, Set<String> urlForming, boolean production) {
        Set<Found> found = new LinkedHashSet<>();
        if (environment == null) {
            return List.of();
        }
        Map<String, PostmanVariable> inForce = environment.inForce();
        List<VariableLayer> layers = List.of(VariableLayer.environment(environment.name(), inForce));
        for (PostmanVariable variable : inForce.values()) {
            if (!variable.hasValue() || (notACall(variable.key()) && !urlForming.contains(variable.key()))) {
                continue;
            }
            Resolution resolved = WITHIN.resolve(variable.value(), layers);
            String value = resolved.send();
            if (value == null || !ProductionSigns.looksLikeUrl(value)) {
                continue;
            }
            String written = variable.secret() ? null : variable.value();
            // A secret whose own value is an address is judged by and never shown or kept, like a
            // host built from one.
            UrlNormal.Destination whole = variable.secret() ? UrlNormal.destination(value) : null;
            Found destination = variable.secret() ? (whole == null ? null : new Found(whole, true)) : withoutSecrets(resolved, value);
            if (production && hasSchemeAndHost(value.strip()) && (destination == null || !destination.destination().readable())) {
                UrlNormal.Destination host = UrlNormal.wholeHost(value);
                // A secret anywhere in the value keeps the host out of every view and out of the store.
                boolean secret = variable.secret() || resolved.pieces().stream().anyMatch(piece -> piece.kind() == Resolution.Kind.SECRET);
                destination = host == null ? null : new Found(host, secret);
            }
            if (destination != null) {
                found.add(new Found(destination.destination(), destination.secret(), variable.key(), written));
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * The variables considered by {@link #of} whose value, resolved against the environment alone,
     * has a scheme and a host and yet no destination the HTTP client would read the same way.
     */
    static List<PostmanVariable> unreadableVariables(PostmanEnvironment environment, Set<String> urlForming) {
        return variablesWhere(environment, urlForming, value -> {
            UrlNormal.Destination destination = UrlNormal.destination(value);
            return destination == null || !destination.readable();
        });
    }

    /**
     * The names of the variables considered by {@link #of} whose value, resolved against the
     * environment alone, has a scheme and a host and yet no destination the HTTP client would read
     * the same way. A write to such an address is refused, and the environment says so before
     * anything is sent.
     */
    static List<String> unreadable(PostmanEnvironment environment, Set<String> urlForming) {
        return unreadableVariables(environment, urlForming).stream().map(PostmanVariable::key).toList();
    }

    /**
     * The names of the variables considered by {@link #of} whose address, resolved against the
     * environment alone, holds an encoded slash, backslash, semicolon or dot segment in its path.
     * No request is sent to such an address.
     */
    static List<String> encodedSeparators(PostmanEnvironment environment, Set<String> urlForming) {
        return namesWhere(environment, urlForming, value -> UrlNormal.encodedSeparator(value) != null);
    }

    private static List<String> namesWhere(PostmanEnvironment environment, Set<String> urlForming,
                                           java.util.function.Predicate<String> holds) {
        return variablesWhere(environment, urlForming, holds).stream().map(PostmanVariable::key).toList();
    }

    private static List<PostmanVariable> variablesWhere(PostmanEnvironment environment, Set<String> urlForming,
                                                        java.util.function.Predicate<String> holds) {
        List<PostmanVariable> names = new ArrayList<>();
        if (environment == null) {
            return names;
        }
        Map<String, PostmanVariable> inForce = environment.inForce();
        List<VariableLayer> layers = List.of(VariableLayer.environment(environment.name(), inForce));
        for (PostmanVariable variable : inForce.values()) {
            if (!variable.hasValue() || (notACall(variable.key()) && !urlForming.contains(variable.key()))) {
                continue;
            }
            Resolution resolved = WITHIN.resolve(variable.value(), layers);
            String value = resolved.send();
            if (value != null && hasSchemeAndHost(value.strip()) && holds.test(value)) {
                names.add(variable);
            }
        }
        return names;
    }

    /** True when the text starts with a scheme followed by {@code ://} and at least one character of authority. */
    private static boolean hasSchemeAndHost(String value) {
        int scheme = value.indexOf("://");
        return scheme > 0 && value.substring(0, scheme).matches("[A-Za-z][A-Za-z0-9+.-]*") && authorityEnd(value) > scheme + 3;
    }

    /** The destinations alone, for comparing with the addresses another environment would use. */
    static List<UrlNormal.Destination> destinations(PostmanEnvironment environment, Set<String> urlForming) {
        return of(environment, urlForming).stream().map(Found::destination).toList();
    }

    /**
     * The destination of a resolved value with every secret left out of it: the path ends before
     * the segment where the first secret starts, and a secret in the host or the port marks the
     * whole destination as secret.
     */
    private static Found withoutSecrets(Resolution resolved, String value) {
        StringBuilder marked = new StringBuilder();
        for (Resolution.Piece piece : resolved.pieces()) {
            marked.append(piece.kind() == Resolution.Kind.SECRET ? String.valueOf(SECRET_MARK) : piece.send());
        }
        String text = marked.toString();
        if (text.indexOf(SECRET_MARK) < 0) {
            UrlNormal.Destination destination = UrlNormal.destination(value);
            return destination == null ? null : new Found(destination, false);
        }
        int markedEnd = authorityEnd(text);
        boolean inHost = text.substring(0, markedEnd).indexOf(SECRET_MARK) >= 0;
        String path = text.substring(markedEnd);
        int mark = path.indexOf(SECRET_MARK);
        if (mark >= 0) {
            int slash = path.lastIndexOf('/', mark);
            path = slash < 0 ? "" : path.substring(0, slash);
        }
        // With a secret in the host, the host is read from the value as sent; it is never shown.
        String authority = inHost ? value.substring(0, authorityEnd(value)) : text.substring(0, markedEnd);
        UrlNormal.Destination destination = UrlNormal.destination(authority + path);
        return destination == null ? null : new Found(destination, inHost);
    }

    /** Where the scheme and the authority of a url end: the first slash, question mark or hash after {@code ://}, or the end. */
    private static int authorityEnd(String url) {
        int scheme = url.indexOf("://");
        int at = scheme < 0 ? 0 : scheme + 3;
        while (at < url.length() && "/?#".indexOf(url.charAt(at)) < 0) {
            at++;
        }
        return at;
    }

    /**
     * The names of the variables the urls of these collections build their scheme, host and port
     * from: every placeholder written before the path starts.
     */
    static Set<String> urlForming(List<StoredCollection> collections) {
        Set<String> names = new LinkedHashSet<>();
        for (StoredCollection collection : collections) {
            for (StoredCollection.Mapped request : collection.requests()) {
                String url = request.request().url();
                if (url != null) {
                    names.addAll(Placeholders.names(authority(url.strip())));
                }
            }
        }
        return names;
    }

    /** The text of a url template up to where its path starts: the scheme and the authority, or everything before the first slash. */
    private static String authority(String url) {
        int scheme = url.indexOf("://");
        int from = scheme > 0 && !url.substring(0, scheme).contains("{{") ? scheme + 3 : 0;
        // A placeholder can hold a slash, so the search for the path skips over each one whole.
        int at = from;
        while (at < url.length()) {
            if (url.startsWith("{{", at)) {
                int close = url.indexOf("}}", at + 2);
                if (close < 0) {
                    return url;
                }
                at = close + 2;
                continue;
            }
            if ("/?#".indexOf(url.charAt(at)) >= 0) {
                return url.substring(0, at);
            }
            at++;
        }
        return url;
    }

    /** True when the name says the variable is the address of a callback or of an authentication endpoint. */
    static boolean notACall(String name) {
        for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (NOT_A_CALL.contains(word.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
