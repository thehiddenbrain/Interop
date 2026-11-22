package org.p32h.interop.epa.pave.service.draft;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import tools.jackson.databind.JsonNode;

/**
 * The requests of a collection that ask a token endpoint for a token (D-305): a request whose form
 * body, or whose url query, carries {@code grant_type}. A vendor's collection usually signs in that
 * way, with a script that stores the answer, rather than with Postman's OAuth2 helper, so the
 * sign-in is read from the request itself. The scripts are never read.
 *
 * <p>Read from the collection's text again, as {@link EnvironmentDrafts.CollectionAuth} is: the
 * parser loads raw bodies only, and keeps no form field, no disabled flag and no helper by owner.
 */
final class TokenRequests {

    private TokenRequests() {
    }

    /**
     * One token request, as the file writes it. Holds values that can be credentials, so it prints
     * names only.
     *
     * @param name    the request with the folders it sits in, as a sentence quotes it
     * @param method  the method, upper case; GET when the file states none, as Postman sends it
     * @param url     the url as written, placeholders kept, or null
     * @param fields  the form fields and query parameters that are switched on, by name, first one
     *                kept, in the order of the file: the body's first, then the query's
     * @param headers the names of the headers that are switched on
     * @param basic   the Basic helper the request uses, its own or inherited, or null
     */
    record Found(String name, String method, String url, Map<String, String> fields, List<String> headers,
                 EnvironmentDrafts.CollectionAuth basic) {

        @Override
        public String toString() {
            return "Found[" + method + ", " + fields.keySet() + ", " + headers + "]";
        }

        String grant() {
            return Texts.trimToNull(fields.get("grant_type"));
        }
    }

    /** One item still to look at, with what it inherits. */
    private record Pending(JsonNode item, List<String> path, JsonNode auth) {
    }

    /** The token requests of the collection, in the order of the file. Never throws on what a file holds. */
    static List<Found> find(String text) {
        List<Found> found = new ArrayList<>();
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (!parsed.ok()) {
            return found;
        }
        JsonNode root = parsed.node();
        JsonNode wrapped = JsonSafe.child(root, "collection");
        if (JsonSafe.isObject(wrapped) && JsonSafe.missing(JsonSafe.child(root, "info"))) {
            root = wrapped;
        }
        // Depth first in the order of the file, with a stack of this method's own: a walk that
        // called itself would take one frame per level of the file.
        Deque<Pending> pending = new ArrayDeque<>();
        push(pending, JsonSafe.child(root, "item"), List.of(), inherited(null, JsonSafe.child(root, "auth")));
        while (!pending.isEmpty()) {
            Pending next = pending.pop();
            JsonNode item = next.item();
            String name = Texts.orElse(JsonSafe.presentText(item, "name"), "(unnamed request)");
            JsonNode request = JsonSafe.child(item, "request");
            if (JsonSafe.isObject(request)) {
                Found one = read(request, Names.path(next.path(), name), inherited(next.auth(), JsonSafe.child(request, "auth")));
                if (one != null) {
                    found.add(one);
                }
            }
            JsonNode children = JsonSafe.child(item, "item");
            if (JsonSafe.isArray(children)) {
                List<String> path = new ArrayList<>(next.path());
                path.add(name);
                push(pending, children, path, inherited(next.auth(), JsonSafe.child(item, "auth")));
            }
        }
        return found;
    }

