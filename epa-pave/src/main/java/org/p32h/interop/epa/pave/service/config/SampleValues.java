package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.service.crd.VendorSamples;
import tools.jackson.databind.JsonNode;

/**
 * The values of the Edit form's Values that a vendor's sample requests state (D-308). On the first
 * real run the coverage check was refused for a missing {@code fhirServer} that the vendor's own
 * coverage-check sample had written all along: a vendor's collection states these values in the
 * bodies of its samples, seldom in variables of PAVE's names.
 *
 * <p>Nothing is guessed. A value is read only from the place in a sample where PAVE's own builder
 * writes that value, and only from the sample a call for that operation would be sent from: the
 * first request in the order of the file mapped to it, passing over a sample of an error, as the
 * gateway chooses. Each body is read once as a tree and its parts by fixed paths, so no walk grows
 * with the depth of the file.
 */
public final class SampleValues {

    /**
     * A value that belongs to another: the payer identifier's system is the system of the payer
     * identifier the same sample states, and is not laid beside an identifier that came from
     * elsewhere, which may be another identifier with another system.
     */
    static final Map<String, String> PAIRED = Map.of("payerIdentifierSystem", "payerIdentifier");

    /** Why a value a sample states is not taken when the value it belongs to is in force from elsewhere, or null. */
    public static String pairedLine(Taken taken) {
        return PAIRED.containsKey(taken.name()) ? "The collection's request \"" + taken.request() + "\" states the system of its own "
                + "payer identifier, and the payer identifier in force comes from elsewhere, so it is not taken." : null;
    }

    /** The value a value belongs to, or null. */
    public static String owner(String name) {
        return PAIRED.get(name);
    }

    /** The members PAVE's own hook request has at its top level (CrdRequestBuilder). */
    static final Set<String> HOOK_MEMBERS = Set.of("hook", "hookInstance", "fhirServer", "context", "prefetch");

    private SampleValues() {
    }

    /**
     * One value a sample states.
     *
     * @param name    the value's name, as KnownValues names it
     * @param value   the value as the sample writes it, placeholders intact
     * @param request the sample's name with its folders, as a sentence quotes it
     */
    public record Taken(String name, String value, String request) {

        /** The name and the request only: a value is the tester's configuration. */
        @Override
        public String toString() {
            return "Taken[" + name + ", " + request + "]";
        }
    }

    /**
     * What the samples of one collection state.
     *
     * @param taken       the values, by name, in the order of KnownValues
     * @param lines       per value name, what else the tester should know of it: why a sample's value is not taken
     * @param hookRequest the coverage-check sample read, as a sentence quotes it, or null without one
     * @param hookExtras  the members of that sample's top level that PAVE's request does not send, by name, in its order
     */
    public record Reading(Map<String, Taken> taken, Map<String, List<String>> lines, String hookRequest, List<String> hookExtras) {

