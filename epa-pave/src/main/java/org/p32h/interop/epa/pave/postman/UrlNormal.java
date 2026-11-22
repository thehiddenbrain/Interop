package org.p32h.interop.epa.pave.postman;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one normal form in which a url is judged and sent.
 *
 * <p>Three steps. Two are from RFC 3986: a percent-escape of an unreserved character (a letter, a
 * digit, hyphen, full stop, underscore or tilde) is decoded, since it means exactly that
 * character, and the dot segments {@code .} and {@code ..} of the path are removed. The third is
 * what common servers do on top of that: a run of slashes in the path is one slash (nginx with
 * {@code merge_slashes}, Tomcat), and a segment that is a dot segment once its {@code ;}
 * parameters are set aside is a dot segment (Tomcat reads {@code /..;/} as {@code /../}). A
 * server applies these before it decides what an address means, so a url read in any other form
 * can be judged as one thing and acted on as another: {@code /Claim/$inquire/../../Claim} reads
 * as an inquiry and is a create. Every other escape is left as it was written, and the query and
 * fragment keep their dot segments, which mean nothing there.
 *
 * <p>The text before the path is taken to be a scheme and an authority when it has {@code ://};
 * otherwise the whole text before a {@code ?} or {@code #} is a path. That lets a template such as
 * {@code {{baseUrl}}/Claim/$inquire/../..} be read the same way, with its first segment as the
 * root that a {@code ..} cannot remove.
 */
public final class UrlNormal {

    /**
     * A url in its normal form.
     *
     * @param text    the url with unreserved escapes decoded and dot segments removed
     * @param climbed true when a {@code ..} reached above the root of the path, so the url as
     *                written addressed something above where it starts. The normal form keeps it
     *                at the root, as RFC 3986 does, and a write in that state is refused
     */
    public record Form(String text, boolean climbed) {

        /** Prints nothing of the url, which can hold an identifier. */
        @Override
        public String toString() {
            return "Form[" + (climbed ? "climbed" : "within its root") + "]";
        }
    }

    /**
     * The parts of an absolute url, for comparing destinations.
     *
     * @param scheme the scheme in lower case
     * @param host   the host in lower case, without a trailing dot
     * @param port   the port, or the default port of the scheme, or -1 when there is neither
     * @param path   the path in its normal form, without a trailing slash and without the
     *               {@code ;} parameters of its segments, which name nothing a server routes on.
     *               Empty for the root
     * @param readable true when the HTTP client reads the url the same way: it has a scheme, a
     *               host the JDK parser accepts and a port of at most 65535. When false, the parts
     *               are this tool's own lenient reading, used only to compare with production
     *               addresses, and the client would refuse the url or read it otherwise
     */
    public record Destination(String scheme, String host, int port, String path, boolean readable) {

        /** A destination read the way the HTTP client reads it. */
        public Destination(String scheme, String host, int port, String path) {
            this(scheme, host, port, path, true);
        }

        /** The destination as one text: scheme, host, port and path. Never a query or user information. */
        public String shown() {
            boolean defaultPort = port < 0 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return (scheme == null ? "" : scheme + "://") + host + (defaultPort ? "" : ":" + port) + path;
        }

        /**
         * True when the two name the same scheme, host and port. A scheme or a port that was not
         * written, as in a bare host name, matches any, so a comparison errs toward a match. Two
         * names of this machine, such as {@code localhost} and {@code 127.0.0.1}, are the same host,
         * and so are two ways of writing one IP address. Another DNS name for a host is not known.
         */
        public boolean sameOrigin(Destination other) {
            return other != null && hostKey(host).equals(hostKey(other.host)) && (port < 0 || other.port < 0 || port == other.port)
                    && (scheme == null || other.scheme == null || scheme.equals(other.scheme));
        }

        /**
         * True when the other destination is this one or lies beneath it: same origin, and a path
         * that starts with this path at a segment boundary. The paths are compared without regard
         * to letter case: many servers route {@code /LIVE} as {@code /live}, and a comparison that
         * decides whether an address is production errs toward a match.
         */
        public boolean covers(Destination other) {
            if (!sameOrigin(other)) {
                return false;
            }
            String mine = path.toLowerCase(Locale.ROOT);
            String theirs = other.path.toLowerCase(Locale.ROOT);
            return theirs.equals(mine) || theirs.startsWith(mine + "/") || mine.isEmpty();
        }

        /** True when either covers the other. */
        public boolean overlaps(Destination other) {
            return covers(other) || (other != null && other.covers(this));
        }

        /**
         * True for a destination that stands for a whole host, every scheme, port and path on it:
         * what a production address that cannot be read protects, see {@link #wholeHost}.
         */
        public boolean everyPortAndPath() {
            return !readable && scheme == null && port < 0 && path.isEmpty();
        }

        @Override
        public String toString() {
            return "Destination[" + shown() + "]";
        }
    }

    private UrlNormal() {
    }

    /** The normal form of a url or url template. Null for null. */
    public static Form of(String url) {
        if (url == null) {
            return null;
        }
        String text = decodeUnreserved(url);
        int scheme = text.indexOf("://");
        int pathStart = 0;
        if (scheme > 0 && text.substring(0, scheme).chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
            pathStart = scheme + 3;
            while (pathStart < text.length() && "/?#".indexOf(text.charAt(pathStart)) < 0) {
                pathStart++;
            }
        }
        int pathEnd = pathStart;
        while (pathEnd < text.length() && text.charAt(pathEnd) != '?' && text.charAt(pathEnd) != '#') {
            pathEnd++;
        }
        String before = text.substring(0, pathStart);
        String path = text.substring(pathStart, pathEnd);
        String after = text.substring(pathEnd);
        boolean[] climbed = {false};
        String normal = removeDotSegments(path, pathStart > 0, climbed);
        return new Form(before + normal + after, climbed[0]);
    }

    /** The normal text of a url, or null for null. */
    public static String text(String url) {
        Form form = of(url);
        return form == null ? null : form.text();
    }

    /**
     * The destination an absolute url names, read exactly as the HTTP client that sends it reads
     * it: by {@link URI}, which is what the client is given. A port written with leading zeros,
     * an explicit default port, a scheme or host in capitals, user information before the host
     * and an IPv6 literal are therefore what the client connects to, and nothing else.
     *
     * <p>When {@link URI} finds no host, or the port is above 65535, the client would refuse the
     * url. The destination is then this tool's own lenient reading, marked as not readable, so that
     * it can still be compared with production addresses (a comparison errs toward a match) and so
     * that a write to it is refused. A value with no scheme, such as {@code api.example.org/fhir},
     * is read that way too, with the scheme left open. Null when not even a host can be found.
     */
    public static Destination destination(String url) {
        if (url == null) {
            return null;
        }
        String text = of(url.strip()).text();
        URI uri = parsed(text);
        if (uri != null && uri.getScheme() != null && uri.getHost() != null && uri.getPort() <= MAX_PORT) {
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = withoutTrailingDots(uri.getHost().toLowerCase(Locale.ROOT));
            int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(scheme);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            // A zone in an IPv6 literal names an interface of this machine, which the client
            // resolves by itself. It is not compared: the address is taken as unreadable.
            boolean readable = !host.isEmpty() && host.indexOf('%') < 0;
            return new Destination(scheme, host, port, trimmed(path), readable);
        }
        return lenient(text);
    }

    /**
     * The url as the HTTP client is given it, or null when it cannot be: it does not parse, or it
     * has no scheme or no host, or its port is above 65535. The same parse as {@link #destination},
     * so the address judged is the address sent to.
     */
    public static URI sendable(String url) {
        if (url == null) {
            return null;
        }
        URI uri = parsed(url.strip());
        Destination destination = destination(url);
        return uri == null || uri.getScheme() == null || uri.getHost() == null || destination == null || !destination.readable()
                ? null : uri;
    }

    private static final int MAX_PORT = 65535;

    /**
     * True when the scheme, host and port of an absolute url are read by the HTTP client as this
     * tool reads them, whatever follows them. Tells a url whose address is wrong from one that is
     * whole up to its path and is not a valid url after it, such as one with a space in its path.
     */
    public static boolean authorityReadable(String url) {
        if (url == null) {
            return false;
        }
        String text = url.strip();
        int scheme = text.indexOf("://");
        if (scheme <= 0) {
            return false;
        }
        int end = scheme + 3;
        while (end < text.length() && "/?#".indexOf(text.charAt(end)) < 0) {
            end++;
        }
        Destination head = destination(text.substring(0, end));
        return head != null && head.readable() && parsed(text.substring(0, end)) != null;
    }

    /** True when the text can stand in the path of a url as it is: no space, no stray percent sign, nothing the client refuses. */
    public static boolean validInPath(String text) {
        return text == null || parsed("http://h/" + text) != null;
    }

    /**
     * The whole host an absolute url names, every scheme, port and path on it, or null when it
     * names no host at all. For a production address that cannot be read: the client may read its
     * port and path in a way this tool does not, so only the host is taken from it, and the host is
     * read as loosely as a browser would. A backslash ends the authority as a slash does, the host
     * ends at its first colon outside an IPv6 literal, and compatibility characters are folded, so
     * {@code http://127.0.0.1:+18865\fhir} and {@code http://127.0.0.1:18865:/fhir} both protect
     * every address of {@code 127.0.0.1}. The result keeps no scheme, port or path, so that it is
     * written and read back the same way: as the host alone. Null too for a host that is not read
     * back as itself, such as one holding a space or a percent sign: no client reaches it.
     */
    public static Destination wholeHost(String url) {
        if (url == null) {
            return null;
        }
        String text = of(url.strip()).text();
        int at = text.indexOf("://");
        String rest = at > 0 ? text.substring(at + 3) : text;
        int end = 0;
        while (end < rest.length() && "/?#\\".indexOf(rest.charAt(end)) < 0) {
            end++;
        }
        String authority = rest.substring(0, end);
        int userInfo = authority.lastIndexOf('@');
        if (userInfo >= 0) {
            authority = authority.substring(userInfo + 1);
        }
        String host;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            host = close < 0 ? authority : authority.substring(0, close + 1);
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
        }
        host = withoutTrailingDots(java.text.Normalizer.normalize(host, java.text.Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT));
        if (host.isEmpty() || host.contains("{")) {
            return null;
        }
        Destination whole = new Destination(null, host, -1, "", false);
        // The store writes this host as its shown text and reads it back with destination(), whose
        // lenient reading refuses a host with white space or a percent sign, and a host that folding
        // turned into one with a slash or another separator reads back as a different host. A host
        // that does not read back as itself is no host any client reaches, and remembered it would
        // keep the tool from starting (hunt-files 5), so the two readings accept the same hosts.
        return whole.equals(destination(whole.shown())) ? whole : null;
    }

    /**
     * An address as the file writes it, for quoting in a sentence: its scheme, authority and path,
     * without user information, query or fragment, which can hold a credential, and with every
     * control character written as an escape so that it shows and stays on one line.
     */
    public static String asWritten(String url) {
        if (url == null) {
            return "";
        }
        String text = url.strip();
        int scheme = text.indexOf("://");
        int authorityStart = scheme > 0 ? scheme + 3 : 0;
        int pathStart = authorityStart;
        while (pathStart < text.length() && "/?#\\".indexOf(text.charAt(pathStart)) < 0) {
            pathStart++;
        }
        int userInfo = text.lastIndexOf('@', pathStart - 1);
        String head = userInfo >= authorityStart ? text.substring(0, authorityStart) + text.substring(userInfo + 1, pathStart)
                : text.substring(0, pathStart);
        int pathEnd = pathStart;
        while (pathEnd < text.length() && text.charAt(pathEnd) != '?' && text.charAt(pathEnd) != '#') {
            pathEnd++;
        }
        StringBuilder out = new StringBuilder();
        for (char c : (head + text.substring(pathStart, pathEnd)).toCharArray()) {
            if (c == '\t') {
                out.append("\\t");
            } else if (Character.isISOControl(c)) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static URI parsed(String text) {
        try {
            return new URI(text);
        } catch (URISyntaxException e) {
            // Not a url the client can be given. The caller reads it leniently or refuses it.
            return null;
        }
    }

    private static int defaultPort(String scheme) {
        return "http".equals(scheme) ? 80 : "https".equals(scheme) ? 443 : -1;
    }

    private static String withoutTrailingDots(String host) {
        String result = host;
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** The path without its query, fragment, segment parameters and trailing slashes. */
    private static String trimmed(String rawPath) {
        int query = indexOfAny(rawPath, "?#");
        String path = withoutParameters(query < 0 ? rawPath : rawPath.substring(0, query));
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * This tool's own reading of a url the HTTP client would not accept, marked as not readable.
     * A port with leading zeros is read as its number, and a port out of range, which the client
     * cannot connect to, is left open so that it matches any port.
     */
    private static Destination lenient(String text) {
        String scheme = null;
        int at = text.indexOf("://");
        String rest = text;
        if (at > 0) {
            scheme = text.substring(0, at).toLowerCase(Locale.ROOT);
            rest = text.substring(at + 3);
        }
        int end = 0;
        while (end < rest.length() && "/?#".indexOf(rest.charAt(end)) < 0) {
            end++;
        }
        String authority = rest.substring(0, end);
        int userInfo = authority.lastIndexOf('@');
        if (userInfo >= 0) {
            authority = authority.substring(userInfo + 1);
        }
        String host = authority;
        int port = -1;
        boolean written = false;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']') < colon) {
            host = authority.substring(0, colon);
            String digits = authority.substring(colon + 1);
            if (!digits.isEmpty()) {
                if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    return null;
                }
                written = true;
                String significant = digits.replaceFirst("^0+(?=.)", "");
                port = significant.length() > 5 || Integer.parseInt(significant) > MAX_PORT ? -1 : Integer.parseInt(significant);
            }
        }
        host = withoutTrailingDots(host.toLowerCase(Locale.ROOT));
        if (host.isEmpty() || host.contains("{") || host.contains("%") || host.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        if (!written && scheme != null) {
            port = defaultPort(scheme);
        }
        return new Destination(scheme, host, port, trimmed(rest.substring(end)), false);
    }

    /** The path with the {@code ;} parameters of each segment left out: {@code /live2;a=b/Claim} is {@code /live2/Claim}. */
    private static String withoutParameters(String path) {
        if (path.indexOf(';') < 0) {
            return path;
        }
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = bare(parts[i]);
        }
        return String.join("/", parts);
    }

    /** A segment without its parameters. */
    private static String bare(String segment) {
        int semicolon = segment.indexOf(';');
        return semicolon < 0 ? segment : segment.substring(0, semicolon);
    }

    /**
     * The form in which a host is compared: every name and address of this machine is one, and an
     * IPv4 address is its dotted decimal form. A host of digits and dots is read as the JDK, and so
     * the HTTP client, reads it: decimal parts with any leading zeros, one to four of them, so
     * {@code 0177.0.0.1} is 177.0.0.1 and not the loopback address. A host the client would not
     * accept, such as {@code 0x7f.1}, is read in the forms a C resolver accepts (octal and
     * hexadecimal too), which errs toward a match. Anything else is the host in lower case.
     */
    static String hostKey(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        long jdk = lower.matches("[0-9.]+") ? jdkIpv4(lower) : -1;
        if (jdk >= 0) {
            return (jdk >>> 24) == 127 || jdk == 0 ? LOOPBACK
                    : (jdk >>> 24) + "." + ((jdk >>> 16) & 0xff) + "." + ((jdk >>> 8) & 0xff) + "." + (jdk & 0xff);
        }
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            return LOOPBACK;
        }
        if (lower.startsWith("[") && lower.endsWith("]")) {
            String inner = lower.substring(1, lower.length() - 1);
            int zone = inner.indexOf('%');
            inner = zone < 0 ? inner : inner.substring(0, zone);
            if (inner.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.')) {
                try {
                    java.net.InetAddress address = java.net.InetAddress.getByName(inner);
                    if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                        return LOOPBACK;
                    }
                    return address.getHostAddress();
                } catch (java.net.UnknownHostException e) {
                    // Not an address this tool can read: compared as written.
                    return lower;
                }
            }
            return lower;
        }
        long ipv4 = ipv4(lower);
        if (ipv4 < 0) {
            return lower;
        }
        if ((ipv4 >>> 24) == 127 || ipv4 == 0) {
            return LOOPBACK;
        }
        return (ipv4 >>> 24) + "." + ((ipv4 >>> 16) & 0xff) + "." + ((ipv4 >>> 8) & 0xff) + "." + (ipv4 & 0xff);
    }

    private static final String LOOPBACK = "this machine";

    /**
     * The IPv4 address a host of digits and dots stands for as the JDK reads a literal address, or
     * -1 when the JDK would not take it as one: at most 15 characters, one to four decimal parts,
     * each but the last at most 255, the last filling the bytes that remain.
     */
    private static long jdkIpv4(String host) {
        if (host.isEmpty() || host.length() > 15) {
            return -1;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            return -1;
        }
        long result = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                return -1;
            }
            long value = Long.parseLong(parts[i]);
            boolean last = i == parts.length - 1;
            if (!last && value > 0xff) {
                return -1;
            }
            if (last) {
                if (value >= (1L << (8 * (4 - i)))) {
                    return -1;
                }
                result |= value;
            } else {
                result |= value << (8 * (3 - i));
            }
        }
        return result;
    }

    /** The IPv4 address a host written as numbers stands for, as inet_aton reads it, or -1 when it is not one. */
    private static long ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            return -1;
        }
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int radix = 10;
            if (part.startsWith("0x")) {
                radix = 16;
                part = part.substring(2);
            } else if (part.length() > 1 && part.startsWith("0")) {
                radix = 8;
                part = part.substring(1);
            }
            if (part.isEmpty() && radix == 16) {
                part = "0";
            }
            if (part.isEmpty() || part.length() > 11) {
                return -1;
            }
            try {
                values[i] = Long.parseLong(part, radix);
            } catch (NumberFormatException e) {
                // Letters: a name, not an address.
                return -1;
            }
        }
        long result = 0;
        for (int i = 0; i < values.length - 1; i++) {
            if (values[i] > 0xff) {
                return -1;
            }
            result |= values[i] << (8 * (3 - i));
        }
        long last = values[values.length - 1];
        if (last >= (1L << (8 * (5 - values.length)))) {
            return -1;
        }
        return result | last;
    }

    /** How many rounds of decoding are looked through for an encoded separator: a value encoded more often than this is refused whole. */
    private static final int DECODING_ROUNDS = 8;

    /**
     * What in the path of a url a server in front of a service could read as a separator once it
     * decodes it, or null when there is nothing of that kind: an encoded slash ({@code %2F}), an
     * encoded backslash ({@code %5C}), an encoded semicolon ({@code %3B}), a segment that is a
     * dot segment once decoded ({@code %2E%2E}), or an escape of a byte above 0x7F, which an
     * overlong or look-alike form of a separator is written with, or a character above 0x7F written
     * as it is, which the client sends as such an escape. Letter case does not matter, and the path is
     * decoded again and again, so {@code %252F} counts. Only the path is read: the query and the
     * fragment keep whatever escapes they carry, as a FHIR search needs.
     *
     * @return the escape as found, in upper case, such as {@code %2F}, or words: "an encoded dot segment",
     *         or "a character above 0x7F (sent as %E2%88%95)"
     */
    public static String encodedSeparator(String url) {
        if (url == null) {
            return null;
        }
        String text = url.strip();
        int scheme = text.indexOf("://");
        int pathStart = 0;
        if (scheme > 0) {
            pathStart = scheme + 3;
            while (pathStart < text.length() && "/?#".indexOf(text.charAt(pathStart)) < 0) {
                pathStart++;
            }
        }
        int pathEnd = pathStart;
        while (pathEnd < text.length() && text.charAt(pathEnd) != '?' && text.charAt(pathEnd) != '#') {
            pathEnd++;
        }
        String path = text.substring(pathStart, pathEnd);
        // The client sends a character above 0x7F as the percent-escapes of its UTF-8 bytes, so
        // the path it sends holds an escape above 0x7F whether the value was typed escaped or raw.
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) > 0x7F) {
                int codePoint = path.codePointAt(i);
                StringBuilder sent = new StringBuilder();
                for (byte b : new String(Character.toChars(codePoint)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    sent.append(String.format(Locale.ROOT, "%%%02X", b & 0xFF));
                }
                return "a character above 0x7F (sent as " + sent + ")";
            }
        }
        for (int round = 0; round <= DECODING_ROUNDS && path.indexOf('%') >= 0; round++) {
            String upper = path.toUpperCase(Locale.ROOT);
            for (String escape : List.of("%2F", "%5C", "%3B")) {
                if (upper.contains(escape)) {
                    return escape;
                }
            }
            String high = escapeAboveAscii(upper);
            if (high != null) {
                return high;
            }
            String decoded = decodedOnce(path);
            // A dot segment that was written as one is left to the normal form. One that only
            // appears once an escape is decoded is hidden from it.
            if (dotSegments(decoded) > dotSegments(path)) {
                return "an encoded dot segment";
            }
            if (decoded.equals(path)) {
                return null;
            }
            if (round == DECODING_ROUNDS) {
                return "an escape encoded more than " + DECODING_ROUNDS + " times";
            }
            path = decoded;
        }
        return null;
    }

    /**
     * The first percent-escape of a byte above 0x7F, or null. A FHIR path is plain ASCII, and such
     * an escape can be read as a separator by a server that decodes an overlong UTF-8 form
     * ({@code %C0%AF} for a slash) or folds a look-alike character ({@code %E2%88%95}, the division
     * slash) into one.
     */
    private static String escapeAboveAscii(String upper) {
        for (int i = 0; i < upper.length(); i++) {
            if (upper.charAt(i) == '%' && hex(upper, i + 1) >= 0x80) {
                return upper.substring(i, i + 3);
            }
        }
        return null;
    }

    /** True when the escape found by {@link #encodedSeparator} is one of a byte above 0x7F. */
    public static boolean aboveAscii(String escape) {
        return escape != null && escape.startsWith("%") && hex(escape, 1) >= 0x80;
    }

    /** How many segments of a path are {@code .} or {@code ..}, a segment ending at a slash, a backslash or a semicolon. */
    private static int dotSegments(String path) {
        int count = 0;
        for (String segment : path.split("[/\\\\;]", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                count++;
            }
        }
        return count;
    }

    /** Every percent-escape of the text decoded once, byte for byte. */
    private static String decodedOnce(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value = c == '%' ? hex(text, i + 1) : -1;
            if (value >= 0) {
                out.append((char) value);
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The url with every percent-escape decoded where it decodes to one byte of printable ASCII. For looking for a word only. */
    public static String decodedForReading(String url) {
        if (url == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            int value = c == '%' ? hex(url, i + 1) : -1;
            if (value >= 0x20 && value < 0x7f) {
                out.append((char) value);
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String decodeUnreserved(String url) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            int value = c == '%' ? hex(url, i + 1) : -1;
            if (value >= 0 && unreserved(value)) {
                out.append((char) value);
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The byte two hex digits at this position stand for, or -1 when they are not two hex digits. */
    private static int hex(String text, int at) {
        if (at + 1 >= text.length()) {
            return -1;
        }
        int high = Character.digit(text.charAt(at), 16);
        int low = Character.digit(text.charAt(at + 1), 16);
        return high < 0 || low < 0 ? -1 : high * 16 + low;
    }

    private static boolean unreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /**
     * RFC 3986 section 5.2.4, by segments, with a segment read without its {@code ;} parameters
     * and empty segments dropped. For a path that follows an authority, a {@code ..} at the root
     * is dropped. For a template, which has no authority, the first segment stands for the root.
     */
    private static String removeDotSegments(String path, boolean afterAuthority, boolean[] climbed) {
        if (path.isEmpty()) {
            return path;
        }
        boolean absolute = path.startsWith("/");
        String[] parts = (absolute ? path.substring(1) : path).split("/", -1);
        List<String> kept = new ArrayList<>();
        int floor = absolute || afterAuthority ? 0 : 1;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean last = i == parts.length - 1;
            String bare = bare(part);
            if (bare.equals(".") || bare.equals("..")) {
                if (bare.equals("..")) {
                    if (kept.size() > floor) {
                        kept.remove(kept.size() - 1);
                    } else {
                        climbed[0] = true;
                    }
                }
                // A dot segment at the end leaves the path ending with a slash, as RFC 3986 does.
                if (last) {
                    kept.add("");
                }
                continue;
            }
            // A run of slashes is one slash. Only a slash at the very end stays, as written.
            if (part.isEmpty() && !last) {
                continue;
            }
            kept.add(part);
        }
        return (absolute ? "/" : "") + String.join("/", kept);
    }

    private static int indexOfAny(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