    private static void push(Deque<Pending> pending, JsonNode items, List<String> path, JsonNode auth) {
        List<JsonNode> elements = JsonSafe.elements(items);
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (JsonSafe.isObject(elements.get(i))) {
                pending.push(new Pending(elements.get(i), path, auth));
            }
        }
    }

    /** The helper an owner applies: its own, none when it says noauth, else what it inherits. */
    private static JsonNode inherited(JsonNode from, JsonNode own) {
        if (!JsonSafe.isObject(own)) {
            return from;
        }
        String type = JsonSafe.presentText(own, "type");
        if (type == null || "inherit".equalsIgnoreCase(type)) {
            return from;
        }
        return "noauth".equalsIgnoreCase(type) ? null : own;
    }

    /** The request as a token request, or null when it carries no grant_type. */
    private static Found read(JsonNode request, String name, JsonNode auth) {
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode body = JsonSafe.child(request, "body");
        if (JsonSafe.isObject(body) && !Boolean.TRUE.equals(JsonSafe.bool(body, "disabled"))) {
            String mode = Texts.orElse(JsonSafe.presentText(body, "mode"), JsonSafe.text(body, "raw") != null ? "raw" : "")
                    .toLowerCase(Locale.ROOT);
            if (mode.equals("urlencoded")) {
                for (JsonNode field : JsonSafe.elements(body, "urlencoded")) {
                    String key = JsonSafe.presentText(field, "key");
                    if (key != null && !Boolean.TRUE.equals(JsonSafe.bool(field, "disabled"))) {
                        fields.putIfAbsent(key, Texts.orElse(JsonSafe.text(field, "value"), ""));
                    }
                }
            } else if (mode.equals("raw")) {
                pairs(JsonSafe.text(body, "raw"), fields, true);
            }
        }
        JsonNode urlNode = JsonSafe.child(request, "url");
        String url;
        if (JsonSafe.isObject(urlNode)) {
            url = Texts.trimToNull(JsonSafe.text(urlNode, "raw"));
            JsonNode query = JsonSafe.child(urlNode, "query");
            if (JsonSafe.isArray(query)) {
                for (JsonNode parameter : JsonSafe.elements(query)) {
                    String key = JsonSafe.presentText(parameter, "key");
                    if (key != null && !Boolean.TRUE.equals(JsonSafe.bool(parameter, "disabled"))) {
                        fields.putIfAbsent(key, Texts.orElse(JsonSafe.text(parameter, "value"), ""));
                    }
                }
            } else if (url != null) {
                pairs(query(url), fields, false);
            }
            if (url == null) {
                url = rebuilt(urlNode);
            }
        } else {
            url = Texts.trimToNull(JsonSafe.text(urlNode));
            if (url != null) {
                pairs(query(url), fields, false);
            }
        }
        if (!fields.containsKey("grant_type")) {
            return null;
        }
        String method = Texts.orElse(JsonSafe.presentText(request, "method"), "GET").strip().toUpperCase(Locale.ROOT);
        EnvironmentDrafts.CollectionAuth helper = EnvironmentDrafts.CollectionAuth.of(auth);
        return new Found(name, method, url, fields, headers(JsonSafe.child(request, "header")),
                helper != null && "basic".equals(helper.type()) ? helper : null);
    }

    /** The query of a url as written, without its fragment, or null. */
    private static String query(String url) {
        int mark = url.indexOf('?');
        if (mark < 0) {
            return null;
        }
        int hash = url.indexOf('#', mark);
        return url.substring(mark + 1, hash < 0 ? url.length() : hash);
    }

    /**
     * Adds the name and value pairs of a form text. A raw body counts only when it reads as a form
     * as a whole: every part a name of letters, digits, underscores, dots or hyphens and an equals
     * sign, so a JSON or XML body is never taken apart.
     */
    private static void pairs(String text, Map<String, String> fields, boolean wholeForm) {
        if (Texts.blank(text)) {
            return;
        }
        Map<String, String> read = new LinkedHashMap<>();
        for (String part : cut(text.strip(), '&')) {
            if (part.isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            String key = decoded(equals < 0 ? part : part.substring(0, equals)).strip();
            if (wholeForm && (equals < 0 || !plainName(key))) {
                return;
            }
            if (!key.isEmpty()) {
                read.putIfAbsent(key, equals < 0 ? "" : decoded(part.substring(equals + 1)));
            }
        }
        read.forEach(fields::putIfAbsent);
    }

    /** The text cut at each mark, by plain search: no pattern is compiled for so simple a cut. */
    private static List<String> cut(String text, char mark) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        int at;
        while ((at = text.indexOf(mark, from)) >= 0) {
            parts.add(text.substring(from, at));
            from = at + 1;
        }
        parts.add(text.substring(from));
        return parts;
    }

    private static boolean plainName(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** Percent decoding as a form is read; a part that is not valid encoding is kept as written. */
    private static String decoded(String part) {
        try {
            return URLDecoder.decode(part, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            return part;
        }
    }

    /** A url object with no raw form, rebuilt from its parts as Postman shows it. */
    private static String rebuilt(JsonNode node) {
        StringBuilder url = new StringBuilder();
        String protocol = JsonSafe.presentText(node, "protocol");
        if (protocol != null) {
            url.append(protocol).append("://");
        }
        url.append(joined(JsonSafe.child(node, "host"), "."));
        String port = JsonSafe.presentText(node, "port");
        if (port != null) {
            url.append(':').append(port);
        }
        String path = joined(JsonSafe.child(node, "path"), "/");
        if (!path.isEmpty()) {
            url.append(path.startsWith("/") ? "" : "/").append(path);
        }
        return url.length() == 0 ? null : url.toString();
    }

    private static String joined(JsonNode node, String separator) {
        if (!JsonSafe.isArray(node)) {
            return Texts.orElse(JsonSafe.text(node), "");
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode element : JsonSafe.elements(node)) {
            String part = JsonSafe.isObject(element) ? JsonSafe.text(element, "value") : JsonSafe.text(element);
            parts.add(part == null ? "" : part);
        }
        return String.join(separator, parts);
    }

    /** The names of the headers that are switched on: a list of objects, or the older block of lines. */
    private static List<String> headers(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (JsonSafe.isArray(node)) {
            for (JsonNode header : JsonSafe.elements(node)) {
                String key = JsonSafe.presentText(header, "key");
                if (key != null && !Boolean.TRUE.equals(JsonSafe.bool(header, "disabled"))) {
                    names.add(key);
                }
            }
        } else {
            String block = JsonSafe.text(node);
            if (block != null) {
                for (String line : cut(block, '\n')) {
                    String stripped = line.strip();
                    int colon = stripped.indexOf(':');
                    // Postman's older form switches a header off by starting its line with two slashes.
                    if (colon > 0 && !stripped.startsWith("//")) {
                        names.add(stripped.substring(0, colon).strip());
                    }
                }
            }
        }
        return names;
    }
}