        public Reading {
            taken = taken == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(taken));
            lines = lines == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(lines));
            hookExtras = hookExtras == null ? List.of() : List.copyOf(hookExtras);
        }

        /** The lines of one value, empty without any. */
        public List<String> lines(String name) {
            return lines.getOrDefault(name, List.of());
        }
    }

    /** Reads what the collection's samples state; an empty reading without a collection. */
    public static Reading read(PostmanCollection collection) {
        Map<String, Taken> taken = new LinkedHashMap<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        if (collection == null) {
            return new Reading(taken, lines, null, List.of());
        }
        PostmanRequest hook = hookSample(collection);
        List<String> extras = new ArrayList<>();
        if (hook != null) {
            JsonNode root = body(hook);
            if (JsonSafe.isObject(root)) {
                take(taken, lines, "fhirServer", JsonSafe.child(root, "fhirServer"), hook);
                for (Map.Entry<String, JsonNode> member : JsonSafe.properties(root)) {
                    if (!HOOK_MEMBERS.contains(member.getKey())) {
                        extras.add(Names.shown(member.getKey()));
                    }
                }
            }
        }
        PostmanRequest submit = submitSample(collection);
        if (submit != null) {
            submit(taken, lines, submit);
        }
        return new Reading(ordered(taken), lines, hook == null ? null : hook.quotedName(), extras);
    }

    /**
     * The line that says what the coverage-check sample sends and PAVE does not, by name and never
     * by value, or null when it sends nothing more. A refusal of the check is then most likely
     * about one of them, and the tester should look there first.
     *
     * @param named whether the line stands where the sample is already named
     */
    public static String hookExtrasLine(Reading reading, boolean named) {
        List<String> extras = reading.hookExtras();
        if (extras.isEmpty()) {
            return null;
        }
        String who = named ? "The sample" : "The collection's request \"" + reading.hookRequest() + "\"";
        if (extras.size() == 1) {
            return who + " also sends " + extras.get(0) + ". PAVE does not send it: if the service refuses the check, that is the first "
                    + "thing to look at.";
        }
        return who + " also sends " + words(extras) + ". PAVE does not send them: if the service refuses the check, they are the first "
                + "things to look at.";
    }

    /**
     * Why a value a sample states is not taken, once resolved through the layers a call would
     * see, or null when it is taken. A value that holds a secret is never taken, since a value is
     * shown; one that only names itself says nothing the files do not already lack.
     */
    public static String refusal(Taken taken, Resolution resolution) {
        String value = taken.value().strip();
        if (value.equals("{{" + taken.name() + "}}")) {
            return "The collection's request \"" + taken.request() + "\" writes it as {{" + Names.shown(taken.name()) + "}}, which "
                    + "these files do not define.";
        }
        boolean secret = resolution != null && resolution.variables().stream().anyMatch(org.p32h.interop.epa.pave.model.VariableUse::secret);
        if (secret || !LiteralCredentials.inUrl(value).none()) {
            return "The collection's request \"" + taken.request() + "\" states it through a secret, so it is not taken.";
        }
        if (value.length() > StoredSettings.VALUE_MAX) {
            return "The collection's request \"" + taken.request() + "\" states it in more than " + StoredSettings.VALUE_MAX
                    + " characters, so it is not taken.";
        }
        return null;
    }

    /** What a save says of a value it stored from a sample. */
    public static String storedLine(Taken taken) {
        KnownValues.Known known = KnownValues.of(taken.name());
        String label = known == null ? taken.name() : known.label();
        return label + " set from the collection's request \"" + taken.request() + "\".";
    }

    /** Where a value stored from a sample comes from, in the words of Edit. */
    public static String fromWords(String request) {
        return "from the collection's request \"" + request + "\"";
    }

    /**
     * The values to store: each value a sample states for a name that nothing is in force for,
     * laid under what is typed and stored, with where it came from. A value typed, stored or given
     * by the export, the collection's variables or application.yml is never replaced.
     *
     * @param given the names some file or application.yml already gives
     * @param said  receives one line for each value stored
     */
    static EnvironmentSettings lay(EnvironmentSettings merged, List<Taken> taken, Set<String> given, List<String> said) {
        Map<String, String> values = new LinkedHashMap<>(merged.values());
        Map<String, String> origins = new LinkedHashMap<>(merged.valueOrigins());
        java.util.Set<String> laid = new java.util.HashSet<>();
        for (Taken each : taken) {
            if (values.containsKey(each.name()) || given.contains(each.name())) {
                continue;
            }
            String owner = PAIRED.get(each.name());
            if (owner != null && !laid.contains(owner) && (values.containsKey(owner) || given.contains(owner))) {
                continue;
            }
            laid.add(each.name());
            values.put(each.name(), each.value().strip());
            origins.put(each.name(), each.request());
            said.add(storedLine(each));
        }
        return merged.withValues(values, origins);
    }

    // ------------------------------------------------------------------------------------------
    // Reading the samples
    // ------------------------------------------------------------------------------------------

    /**
     * The coverage-check sample: the request a call for order-sign would be sent from, else, when
     * none is mapped to it, the only request whose body is a CDS Hooks request.
     */
    static PostmanRequest hookSample(PostmanCollection collection) {
        PostmanRequest mapped = first(collection, EpaOperation.CRD_ORDER_SIGN);
        if (mapped != null) {
            return mapped;
        }
        PostmanRequest only = null;
        for (PostmanRequest request : collection.requests()) {
            if (errorSample(request)) {
                continue;
            }
            JsonNode root = body(request);
            if (JsonSafe.isObject(root) && JsonSafe.presentText(root, "hook") != null) {
                if (only != null) {
                    return null;
                }
                only = request;
            }
        }
        return only;
    }

    /**
     * The Submit sample: the request a call for $submit would be sent from, else, when none is
     * mapped to it, the only request whose body is a Bundle that carries a Claim.
     */
    static PostmanRequest submitSample(PostmanCollection collection) {
        PostmanRequest mapped = first(collection, EpaOperation.PAS_SUBMIT);
        if (mapped != null) {
            return mapped;
        }
        PostmanRequest only = null;
        for (PostmanRequest request : collection.requests()) {
            if (errorSample(request) || claims(body(request)).isEmpty()) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = request;
        }
        return only;
    }

    /**
     * What a Submit Bundle states where PAVE's builder writes the same values (PasResources): the
     * payer is the Organization Claim.insurer refers to, its name and its one identifier; the member
     * ID system is the system of the member ID on the Patient Claim.patient refers to, chosen as
     * the saved lists choose a member ID (D-306): the identifier typed MB, else the only one.
     */
    private static void submit(Map<String, Taken> taken, Map<String, List<String>> lines, PostmanRequest from) {
        JsonNode root = body(from);
        List<JsonNode> claims = claims(root);
        if (claims.size() != 1) {
            return;
        }
        String where = "The collection's request \"" + from.quotedName() + "\"";
        JsonNode claim = claims.get(0);
        JsonNode payer = referred(root, JsonSafe.presentText(JsonSafe.child(claim, "insurer"), "reference"), "Organization");
        if (payer != null) {
            take(taken, lines, "payerName", JsonSafe.child(payer, "name"), from);
            List<JsonNode> identifiers = withValue(JsonSafe.elements(payer, "identifier"));
            if (identifiers.size() == 1) {
                take(taken, lines, "payerIdentifier", JsonSafe.child(identifiers.get(0), "value"), from);
                take(taken, lines, "payerIdentifierSystem", JsonSafe.child(identifiers.get(0), "system"), from);
            } else if (identifiers.size() > 1) {
                line(lines, "payerIdentifier", where + " gives the payer " + identifiers.size() + " identifiers" + systems(identifiers)
                        + ", so none is taken: choose one and type it.");
            }
        }
        JsonNode patient = referred(root, JsonSafe.presentText(JsonSafe.child(claim, "patient"), "reference"), "Patient");
        if (patient != null) {
            List<JsonNode> identifiers = withValue(JsonSafe.elements(patient, "identifier"));
            List<JsonNode> typedMb = identifiers.stream().filter(SampleValues::typedMb).toList();
            JsonNode member = typedMb.size() == 1 ? typedMb.get(0) : typedMb.isEmpty() && identifiers.size() == 1 ? identifiers.get(0) : null;
            if (member != null) {
                take(taken, lines, "memberIdSystem", JsonSafe.child(member, "system"), from);
            } else if (identifiers.size() > 1) {
                line(lines, "memberIdSystem", where + " gives the patient " + identifiers.size() + " identifiers" + systems(identifiers)
                        + (typedMb.isEmpty() ? " and none is typed MB" : ", " + typedMb.size() + " of them typed MB")
                        + ", so the member ID system is not taken.");
            }
        }
    }

    /** The Claims of a Bundle's entries, in their order; none for a body that is not a Bundle. */
    static List<JsonNode> claims(JsonNode root) {
        List<JsonNode> claims = new ArrayList<>();
        if (!"Bundle".equals(JsonSafe.presentText(root, "resourceType"))) {
            return claims;
        }
        for (JsonNode entry : JsonSafe.elements(root, "entry")) {
            JsonNode resource = JsonSafe.child(entry, "resource");
            if ("Claim".equals(JsonSafe.presentText(resource, "resourceType"))) {
                claims.add(resource);
            }
        }
        return claims;
    }

    /**
     * The resource of the Bundle's entries a reference names, by its fullUrl or by type and id, or
     * null when none is, or when more than one could be: a reference that names two is not read.
     */
    private static JsonNode referred(JsonNode root, String reference, String type) {
        if (reference == null) {
            return null;
        }
        JsonNode found = null;
        for (JsonNode entry : JsonSafe.elements(root, "entry")) {
            JsonNode resource = JsonSafe.child(entry, "resource");
            if (!type.equals(JsonSafe.presentText(resource, "resourceType"))) {
                continue;
            }
            String fullUrl = JsonSafe.presentText(entry, "fullUrl");
            String id = JsonSafe.presentText(resource, "id");
            String typed = id == null ? null : type + "/" + id;
            boolean names = reference.equals(fullUrl) || reference.equals(typed) || (typed != null && fullUrl != null
                    && fullUrl.endsWith("/" + typed) && reference.endsWith("/" + typed));
            if (names) {
                if (found != null) {
                    return null;
                }
                found = resource;
            }
        }
        return found;
    }

    private static List<JsonNode> withValue(List<JsonNode> identifiers) {
        return identifiers.stream().filter(each -> JsonSafe.presentText(each, "value") != null).toList();
    }

    private static boolean typedMb(JsonNode identifier) {
        for (JsonNode coding : JsonSafe.elements(JsonSafe.child(identifier, "type"), "coding")) {
            if ("MB".equals(JsonSafe.presentText(coding, "code"))) {
                return true;
            }
        }
        return false;
    }

    /** The systems of several identifiers, by name, never their values: a value can be a member's. */
    private static String systems(List<JsonNode> identifiers) {
        List<String> systems = new ArrayList<>();
        for (JsonNode identifier : identifiers) {
            String system = JsonSafe.presentText(identifier, "system");
            systems.add(system == null ? "one with no system" : Names.shown(system));
        }
        return " (" + words(systems) + ")";
    }

    /** The first request in the order of the file mapped to the operation, passing over a sample of an error. */
    static PostmanRequest first(PostmanCollection collection, EpaOperation operation) {
        for (PostmanRequest request : collection.requests()) {
            OperationDetector.Detection detection = OperationDetector.detect(request.name(), request.method(), request.url(),
                    request.folderPath(), request.body());
            if (detection.operation() == operation && !errorSample(request)) {
                return request;
            }
        }
        return null;
    }

    private static boolean errorSample(PostmanRequest request) {
        // As the gateway passes it over: a request whose name says "error" is a sample of a failure.
        return Texts.orElse(request.name(), "").toLowerCase(Locale.ROOT).contains("error");
    }

    /** The body as a tree, a bare placeholder read as null, or null when it is not JSON. */
    static JsonNode body(PostmanRequest request) {
        if (!Texts.present(request.body())) {
            return null;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(VendorSamples.placeholdersAsNull(request.body()));
        return parsed.ok() ? parsed.node() : null;
    }

    private static void take(Map<String, Taken> taken, Map<String, List<String>> lines, String name, JsonNode node, PostmanRequest from) {
        String value = JsonSafe.presentText(node);
        if (value != null) {
            taken.put(name, new Taken(name, value, from.quotedName()));
        } else if (!JsonSafe.nullOrMissing(node) && !node.isString()) {
            line(lines, name, "The collection's request \"" + from.quotedName() + "\" states it as something other than a text, so it "
                    + "is not taken.");
        }
    }

    static void line(Map<String, List<String>> lines, String name, String line) {
        lines.computeIfAbsent(name, key -> new ArrayList<>()).add(line);
    }

    private static Map<String, Taken> ordered(Map<String, Taken> taken) {
        Map<String, Taken> out = new LinkedHashMap<>();
        for (KnownValues.Known known : KnownValues.ALL) {
            Taken each = taken.get(known.name());
            if (each != null) {
                out.put(known.name(), each);
            }
        }
        return out;
    }

    private static String words(List<String> items) {
        return items.size() < 2 ? String.join("", items)
                : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
