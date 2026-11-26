package org.p32h.interop.epa.pave.postman;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Nesting;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.databind.JsonNode;

/**
 * Reads Postman environment and collection exports.
 *
 * <p>The vendor ships its endpoints, sample bodies and saved responses as Postman files, and this
 * tool takes those files as they are. A file that is not what its upload slot expects is refused
 * with a message that says what the file is and what to upload in its place, because "invalid
 * file" sends a tester back to the vendor for something they already have.
 *
 * <p>Scripts are read as text and nothing more. No script is run, compiled or evaluated here or
 * anywhere else in this tool.
 */
public final class PostmanParser {

    private static final String EXPORT_ENVIRONMENT = "In Postman, open Environments, open the menu of the environment, choose Export, "
            + "and upload the file it saves.";
    private static final String EXPORT_COLLECTION = "In Postman, open the menu of the collection, choose Export, select Collection v2.1, "
            + "and upload the file it saves.";

    /** Possessive, so that a schema text with thousands of numbers is read in a loop and not by one call per number. */
    private static final Pattern VERSION = Pattern.compile("v\\d++(?:\\.\\d++)*+");

    /** The mark some editors write at the start of a UTF-8 file. Written as a number so this source stays plain ASCII. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private PostmanParser() {
    }

    /** The words for text that is not UTF-8 but reads as text: a copy re-saved by a Windows tool, not a damaged file. */
    private static final String OTHER_ENCODING = "This file is saved in another encoding (for example by PowerShell or Notepad).";
    private static final String SAVE_AS_UTF8 = "Save it as UTF-8, or export it again from Postman.";

    /** The words of R-36 for an export flattened by a script, split so that the action is the way out. */
    private static final String FLATTENED_CAUSE = "a script saved it cut short (PowerShell ConvertTo-Json does at its default depth).";
    private static final String FLATTENED_ACTION = "Export it from Postman, or save it with -Depth 100.";

    /** The words of R-36 for a name the store could not write back as it was read. */
    static final String BROKEN_NAME = "The name holds a broken character; rename it in Postman and export again.";

    /** How many bytes of a character a cut can have left behind at the end: UTF-8 writes at most four. */
    private static final int LONGEST_TAIL = 3;

