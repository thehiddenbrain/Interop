package org.p32h.interop.epa.pave.service.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The destinations an environment's values point at: the scheme, host, port and base path of
 * every variable in force whose value reads as a url and forms the url of a request, resolved
 * through the layers the gateway resolves through when it sends: the environment, then the
 * variables of a collection, then application.yml ({@code onyx.variables}). A url-forming name
 * the environment does not define is read from those layers too, because that is where the
 * environment's requests then go.
 *
 * <p>A variable whose name says it is the address of something a request is never sent to as a
 * call, such as the callback the vendor notifies or the endpoint an access token is fetched from,
 * is not a destination, unless a collection request builds its own url on it. Production and the
 * sandbox often share those two addresses, and treating them as production would refuse the
 * sandbox for an address no write goes to.
 *
 * <p>For a production environment these are the addresses a write must be unlocked for. For any
 * other environment they are compared with those, so that production cannot be uploaded again
 * under a sandbox's kind. Neither the operating system, whose values are read only at the moment
 * of sending, nor a value typed for a run is used: a production address that depends on either is
 * one this tool cannot know, and is refused, see {@link #problems}.
 *
 * <p>A secret of the environment never becomes part of an address that is shown or kept. Where a
 * secret sits in the path, the address ends before the segment that holds it, which is broader
 * and so errs toward production. Where a secret sits in the host or the port, the address is used
 * for judging while that environment is stored and is marked as secret, so that no view shows it
 * and the store never writes it down.
 */
final class ProductionDestinations {

    /** Resolves nested placeholders through the layers it is given. It reads no operating system variable and generates nothing. */
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

    /** Words that say a variable holds an address, for a value that cannot be resolved to show it. */
    private static final Set<String> ADDRESS_WORDS = Set.of("url", "uri", "urls", "host", "hostname", "endpoint", "base", "server");

    /** Stands in for the text of a secret, so that where a secret starts in a value is known exactly. */
    private static final char SECRET_MARK = '\u0001';

    /**
     * Where values come from after the environment's own, in the order the gateway asks them.
     *
     * @param urlForming the names of variables that a collection request puts where its url names
     *                   its scheme, host and port. Such a variable is a destination whatever its name says
     * @param after      each list of layers asked after the environment: application.yml alone, and
     *                   each collection's variables followed by application.yml
     */
    record Context(Set<String> urlForming, List<List<VariableLayer>> after) {

        Context {
            urlForming = urlForming == null ? Set.of() : Set.copyOf(urlForming);
            after = after == null || after.isEmpty() ? List.of(List.of()) : List.copyOf(after);
        }

        static Context of(List<StoredCollection> collections, Map<String, String> applicationYml) {
            VariableLayer yml = VariableLayer.applicationYml(applicationYml);
            List<List<VariableLayer>> after = new ArrayList<>();
            after.add(List.of(yml));
            for (StoredCollection collection : collections) {
                after.add(List.of(VariableLayer.collection(collection.name(), collection.content().inForce()), yml));
            }
            return new Context(ProductionDestinations.urlForming(collections), after);
        }
    }

    /**
     * One destination of an environment.
     *
     * @param destination scheme, host, port and base path
     * @param secret      true when a secret of the environment is part of the host or the port, so
     *                    the destination may be used for judging and is never shown or kept
     * @param variable    the name of the variable it was read from
     * @param written     the value of that variable as the file writes it, for quoting, or null
     *                    when the variable is a secret or the environment does not define it. See
     *                    {@link UrlNormal#asWritten}
     * @param own         false when the environment does not define the variable and it was read
     *                    from a collection or application.yml, which every environment shares
     */
    record Found(UrlNormal.Destination destination, boolean secret, String variable, String written, boolean own) {

        Found(UrlNormal.Destination destination, boolean secret) {
            this(destination, secret, null, null, true);
        }

        /** Prints the variable name only: the destination can be one built from a secret. */
        @Override
        public String toString() {
            return "Found[" + variable + (secret ? ", secret" : "") + "]";
        }
    }

    /** Why the address a production environment would send to is not known exactly. */
    enum Unknown {
        /** It has a scheme and a host, and the HTTP client would not read it as this tool does. */
        UNREADABLE,
        /** It has no value in any layer. */
        NO_VALUE,
        /** Part of it is read from the operating system, which happens only at the moment of sending. */
        AT_SENDING
    }

    /**
     * An address of a production environment that is not known exactly.
     *
     * @param variable the variable that holds it
     * @param why      what is not known about it
     * @param own      true when the environment defines the variable, false when it comes from a
     *                 collection or application.yml
     * @param written  the value as the environment writes it, or null when the variable is a secret
     *                 or the environment does not define it
     * @param resolved the address as it resolved through the other layers, when that is not what
     *                 the environment writes and no secret is part of it, or null
     * @param from     the layer the value was found in when the environment does not define it, or null
     * @param secret   true when the variable or a part of its value is a secret, so no value is quoted
     */
    record Problem(String variable, Unknown why, boolean own, String written, String resolved, String from, boolean secret) {

        /** Prints the variable name only. */
        @Override
        public String toString() {
            return "Problem[" + variable + ", " + why + "]";
        }
    }

    /** What reading one environment found. */
    private record Scan(List<Found> found, List<Problem> problems) {
    }

    static List<Found> of(PostmanEnvironment environment, Context context) {
        return scan(environment, context, false).found();
    }

    /**
     * The destinations of a production environment, as they are remembered. The same as
     * {@link #of}, with one difference: an address with a scheme and a host that cannot be read
     * the way the HTTP client reads it is never dropped, and protects its whole host, every port
     * and every path on it. This tool cannot know which port and path the client would make of
     * it, and a destination that cannot be read is never "not production".
     */
    static List<Found> production(PostmanEnvironment environment, Context context) {
        return scan(environment, context, true).found();
    }

    /**
     * Every address of the environment that is not known exactly, for refusing it as production:
     * one the client cannot read, a variable that reads as an address and has no value, and one
     * built from an operating system variable. Empty when every address is known.
     */
    static List<Problem> problems(PostmanEnvironment environment, Context context) {
        return scan(environment, context, true).problems();
    }

    private static Scan scan(PostmanEnvironment environment, Context context, boolean production) {
        Set<Found> found = new LinkedHashSet<>();
        Map<String, Problem> problems = new LinkedHashMap<>();
        if (environment == null) {
            return new Scan(List.of(), List.of());
        }
        Map<String, PostmanVariable> inForce = environment.inForce();
        VariableLayer own = VariableLayer.environment(environment.name(), inForce);
        Set<String> noValue = new LinkedHashSet<>();
        Set<String> valued = new LinkedHashSet<>();
        for (List<VariableLayer> after : context.after()) {
            List<VariableLayer> layers = new ArrayList<>();
            layers.add(own);
            layers.addAll(after);
            for (PostmanVariable variable : inForce.values()) {
                if (!variable.hasValue() || (notACall(variable.key()) && !context.urlForming().contains(variable.key()))) {
                    continue;
                }
                Resolution resolved = WITHIN.resolve(variable.value(), layers);
                read(variable.key(), variable.secret(), variable.secret() ? null : variable.value(), true, null, resolved, context,
                        production, found, problems, noValue, valued);
            }
            // A url-forming name a production environment leaves to the other layers sends its
            // requests wherever those layers say. Another environment is judged by its own
            // addresses: a write of it that goes there is refused when it is sent.
            for (String name : production ? context.urlForming() : Set.<String>of()) {
                PostmanVariable defined = inForce.get(name);
                if (defined != null && defined.hasValue()) {
                    continue;
                }
                Resolution resolved = WITHIN.resolve("{{" + name + "}}", layers);
                read(name, false, null, false, layerOf(name, after), resolved, context, production, found, problems, noValue, valued);
            }
        }
        // No value is said only of a variable that had none under every collection.
        noValue.removeAll(valued);
        List<Problem> all = new ArrayList<>();
        for (Problem problem : problems.values()) {
            if (problem.why() != Unknown.NO_VALUE || noValue.contains(problem.variable())) {
                all.add(problem);
            }
        }
        return new Scan(new ArrayList<>(found), all);
    }

    /** Reads one resolved variable into what was found and what is not known. */
    private static void read(String name, boolean secretVariable, String written, boolean own, String from, Resolution resolved,
                             Context context, boolean production, Set<Found> found, Map<String, Problem> problems,
                             Set<String> noValue, Set<String> valued) {
        boolean holdsSecret = secretVariable || resolved.pieces().stream().anyMatch(piece -> piece.kind() == Resolution.Kind.SECRET);
        String value = resolved.send();
        if (value == null) {
            boolean atSending = resolved.unresolved().stream().anyMatch(each -> each.startsWith(VariableResolver.OS_REFERENCE));
            if (!addressLike(name, resolved, context)) {
                return;
            }
            if (atSending) {
                problems.putIfAbsent(name, new Problem(name, Unknown.AT_SENDING, own, written, null, from, holdsSecret));
            } else if (own) {
                noValue.add(name);
                problems.putIfAbsent(name, new Problem(name, Unknown.NO_VALUE, own, written, null, from, holdsSecret));
            }
            return;
        }
        valued.add(name);
        if (schemeWithoutHost(value.strip())) {
            // http:///host or http:/host: a url by its scheme, with no host the client reads.
            String shown = holdsSecret || value.equals(written) ? null : value;
            problems.putIfAbsent(name, new Problem(name, Unknown.UNREADABLE, own, written, shown, from, holdsSecret));
            return;
        }
        if (!ProductionSigns.looksLikeUrl(value)) {
            return;
        }
        // A secret whose own value is an address is judged by and never shown or kept, like a
        // host built from one.
        UrlNormal.Destination whole = secretVariable ? UrlNormal.destination(value) : null;
        Found destination = secretVariable ? (whole == null ? null : new Found(whole, true)) : withoutSecrets(resolved, value);
        UrlNormal.Destination read = whole != null ? whole : UrlNormal.destination(value);
        boolean unreadable = hasSchemeAndHost(value.strip()) && (read == null || !read.readable());
        if (unreadable) {
            String shown = holdsSecret || value.equals(written) ? null : value;
            problems.putIfAbsent(name, new Problem(name, Unknown.UNREADABLE, own, written, shown, from, holdsSecret));
        }
        if (production && unreadable) {
            UrlNormal.Destination host = UrlNormal.wholeHost(value);
            // A secret anywhere in the value keeps the host out of every view and out of the store.
            destination = host == null ? null : new Found(host, holdsSecret);
        }
        if (destination != null) {
            found.add(new Found(destination.destination(), destination.secret(), name, written, own));
        }
    }

    /**
     * True when a value that could not be resolved is an address: a collection request builds its
     * url on it, what could be resolved of it has a scheme, or its name says it holds one.
     */
    private static boolean addressLike(String name, Resolution resolved, Context context) {
        if (context.urlForming().contains(name)) {
            return true;
        }
        StringBuilder partial = new StringBuilder();
        for (Resolution.Piece piece : resolved.pieces()) {
            partial.append(piece.send() == null ? String.valueOf(SECRET_MARK) : piece.send());
        }
        if (partial.indexOf("://") >= 0) {
            return true;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("url") || lower.endsWith("uri") || lower.endsWith("host") || lower.endsWith("endpoint")) {
            return true;
        }
        for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (ADDRESS_WORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** The label of the first of these layers that holds a value for the name, or null. */
    private static String layerOf(String name, List<VariableLayer> layers) {
        for (VariableLayer layer : layers) {
            VariableLayer.Entry entry = layer.entries().get(name);
            if (entry != null && entry.value() != null && !entry.value().isBlank()) {
                return layer.label();
            }
        }
        return null;
    }

    /**
     * The names of the variables considered by {@link #of} whose value has a scheme and a host and
     * yet no destination the HTTP client would read the same way. A write to such an address is
     * refused, and the environment says so before anything is sent.
     */
    static List<String> unreadable(PostmanEnvironment environment, Context context) {
        return scan(environment, context, false).problems().stream().filter(problem -> problem.why() == Unknown.UNREADABLE)
                .map(Problem::variable).toList();
    }

    /**
     * The names of the variables considered by {@link #of} whose address holds an encoded slash,
     * backslash, semicolon or dot segment in its path. No request is sent to such an address.
     */
    static List<String> encodedSeparators(PostmanEnvironment environment, Context context) {
        Set<String> names = new LinkedHashSet<>();
        if (environment == null) {
            return List.of();
        }
        Map<String, PostmanVariable> inForce = environment.inForce();
        VariableLayer own = VariableLayer.environment(environment.name(), inForce);
        for (List<VariableLayer> after : context.after()) {
            List<VariableLayer> layers = new ArrayList<>();
            layers.add(own);
            layers.addAll(after);
            for (PostmanVariable variable : inForce.values()) {
                if (!variable.hasValue() || (notACall(variable.key()) && !context.urlForming().contains(variable.key()))) {
                    continue;
                }
                String value = WITHIN.resolve(variable.value(), layers).send();
                if (value != null && hasSchemeAndHost(value.strip()) && UrlNormal.encodedSeparator(value) != null) {
                    names.add(variable.key());
                }
            }
        }
        return new ArrayList<>(names);
    }

    /** True for a value that starts with http: or https: and has no authority after {@code //}, such as {@code http:///host/x} or {@code http:/host/x}. */
    private static boolean schemeWithoutHost(String value) {
        return value.regionMatches(true, 0, "http:", 0, 5) || value.regionMatches(true, 0, "https:", 0, 6)
                ? !hasSchemeAndHost(value) : false;
    }

    /** True when the text starts with a scheme followed by {@code ://} and at least one character of authority. */
    private static boolean hasSchemeAndHost(String value) {
        int scheme = value.indexOf("://");
        return scheme > 0 && value.substring(0, scheme).matches("[A-Za-z][A-Za-z0-9+.-]*") && authorityEnd(value) > scheme + 3;
    }

    /** The destinations alone, for comparing with the addresses another environment would use. */
    static List<UrlNormal.Destination> destinations(PostmanEnvironment environment, Context context) {
        return of(environment, context).stream().map(Found::destination).toList();
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
