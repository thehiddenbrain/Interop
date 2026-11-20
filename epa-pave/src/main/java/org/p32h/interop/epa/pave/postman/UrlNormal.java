package org.p32h.interop.epa.pave.postman;

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
     */
    public record Destination(String scheme, String host, int port, String path) {

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
     * The destination an absolute url names, or null when it does not name one: no scheme, no
     * host, or a host this tool cannot read. A value with no scheme, such as
     * {@code api.example.org/fhir}, is read with the scheme left open.
     */
    public static Destination destination(String url) {
        if (url == null) {
            return null;
        }
        String text = of(url.strip()).text();
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
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']') < colon) {
            host = authority.substring(0, colon);
            String digits = authority.substring(colon + 1);
            if (!digits.isEmpty()) {
                if (!digits.chars().allMatch(Character::isDigit) || digits.length() > 5) {
                    return null;
                }
                port = Integer.parseInt(digits);
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.contains("{") || host.contains("%") || host.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        if (port < 0 && "http".equals(scheme)) {
            port = 80;
        } else if (port < 0 && "https".equals(scheme)) {
            port = 443;
        }
        String path = rest.substring(end);
        int query = indexOfAny(path, "?#");
        path = query < 0 ? path : path.substring(0, query);
        path = withoutParameters(path);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return new Destination(scheme, host, port, path);
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
     * IPv4 address written in any of the forms a resolver accepts (decimal, octal, hexadecimal, or
     * fewer than four parts) is its dotted decimal form. Anything else is the host in lower case.
     */
    static String hostKey(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
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