    /**
     * The uploaded bytes as text. A Postman export is UTF-8. A copy re-saved as UTF-16 by Windows
     * PowerShell or Notepad is read as what it is, told by its byte order mark, or without one by
     * a zero byte beside the first character, which in JSON is always ASCII. Anything else is
     * refused with the reason that fits it, because "not UTF-8" sent testers looking at a file that
     * was whole and only saved differently, or cut inside its last character.
     */
    public static String decode(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BadInputException("The uploaded file is empty.", "Upload the JSON file that Postman exported.");
        }
        Charset charset = StandardCharsets.UTF_8;
        int start = 0;
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            start = 2;
        } else if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            start = 2;
        } else if (content.length >= 2 && content[0] != 0 && content[1] == 0) {
            charset = StandardCharsets.UTF_16LE;
        } else if (content.length >= 2 && content[0] == 0 && content[1] != 0) {
            charset = StandardCharsets.UTF_16BE;
        }
        String text = strictly(content, start, content.length - start, charset);
        if (text != null) {
            return text;
        }
        for (int cut = 1; cut <= LONGEST_TAIL && content.length - start - cut > 0; cut++) {
            if (strictly(content, start, content.length - start - cut, charset) != null && cutCharacter(content, cut, charset)) {
                throw new BadInputException("This file is not complete: it stops part way through a character, as a truncated download "
                        + "or a partial copy does.", "Export it again from Postman and upload the whole file.");
            }
        }
        if (charset == StandardCharsets.UTF_8 && !binary(content)) {
            throw new BadInputException(OTHER_ENCODING, SAVE_AS_UTF8);
        }
        throw new BadInputException("The uploaded file is not text in UTF-8, so it is not a Postman export.",
                "Upload the JSON file that Postman exported, unchanged.");
    }

    /** The bytes as text in that encoding, or null when they are not. */
    private static String strictly(byte[] content, int offset, int length, Charset charset) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, offset, length)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * True when the last {@code cut} bytes are the start of one character and too few to finish it.
     * In UTF-8 that is a lead byte followed by fewer continuation bytes than it announces; in UTF-16
     * half a code unit, or a high surrogate with or without half of its partner.
     */
    private static boolean cutCharacter(byte[] content, int cut, Charset charset) {
        int first = content.length - cut;
        if (charset != StandardCharsets.UTF_8) {
            return cut == 1 || cut == 2 && highSurrogateAt(content, first, charset) || cut == 3 && highSurrogateAt(content, first, charset);
        }
        int lead = content[first] & 0xFF;
        int needs = lead >= 0xC2 && lead <= 0xDF ? 1 : lead >= 0xE0 && lead <= 0xEF ? 2 : lead >= 0xF0 && lead <= 0xF4 ? 3 : -1;
        if (needs < cut) {
            return false;
        }
        for (int i = first + 1; i < content.length; i++) {
            if ((content[i] & 0xC0) != 0x80) {
                return false;
            }
        }
        return true;
    }

    private static boolean highSurrogateAt(byte[] content, int at, Charset charset) {
        int high = charset == StandardCharsets.UTF_16LE ? content[at + 1] & 0xFF : content[at] & 0xFF;
        return high >= 0xD8 && high <= 0xDB;
    }

    /** True when the bytes hold a control character that no text file holds, as an archive or an image does. */
    private static boolean binary(byte[] content) {
        for (byte b : content) {
            int value = b & 0xFF;
            if (value < 0x09 || value > 0x0D && value < 0x20 && value != 0x1A) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the text holds half of a surrogate pair. A name like that cannot be written to the
     * store's own file and read back the same, so the tool failed at its next start.
     */
    static boolean brokenCharacter(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** True when a list that Postman fills with objects holds a text: what ConvertTo-Json writes past its depth. */
    private static boolean flattened(JsonNode list) {
        for (JsonNode element : JsonSafe.elements(list)) {
            if (element != null && element.isString()) {
                return true;
            }
        }
        return false;
    }

    private static BadInputException flattenedCollection() {
        return new BadInputException("The items of this collection are text, not requests: " + FLATTENED_CAUSE, FLATTENED_ACTION);
    }

    /**
     * Refuses a file of another tool by its name. An OpenAPI file has an info block, so it was
     * called a collection under Environments and something else under Collections, which sent the
     * tester in a circle.
     */
    private static void refuseOtherKinds(JsonNode root, String howToExport) {
        if (!JsonSafe.isObject(root)) {
            return;
        }
        if (!JsonSafe.missing(JsonSafe.child(root, "openapi")) || !JsonSafe.missing(JsonSafe.child(root, "swagger"))) {
            throw new BadInputException("This is an OpenAPI file, not a Postman export.", howToExport);
        }
        JsonNode log = JsonSafe.child(root, "log");
        if (JsonSafe.isObject(log) && (JsonSafe.isArray(JsonSafe.child(log, "entries")) || JsonSafe.isObject(JsonSafe.child(log, "creator")))) {
            throw new BadInputException("This is a HAR file, not a Postman export.", howToExport);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Environments
    // ------------------------------------------------------------------------------------------

    public static PostmanEnvironment environment(String text) {
        Document document = document(text, "environment", EXPORT_ENVIRONMENT);
        refuseOtherKinds(document.root(), EXPORT_ENVIRONMENT);
        JsonNode root = unwrap(document.root(), "environment");
        if (looksLikeCollection(root) || looksLikeOldCollection(root)) {
            // Worded to fit both places it shows in: an environment's form, and the Collections page.
            throw new BadInputException("This file is a Postman collection, not an environment export.", "Choose it as the Collection export, or upload it in Configuration > Collections.");
        }
        if (!JsonSafe.isObject(root) || !JsonSafe.isArray(JsonSafe.child(root, "values"))) {
            throw new BadInputException("This JSON file is not a Postman environment export: it has no list of values.", EXPORT_ENVIRONMENT);
        }
        if (flattened(JsonSafe.child(root, "values"))) {
            throw new BadInputException("The values of this environment are text, not variables: " + FLATTENED_CAUSE, FLATTENED_ACTION);
        }
        String name = JsonSafe.presentText(root, "name");
        if (name == null) {
            throw new BadInputException("This environment export has no name, and an environment is identified by its name.",
                    "Give the environment a name in Postman, export it again and upload that file.");
        }
        if (brokenCharacter(name)) {
            throw new BadInputException(BROKEN_NAME, null);
        }
        List<String> notes = new ArrayList<>(document.notes());
        List<PostmanVariable> variables = variables(JsonSafe.child(root, "values"), false, notes);
        String scope = JsonSafe.presentText(root, "_postman_variable_scope");
        if ("globals".equalsIgnoreCase(scope)) {
            notes.add("This file is an export of Postman globals. Its variables are used as an environment named \"" + Names.shown(name) + "\".");
        }
        return new PostmanEnvironment(JsonSafe.presentText(root, "id"), name, scope, variables, notes);
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    public static PostmanCollection collection(String text) {
        Document document = document(text, "collection", EXPORT_COLLECTION);
        refuseOtherKinds(document.root(), EXPORT_COLLECTION);
        JsonNode root = unwrap(document.root(), "collection");
        if (looksLikeOldCollection(root)) {
            throw new BadInputException("This is a Postman collection in the old v1 format, which this tool does not read.", EXPORT_COLLECTION);
        }
        if (looksLikeEnvironment(root)) {
            throw new BadInputException("This file is a Postman environment export, not a collection.", "Choose it as the Environment export of an environment, in Configuration > Environments.");
        }
        JsonNode info = JsonSafe.child(root, "info");
        if (!JsonSafe.isObject(root) || !JsonSafe.isObject(info) || !JsonSafe.isArray(JsonSafe.child(root, "item"))) {
            throw new BadInputException("This JSON file is not a Postman collection export: it has no info block with a list of items.",
                    EXPORT_COLLECTION);
        }
        if (flattened(JsonSafe.child(root, "item")) || flattened(JsonSafe.child(root, "variable"))) {
            throw flattenedCollection();
        }
        String schema = JsonSafe.presentText(info, "schema");
        if (schema == null) {
            throw new BadInputException("This collection does not say which format it is in, so this tool cannot tell whether it is v2.1 "
                    + "or v2.0.",
                    EXPORT_COLLECTION);
        }
        // Format v2.0 differs from v2.1 in a few shapes this reader already takes, each where it is
        // read (a url, a header or a request written as text, a helper written as an object), so
        // a vendor's older export is read rather than refused whole (D-307).
        boolean older = schema.toLowerCase(Locale.ROOT).contains("v2.0");
        if (!schema.toLowerCase(Locale.ROOT).contains("v2.1") && !older) {
            Matcher version = VERSION.matcher(schema);
            throw new BadInputException("This is a Postman collection in " + (version.find() ? "format " + version.group() : "a format this "
                    + "tool does not recognise") + ". This tool reads formats v2.1 and v2.0.", EXPORT_COLLECTION);
        }
        String name = JsonSafe.presentText(info, "name");
        if (name == null) {
            throw new BadInputException("This collection export has no name, and a collection is identified by its name.",
                    "Give the collection a name in Postman, export it again and upload that file.");
        }
        if (brokenCharacter(name)) {
            throw new BadInputException(BROKEN_NAME, null);
        }

        List<String> notes = new ArrayList<>(document.notes());
        if (older) {
            notes.add("This collection is in Postman's older format v2.0, which this tool reads as it reads v2.1.");
        }
        List<PostmanVariable> variables = variables(JsonSafe.child(root, "variable"), true, notes);

        Walk walk = new Walk();
        AuthSetting collectionAuth = auth(root, "the collection");
        walk.helper(collectionAuth);
        Inherited fromCollection = new Inherited(scripts(root, "the collection"), collectionAuth.auth());
        walk(JsonSafe.child(root, "item"), List.of(), fromCollection, walk);
        if (walk.skipped > 0) {
            notes.add(walk.skipped + (walk.skipped == 1 ? " entry is" : " entries are") + " neither a folder nor a request and "
                    + (walk.skipped == 1 ? "is" : "are") + " not listed.");
        }
        return new PostmanCollection(JsonSafe.presentText(info, "_postman_id"), name, schema, variables, withIds(walk.requests), notes,
                walk.helpers);
    }

    /** What a request inherits from the collection and from the folders above it. */
    private record Inherited(List<PostmanScript> scripts, PostmanAuth auth) {
    }

    private static final class Walk {
        private final List<PostmanRequest.Builder> requests = new ArrayList<>();
        /** Every helper the file sets, whether or not a request inherits it: each one sits in the file as uploaded. */
        private final List<PostmanAuth> helpers = new ArrayList<>();
        private int skipped;

        private void helper(AuthSetting setting) {
            if (setting.auth() != null) {
                helpers.add(setting.auth());
            }
        }
    }

    /**
     * Walks the tree to any depth. The vendor's collection puts every request inside a folder, so
     * reading only the top level would find nothing.
     */
    private static void walk(JsonNode items, List<String> path, Inherited inherited, Walk walk) {
        for (JsonNode item : JsonSafe.elements(items)) {
            if (item != null && item.isString()) {
                // A folder flattened deeper down is the same cut as one at the top: nothing under it can be read.
                throw flattenedCollection();
            }
            if (!JsonSafe.isObject(item)) {
                walk.skipped++;
                continue;
            }
            if (JsonSafe.isArray(JsonSafe.child(item, "item"))) {
                String folder = Texts.orElse(JsonSafe.presentText(item, "name"), "(unnamed folder)");
                String from = "the folder \"" + Names.shown(folder) + "\"";
                List<PostmanScript> scripts = new ArrayList<>(inherited.scripts());
                scripts.addAll(scripts(item, from));
                AuthSetting own = auth(item, from);
                walk.helper(own);
                List<String> inner = new ArrayList<>(path);
                inner.add(folder);
                walk(JsonSafe.child(item, "item"), inner, new Inherited(scripts, own.stated() ? own.auth() : inherited.auth()), walk);
            } else if (!JsonSafe.nullOrMissing(JsonSafe.child(item, "request"))) {
                walk.requests.add(request(item, path, inherited, walk));
            } else {
                walk.skipped++;
            }
        }
    }

    private static PostmanRequest.Builder request(JsonNode item, List<String> path, Inherited inherited, Walk walk) {
        List<String> notes = new ArrayList<>();
        JsonNode request = JsonSafe.child(item, "request");
        List<PostmanRequest.PathVariable> pathVariables = new ArrayList<>();
        String method;
        String url;
        List<PostmanHeader> headers = List.of();
        String bodyMode = null;
        String body = null;
        String bodyLanguage = null;
        AuthSetting ownAuth = new AuthSetting(false, null);
        List<String> formCredentials = List.of();
        if (!JsonSafe.isObject(request)) {
            // The schema allows a request written as a bare url, which Postman sends as a GET.
            method = "GET";
            url = JsonSafe.text(request);
        } else {
            method = JsonSafe.presentText(request, "method");
            if (method == null) {
                method = "GET";
                notes.add("The request states no method. Postman sends such a request as GET, and so does this tool.");
            }
            url = url(JsonSafe.child(request, "url"), pathVariables, notes);
            headers = headers(JsonSafe.child(request, "header"));
            ownAuth = auth(request, "this request");
            if (ownAuth.auth() != null) {
                String named = Texts.orElse(JsonSafe.presentText(item, "name"), "(unnamed request)");
                walk.helper(new AuthSetting(true, new PostmanAuth(ownAuth.auth().type(), "the request \"" + Names.path(path, named) + "\"",
                        ownAuth.auth().variables(), ownAuth.auth().literals())));
            }

            JsonNode bodyNode = JsonSafe.child(request, "body");
            if (JsonSafe.isObject(bodyNode)) {
                String raw = JsonSafe.text(bodyNode, "raw");
                bodyMode = JsonSafe.presentText(bodyNode, "mode");
                if (bodyMode == null && raw != null) {
                    bodyMode = "raw";
                }
                bodyMode = bodyMode == null ? null : bodyMode.toLowerCase(Locale.ROOT);
                bodyLanguage = JsonSafe.presentText(JsonSafe.at(bodyNode, "options", "raw"), "language");
                if ("raw".equals(bodyMode)) {
                    if (Boolean.TRUE.equals(JsonSafe.bool(bodyNode, "disabled"))) {
                        notes.add("The body is switched off in the collection, so it is not loaded.");
                    } else if (Texts.present(raw)) {
                        body = raw;
                    }
                } else if (bodyMode != null && !"none".equals(bodyMode)) {
                    notes.add("The body is in Postman's " + Names.shown(bodyMode) + " mode. This tool loads raw bodies only, so this one is not loaded.");
                    formCredentials = formFields(JsonSafe.child(bodyNode, bodyMode));
                }
            }
        }
        if (Texts.blank(url)) {
            url = null;
            notes.add("The request has no url, so it cannot be sent.");
        }

        List<PostmanScript> scripts = new ArrayList<>(inherited.scripts());
        scripts.addAll(scripts(item, "this request"));
        PostmanAuth auth = ownAuth.stated() ? ownAuth.auth() : inherited.auth();

        List<PostmanExample> examples = new ArrayList<>();
        for (JsonNode response : JsonSafe.elements(item, "response")) {
            if (JsonSafe.isObject(response)) {
                String exampleBody = JsonSafe.text(response, "body");
                examples.add(new PostmanExample(Texts.orElse(JsonSafe.presentText(response, "name"), "(unnamed example)"),
                        JsonSafe.presentText(response, "status"), JsonSafe.integer(response, "code"),
                        headers(JsonSafe.child(response, "header")), exampleBody == null || exampleBody.isEmpty() ? null : exampleBody));
            }
        }

        return PostmanRequest.builder()
                .name(Texts.orElse(JsonSafe.presentText(item, "name"), "(unnamed request)"))
                .folderPath(path)
                .method(method.strip().toUpperCase(Locale.ROOT))
                .url(url)
                .pathVariables(pathVariables)
                .headers(headers)
                .bodyMode(bodyMode)
                .body(body)
                .bodyLanguage(bodyLanguage)
                .scripted(scripts.stream().anyMatch(PostmanScript::preRequest))
                .scripts(scripts)
                .auth(auth)
                .examples(examples)
                .description(description(JsonSafe.isObject(request) ? JsonSafe.child(request, "description") : null))
                .notes(notes)
                .formCredentials(formCredentials);
    }

    /**
     * The names of the fields of a urlencoded or formdata body that hold a credential typed into
     * the file: a field named like a credential whose value is written out with no placeholder. A
     * field switched off still sits in the file, so it counts. Only the names are kept.
     */
    private static List<String> formFields(JsonNode fields) {
        Set<String> found = new LinkedHashSet<>();
        for (JsonNode field : JsonSafe.elements(fields)) {
            String key = JsonSafe.presentText(field, "key");
            String value = JsonSafe.text(field, "value");
            if (key != null && value != null && !value.isBlank() && !value.contains("{{") && Secrets.looksSecret(key)) {
                found.add(key);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Gives every request its id. The id is the hash of the folder path, the name and, for requests
     * that share both, their position among those twins. Nothing else goes into it, so editing a
     * url or a body in a new export leaves the id, and any mapping correction, where it was.
     */
    private static List<PostmanRequest> withIds(List<PostmanRequest.Builder> builders) {
        Map<String, Integer> twins = new HashMap<>();
        Set<String> used = new HashSet<>();
        List<PostmanRequest> requests = new ArrayList<>();
        for (PostmanRequest.Builder builder : builders) {
            PostmanRequest draft = builder.build();
            String key = String.join("\u001f", draft.folderPath()) + "\u001e" + draft.name();
            int position = twins.merge(key, 1, Integer::sum) - 1;
            String id = "r-" + Ids.hash(key + "\u001e" + position, 12);
            for (int attempt = 2; !used.add(id); attempt++) {
                id = "r-" + Ids.hash(key + "\u001e" + position, 12) + "-" + attempt;
            }
            requests.add(builder.id(id).build());
        }
        return requests;
    }

    /**
     * The url with its placeholders intact. Postman writes a url either as text or as an object
     * with a {@code raw} form beside its parts. The raw form is taken exactly as written; when it
     * is missing the url is rebuilt from the parts.
     */
    private static String url(JsonNode node, List<PostmanRequest.PathVariable> pathVariables, List<String> notes) {
        if (JsonSafe.nullOrMissing(node)) {
            return null;
        }
        if (!JsonSafe.isObject(node)) {
            return JsonSafe.text(node);
        }
        for (JsonNode variable : JsonSafe.elements(node, "variable")) {
            String key = JsonSafe.presentText(variable, "key");
            if (key != null) {
                pathVariables.add(new PostmanRequest.PathVariable(key, JsonSafe.text(variable, "value")));
            }
        }
        String raw = JsonSafe.text(node, "raw");
        if (Texts.present(raw)) {
            return raw;
        }
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
        List<String> query = new ArrayList<>();
        for (JsonNode parameter : JsonSafe.elements(node, "query")) {
            String key = JsonSafe.text(parameter, "key");
            if (key == null || Boolean.TRUE.equals(JsonSafe.bool(parameter, "disabled"))) {
                continue;
            }
            String value = JsonSafe.text(parameter, "value");
            query.add(value == null ? key : key + "=" + value);
        }
        if (!query.isEmpty()) {
            url.append('?').append(String.join("&", query));
        }
        String hash = JsonSafe.presentText(node, "hash");
        if (hash != null) {
            url.append('#').append(hash);
        }
        if (url.length() == 0) {
            return null;
        }
        notes.add("The url has no raw form in the file, so it was rebuilt from its parts.");
        if (!JsonSafe.nullOrMissing(JsonSafe.child(node, "auth"))) {
            notes.add("The url carries a user name or password of its own in the file. They were not rebuilt into the url.");
        }
        return url.toString();
    }

    /** A url part that Postman writes either as one string or as a list of strings or of objects holding a value. */
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

    private static List<PostmanHeader> headers(JsonNode node) {
        List<PostmanHeader> headers = new ArrayList<>();
        if (JsonSafe.isArray(node)) {
            for (JsonNode element : JsonSafe.elements(node)) {
                if (JsonSafe.isObject(element)) {
                    String name = JsonSafe.presentText(element, "key");
                    if (name != null) {
                        headers.add(new PostmanHeader(name, Texts.orElse(JsonSafe.text(element, "value"), ""),
                                Boolean.TRUE.equals(JsonSafe.bool(element, "disabled"))));
                    }
                } else {
                    headerLine(JsonSafe.text(element), headers);
                }
            }
        } else if (!JsonSafe.nullOrMissing(node)) {
            // An older form writes every header in one text block, one per line.
            String block = JsonSafe.text(node);
            if (block != null) {
                block.lines().forEach(line -> headerLine(line, headers));
            }
        }
        return headers;
    }

    private static void headerLine(String line, List<PostmanHeader> headers) {
        if (line == null) {
            return;
        }
        int colon = line.indexOf(':');
        if (colon > 0 && Texts.present(line.substring(0, colon))) {
            headers.add(new PostmanHeader(line.substring(0, colon).strip(), line.substring(colon + 1).strip(), false));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Scripts and authentication helpers. Read, reported, never acted on.
    // ------------------------------------------------------------------------------------------

    /**
     * The scripts attached to one collection, folder or request. Only the event list is read, to
     * learn that a script exists and to keep its text for display. A script with no code in it is
     * not a script: Postman writes an empty one into most exports, and flagging those would mark
     * every request and teach testers to ignore the flag.
     */
    private static List<PostmanScript> scripts(JsonNode owner, String from) {
        List<PostmanScript> scripts = new ArrayList<>();
        for (JsonNode event : JsonSafe.elements(owner, "event")) {
            String listen = JsonSafe.presentText(event, "listen");
            if (listen == null) {
                continue;
            }
            listen = listen.toLowerCase(Locale.ROOT);
            if (!PostmanScript.PRE_REQUEST.equals(listen) && !PostmanScript.TEST.equals(listen)) {
                continue;
            }
            String source = scriptSource(JsonSafe.child(event, "script"));
            if (hasCode(source)) {
                scripts.add(new PostmanScript(listen, from, source));
            }
        }
        return scripts;
    }

    private static String scriptSource(JsonNode script) {
        if (JsonSafe.nullOrMissing(script)) {
            return "";
        }
        if (!JsonSafe.isObject(script)) {
            return Texts.orElse(JsonSafe.text(script), "");
        }
        JsonNode exec = JsonSafe.child(script, "exec");
        String source;
        if (JsonSafe.isArray(exec)) {
            List<String> lines = new ArrayList<>();
            JsonSafe.elements(exec).forEach(line -> lines.add(Texts.orElse(JsonSafe.text(line), "")));
            source = String.join("\n", lines);
        } else {
            source = Texts.orElse(JsonSafe.text(exec), "");
        }
        String external = JsonSafe.presentText(script, "src");
        if (!hasCode(source) && external != null) {
            return "(Postman loads this script from " + external + ")";
        }
        return source;
    }

    private static boolean hasCode(String source) {
        return source != null && source.lines().map(String::strip).anyMatch(line -> !line.isEmpty() && !line.startsWith("//"));
    }

    /**
     * @param stated true when the owner sets authentication itself, including setting it to none.
     *               False means it says nothing and inherits from above
     */
    private record AuthSetting(boolean stated, PostmanAuth auth) {
    }

    private static AuthSetting auth(JsonNode owner, String from) {
        JsonNode auth = JsonSafe.child(owner, "auth");
        if (!JsonSafe.isObject(auth)) {
            return new AuthSetting(false, null);
        }
        String type = JsonSafe.presentText(auth, "type");
        if (type == null || "inherit".equalsIgnoreCase(type)) {
            return new AuthSetting(false, null);
        }
        if ("noauth".equalsIgnoreCase(type)) {
            return new AuthSetting(true, null);
        }
        Set<String> names = new LinkedHashSet<>();
        collectPlaceholders(auth, names);
        String helper = type.toLowerCase(Locale.ROOT);
        return new AuthSetting(true, new PostmanAuth(helper, from, List.copyOf(names), literalFields(helper, JsonSafe.child(auth, type))));
    }

    /**
     * The fields of a helper that hold a credential typed into the file: a field named like a
     * credential, or for an API key helper its value, whose value is written out with no
     * placeholder. Postman writes the fields as a list of key and value, or in older exports as
     * an object. Only the names are kept.
     */
    private static List<String> literalFields(String helper, JsonNode fields) {
        Set<String> found = new LinkedHashSet<>();
        if (JsonSafe.isArray(fields)) {
            for (JsonNode field : JsonSafe.elements(fields)) {
                literalField(helper, JsonSafe.presentText(field, "key"), JsonSafe.child(field, "value"), found);
            }
        } else if (JsonSafe.isObject(fields)) {
            JsonSafe.properties(fields).forEach(entry -> literalField(helper, entry.getKey(), entry.getValue(), found));
        }
        return List.copyOf(found);
    }

    private static void literalField(String helper, String key, JsonNode value, Set<String> found) {
        String text = JsonSafe.text(value);
        if (key == null || text == null || text.isBlank() || text.contains("{{")) {
            return;
        }
        // An API key helper names the header in its field "key" and holds the credential in "value".
        boolean credential = "apikey".equals(helper) ? "value".equalsIgnoreCase(key) : Secrets.looksSecret(key);
        if (credential) {
            found.add(key);
        }
    }

    /** Collects the placeholder names under a node. The values themselves are not kept: one of them may be a credential. */
    private static void collectPlaceholders(JsonNode node, Set<String> names) {
        if (JsonSafe.isObject(node)) {
            JsonSafe.properties(node).forEach(entry -> collectPlaceholders(entry.getValue(), names));
        } else if (JsonSafe.isArray(node)) {
            JsonSafe.elements(node).forEach(element -> collectPlaceholders(element, names));
        } else {
            names.addAll(Placeholders.names(JsonSafe.text(node)));
        }
    }

    private static String description(JsonNode node) {
        if (JsonSafe.nullOrMissing(node)) {
            return null;
        }
        return JsonSafe.isObject(node) ? JsonSafe.presentText(node, "content") : JsonSafe.presentText(node);
    }

    // ------------------------------------------------------------------------------------------
    // Variables
    // ------------------------------------------------------------------------------------------

    /** @param idNamesTheVariable true for a collection, where an older export names a variable by its id */
    private static List<PostmanVariable> variables(JsonNode array, boolean idNamesTheVariable, List<String> notes) {
        List<PostmanVariable> variables = new ArrayList<>();
        Map<String, Integer> enabledCount = new HashMap<>();
        int unusable = 0;
        for (JsonNode element : JsonSafe.elements(array)) {
            String key = JsonSafe.presentText(element, "key");
            if (key == null && idNamesTheVariable) {
                key = JsonSafe.presentText(element, "id");
            }
            if (key == null) {
                unusable++;
                continue;
            }
            // Postman marks a switched-off variable with enabled false in an environment and with
            // disabled true in a collection. Either one switches it off here.
            boolean enabled = !Boolean.FALSE.equals(JsonSafe.bool(element, "enabled"))
                    && !Boolean.TRUE.equals(JsonSafe.bool(element, "disabled"));
            JsonNode valueNode = JsonSafe.child(element, "value");
            String value = valueNode.isContainer() ? JsonSafe.compact(valueNode) : JsonSafe.text(valueNode);
            variables.add(new PostmanVariable(key, value, enabled, JsonSafe.presentText(element, "type")));
            if (enabled) {
                enabledCount.merge(key, 1, Integer::sum);
            }
        }
        if (unusable > 0) {
            notes.add(unusable + (unusable == 1 ? " variable entry has" : " variable entries have") + " no name and "
                    + (unusable == 1 ? "was" : "were") + " ignored.");
        }
        enabledCount.entrySet().stream().filter(entry -> entry.getValue() > 1).map(Map.Entry::getKey).sorted()
                .forEach(key -> notes.add("The variable \"" + Names.shown(key) + "\" is defined " + enabledCount.get(key)
                        + " times. The last definition is the one in force, as in Postman."));
        return variables;
    }

    // ------------------------------------------------------------------------------------------
    // Telling the kinds of file apart
    // ------------------------------------------------------------------------------------------

    /** The Postman API wraps an export in one property named after what it is. The wrapper is removed. */
    private static JsonNode unwrap(JsonNode root, String property) {
        JsonNode inner = JsonSafe.child(root, property);
        return JsonSafe.isObject(root) && root.size() == 1 && JsonSafe.isObject(inner) ? inner : root;
    }

    private static boolean looksLikeCollection(JsonNode root) {
        JsonNode inner = unwrap(root, "collection");
        return JsonSafe.isObject(inner) && (JsonSafe.isObject(JsonSafe.child(inner, "info")) || JsonSafe.isArray(JsonSafe.child(inner, "item")));
    }

    /** The v1 format has no info block. It lists requests flat, with an order list and a folders list beside them. */
    private static boolean looksLikeOldCollection(JsonNode root) {
        return JsonSafe.isObject(root) && JsonSafe.missing(JsonSafe.child(root, "info"))
                && (JsonSafe.isArray(JsonSafe.child(root, "requests")) || JsonSafe.isArray(JsonSafe.child(root, "order"))
                || JsonSafe.isArray(JsonSafe.child(root, "folders")));
    }

    private static boolean looksLikeEnvironment(JsonNode root) {
        JsonNode inner = unwrap(root, "environment");
        return JsonSafe.isObject(inner) && JsonSafe.isArray(JsonSafe.child(inner, "values")) && !looksLikeCollection(inner);
    }

    // ------------------------------------------------------------------------------------------
    // Reading the text
    // ------------------------------------------------------------------------------------------

    private record Document(JsonNode root, List<String> notes) {
    }

    private static Document document(String text, String what, String howToExport) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (parsed.ok()) {
            return new Document(parsed.node(), parsed.repeatedKeys()
                    ? List.of("The file repeats a key inside one object. Only the last value of a repeated key was read.") : List.of());
        }
        String body = text != null && !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
        if (Texts.blank(body)) {
            throw new BadInputException("The uploaded file is empty.", "Upload the JSON file that Postman exported. " + howToExport);
        }
        // A UTF-16 file without its byte order mark is valid UTF-8 to a reader that does not look
        // for zero bytes, and arrives with one beside every character. "Not valid JSON" was the wrong reason.
        int zero = body.indexOf(0);
        if (zero >= 0 && zero < 64) {
            throw new BadInputException(OTHER_ENCODING, SAVE_AS_UTF8);
        }
        // The parser's own message is never used: it quotes the text it could not read. Its type
        // and its position say whether the text simply stopped, and where it went wrong if not.
        try {
            JsonSafe.mapper().readTree(body);
        } catch (JacksonException e) {
            // The parser reads objects and lists only so many levels deep, and a file past that is
            // not invalid JSON. Saying so tells the tester what to change; "not valid JSON" sent
            // them looking for a syntax error that is not there.
            Nesting.TooDeep deep = e instanceof StreamConstraintsException ? Nesting.tooDeep(body) : null;
            if (deep != null) {
                throw new BadInputException("This file nests its objects and lists deeper than the " + deep.limit() + " levels this tool "
                        + "reads: the level past that limit opens at line " + deep.line() + ", column " + deep.column() + ". It was not read, "
                        + "and nothing was stored.", what.equals("collection")
                        ? "In Postman, move the requests of the deepest folders into folders nearer the top, export the collection again "
                        + "and upload that file." : "Export the " + what + " from Postman again and upload the file it saves.");
            }
            // "At the end" allows for the few characters of a word such as true that was cut short:
            // the parser reports where that word began, not where the text ran out.
            boolean atTheEnd = e.getLocation() != null && e.getLocation().getCharOffset() >= body.stripTrailing().length() - 5;
            if (e instanceof UnexpectedEndOfInputException || (atTheEnd && leftOpen(body))) {
                throw new BadInputException("This file is not complete: the JSON stops part way through, as a truncated download or a "
                        + "partial copy does.", "Export the " + what + " from Postman again and upload the whole file.");
            }
            String where = e.getLocation() == null || e.getLocation().getLineNr() < 0 ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new BadInputException("This file is not a Postman " + what + " export: it is not valid JSON" + where + ".", howToExport);
        }
        throw new BadInputException("This file is not a Postman " + what + " export: it is not valid JSON.", howToExport);
    }

    /**
     * True when the text ends inside a string, or with an object or an array still open. The parser
     * reports only some of the ways a text can stop early as "the text ended": a cut after a comma
     * or inside the word true comes back as an ordinary syntax error. Together with an error at the
     * very end of the text, this is what tells a truncated file from a malformed one.
     */
    private static boolean leftOpen(String body) {
        StringBuilder closers = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                closers.append(c == '{' ? '}' : ']');
            } else if (c == '}' || c == ']') {
                // A bracket that closes the wrong thing is a mistake in the text, not a text that stopped.
                if (closers.length() == 0 || closers.charAt(closers.length() - 1) != c) {
                    return false;
                }
                closers.setLength(closers.length() - 1);
            }
        }
        return inString || closers.length() > 0;
    }
}
