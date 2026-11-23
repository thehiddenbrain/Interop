package org.p32h.interop.epa.pave.service.crd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import tools.jackson.databind.JsonNode;

/**
 * How a vendor's coverage-check sample arranges its prefetch: each key in the sample's order, and
 * what it holds there, by kind and resource type only.
 *
 * <p>CDS Hooks fixes no names for the prefetch. A service names its keys in its discovery answer,
 * each with a FHIR read or search, and a client sends a read as the resource itself and a search as
 * a searchset Bundle. PAVE's five named Bundles are what one earlier sandbox accepted (R-37), so a
 * service that looks for the patient under another key finds none. The sample is the vendor's own
 * statement of its keys, and PAVE follows its arrangement while building every resource from the
 * case: nothing of a sample's resources is kept here, only keys, kinds, resource types, Bundle types
 * and search modes (D-309).
 *
 * <p>The body is read once as a tree and the prefetch by fixed paths, two levels down, so nothing
 * here grows with the depth of the file.
 *
 * @param keys      each key of the sample's prefetch, in its order; never empty
 * @param patient   what the sample's first Patient states of the member ID's place, system and type
 * @param coverage  the same of the sample's first Coverage
 * @param practitionerSystem the system of the sample's first Practitioner's only identifier, or null
 * @param contextExtras the members of the sample's context that PAVE's request has no value for, by name
 * @param reason    what the sample's order states of its diagnosis (reasonCode), or null when it carries none
 * @param elements  for each resource type the sample sends, the names of its first resource's own members, in the sample's
 *                  order: the order in context.draftOrders, then each resource of the prefetch (D-310). Names only
 * @param coverageClasses the type codes of the classes of the sample's first Coverage, such as group or plan
 * @param performerType the resource type the first performer of the sample's order refers to, such as PractitionerRole, or null
 *                  when the order names none: a type, never a value (D-321)
 */
public record PrefetchLayout(List<Key> keys, Identity patient, Identity coverage, String practitionerSystem, List<String> contextExtras,
                             Reason reason, Map<String, List<String>> elements, List<String> coverageClasses, String performerType) {

    /** The members of context PAVE's request sends (CrdRequestBuilder). */
    public static final Set<String> CONTEXT_SENT = Set.of("userId", "patientId", "draftOrders", "selections");

    /**
     * Where and how one resource of the sample carries the member ID, its value never kept: the
     * identifier typed MB, else the resource's only identifier.
     *
     * @param hasIdentifier whether the resource has any identifier at all
     * @param system        the system of the member ID's identifier, or null when it states none
     * @param typeSystem    the system of that identifier's first type coding, or null
     * @param typeCode      the code of that coding, or null
     * @param subscriberId  whether the resource carries a subscriberId (a Coverage)
     */
    public record Identity(boolean hasIdentifier, String system, String typeSystem, String typeCode, boolean subscriberId) {

        /** True when the sample states a type with both a system and a code. */
        public boolean statesType() {
            return typeSystem != null && typeCode != null;
        }
    }

    /**
     * The sample's order carries a diagnosis as reasonCode (D-310). Only the system of its first
     * coding is kept: the code is the sample's own value and is never sent.
     *
     * @param system the system of the first coding of the sample's first reasonCode, or null when it states none
     */
    public record Reason(String system) {
    }

    /** The resource types PAVE builds from the case, which are all a prefetch it sends can hold. */
    public static final Set<String> BUILT = Set.of("Patient", "Coverage", "Practitioner", "PractitionerRole", "Organization",
            "ServiceRequest");

    /** What one key of the sample holds. */
    public enum Kind {
        /** A single resource, as a FHIR read gives it. */
        RESOURCE,
        /** A Bundle, as a FHIR search gives it. */
        BUNDLE,
        /** JSON null, which CDS Hooks reads as "could not be fetched". */
        NULL,
        /** A Postman placeholder, whose value is the vendor's own and is never sent. */
        PLACEHOLDER,
        /** Anything else: a text, a number, an array, or an object with no resource type. */
        OTHER
    }

    /**
     * One entry of a sample's Bundle.
     *
     * @param resourceType the entry's resource type, or null when the entry names none
     * @param searchMode   the entry's search mode, or null when it states none
     */
    public record Entry(String resourceType, String searchMode) {
    }

    /**
     * One key of the sample's prefetch.
     *
     * @param name         the key, as the sample writes it
     * @param kind         what it holds
     * @param resourceType the resource type of a single resource, or null
     * @param bundleType   the type of a Bundle as the sample states it, or null when it states none
     * @param total        whether the sample's Bundle states a total
     * @param entries      the entries of a Bundle, in the sample's order; empty otherwise
     */
    public record Key(String name, Kind kind, String resourceType, String bundleType, boolean total, List<Entry> entries) {

        public Key {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        /** The key as a sentence quotes it. */
        public String shownName() {
            return "\"" + Names.shown(name) + "\"";
        }

        /** What the key holds, in words: "a Patient", "a Bundle of type searchset holding Coverage". */
        public String holds() {
            return switch (kind) {
                case RESOURCE -> article(resourceType) + " " + Names.shown(resourceType);
                case BUNDLE -> "a Bundle" + (bundleType == null ? " with no type" : " of type " + Names.shown(bundleType))
                        + (entryTypes().isEmpty() ? " with no entries" : " holding " + words(entryTypes().stream().map(Names::shown).toList()));
                case NULL -> "null";
                case PLACEHOLDER -> "a placeholder";
                case OTHER -> "something other than a resource or a Bundle";
            };
        }

        /** The resource types of the entries, each once, in the order they first appear; an entry with none is passed over. */
        public List<String> entryTypes() {
            List<String> types = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.resourceType() != null && !types.contains(entry.resourceType())) {
                    types.add(entry.resourceType());
                }
            }
            return types;
        }

        /** The search mode the sample's first entry of a type states, or null. */
        public String searchMode(String resourceType) {
            for (Entry entry : entries) {
                if (resourceType.equals(entry.resourceType()) && entry.searchMode() != null) {
                    return entry.searchMode();
                }
            }
            return null;
        }

        /** Names only: a key is the vendor's word, never member data. */
        @Override
        public String toString() {
            return "Key[" + kind + "]";
        }
    }

    /**
     * The members PAVE's builders can write on each resource type, whatever the case (CaseResources,
     * and on the order the diagnosis as reasonCode and the servicing provider as performer, D-321). The form compares a sample with these, as it knows no case; a
     * request compares with what it sent. A test builds a case with every value and holds the two alike.
     */
    public static final Map<String, Set<String>> BUILDABLE = Map.of(
            "Patient", Set.of("id", "identifier", "name", "birthDate", "gender"),
            "Coverage", Set.of("id", "status", "identifier", "subscriberId", "beneficiary", "period", "payor", "class"),
            "Practitioner", Set.of("id", "identifier", "name"),
            "PractitionerRole", Set.of("id", "practitioner", "organization"),
            "Organization", Set.of("id", "identifier", "name"),
            "ServiceRequest", Set.of("id", "status", "intent", "code", "subject", "occurrenceDateTime", "authoredOn", "requester",
                    "performer", "reasonCode", "insurance"));

    /** The class types PAVE's Coverage can carry: the plan, from the plan name. */
    static final Set<String> BUILDABLE_CLASSES = Set.of("plan");

    public PrefetchLayout {
        keys = keys == null ? List.of() : List.copyOf(keys);
        contextExtras = contextExtras == null ? List.of() : List.copyOf(contextExtras);
        elements = elements == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(elements));
        coverageClasses = coverageClasses == null ? List.of() : List.copyOf(coverageClasses);
    }

    /** The keys only, as a layout read before the sample's systems and context were followed. */
    public PrefetchLayout(List<Key> keys) {
        this(keys, null, null, null, List.of(), null, Map.of(), List.of(), null);
    }

    /** Counts only. */
    @Override
    public String toString() {
        return "PrefetchLayout[" + keys.size() + " keys]";
    }

    /**
     * The arrangement of the prefetch a saved body writes.
     *
     * @param savedBody the body as saved in the collection, placeholders intact, or null
     * @return the layout, or null when there is no body, it is not JSON, or its prefetch is absent,
     *         not an object, or empty: such a sample says nothing of the keys its service reads
     */
    public static PrefetchLayout read(String savedBody) {
        if (!Texts.present(savedBody)) {
            return null;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(VendorSamples.placeholdersQuoted(savedBody));
        if (!parsed.ok()) {
            return null;
        }
        JsonNode prefetch = JsonSafe.child(parsed.node(), "prefetch");
        if (!JsonSafe.isObject(prefetch)) {
            return null;
        }
        List<Key> keys = new ArrayList<>();
        // The first resource of each type the prefetch holds, whether alone under a key or in a Bundle: two levels, by fixed paths.
        Map<String, JsonNode> first = new java.util.HashMap<>();
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(prefetch)) {
            keys.add(key(member.getKey(), member.getValue()));
            JsonNode value = member.getValue();
            String type = JsonSafe.presentText(value, "resourceType");
            if ("Bundle".equals(type)) {
                for (JsonNode entry : JsonSafe.elements(value, "entry")) {
                    JsonNode resource = JsonSafe.child(entry, "resource");
                    String entryType = JsonSafe.presentText(resource, "resourceType");
                    if (entryType != null) {
                        first.putIfAbsent(entryType, resource);
                    }
                }
            } else if (type != null) {
                first.putIfAbsent(type, value);
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        List<String> extras = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(JsonSafe.child(parsed.node(), "context"))) {
            if (!CONTEXT_SENT.contains(member.getKey())) {
                extras.add(Names.shown(member.getKey()));
            }
        }
        JsonNode order = firstOrder(parsed.node());
        JsonNode sampleOrder = order == null ? first.get("ServiceRequest") : order;
        Map<String, List<String>> elements = new java.util.LinkedHashMap<>();
        for (JsonNode resource : resources(parsed.node())) {
            elements.putIfAbsent(JsonSafe.presentText(resource, "resourceType"), names(resource));
        }
        return new PrefetchLayout(keys, identity(first.get("Patient")), identity(first.get("Coverage")),
                onlySystem(first.get("Practitioner")), extras, reason(sampleOrder), elements, classTypes(first.get("Coverage")),
                org.p32h.interop.epa.pave.service.run.SampleOrder.read(savedBody).performerType());
    }

    /**
     * Every resource a request sends where a service reads it: the entries of context.draftOrders,
     * then each resource of the prefetch, alone under a key or an entry of a Bundle. Fixed paths, at
     * most three levels, so nothing here grows with the depth of the body.
     */
    static List<JsonNode> resources(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode entry : JsonSafe.elements(JsonSafe.child(JsonSafe.child(root, "context"), "draftOrders"), "entry")) {
            addResource(out, JsonSafe.child(entry, "resource"));
        }
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(JsonSafe.child(root, "prefetch"))) {
            JsonNode value = member.getValue();
            if ("Bundle".equals(JsonSafe.presentText(value, "resourceType"))) {
                for (JsonNode entry : JsonSafe.elements(value, "entry")) {
                    addResource(out, JsonSafe.child(entry, "resource"));
                }
            } else {
                addResource(out, value);
            }
        }
        return out;
    }

    private static void addResource(List<JsonNode> out, JsonNode resource) {
        if (JsonSafe.isObject(resource) && JsonSafe.presentText(resource, "resourceType") != null) {
            out.add(resource);
        }
    }

    /** A resource's own members by name, one level only: resourceType is the type itself, and a placeholder is no name. */
    static List<String> names(JsonNode resource) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(resource)) {
            if (!"resourceType".equals(member.getKey()) && !placeholder(member.getKey())) {
                out.add(Names.shown(member.getKey()));
            }
        }
        return out;
    }

    /** A class type code is a word of a code system; anything else (a placeholder, a sentence) is not named. */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");

    /** The type codes of a Coverage's classes, each once, in its order: the first coding of each class's type. */
    static List<String> classTypes(JsonNode coverage) {
        List<String> out = new ArrayList<>();
        for (JsonNode element : JsonSafe.elements(coverage, "class")) {
            JsonNode coding = JsonSafe.elements(JsonSafe.child(element, "type"), "coding").stream().findFirst().orElse(null);
            String code = coding == null ? null : JsonSafe.presentText(coding, "code");
            if (code != null && CODE.matcher(code.strip()).matches() && !out.contains(code.strip())) {
                out.add(code.strip());
            }
        }
        return out;
    }

    /**
     * What the sample's resources carry that PAVE's built request does not, one line per resource
     * type PAVE sent, by name and never by value (D-310). For a Coverage a class type the sample
     * has and PAVE did not send is named as "class of type group".
     *
     * @param built the request PAVE built, as a tree
     * @param saidElsewhere members another note already speaks for, such as a reasonCode the case gave no diagnosis for
     * @param heldByCase for each type, the members the case holds a value for that the coverage check does not build, such as
     *                   a member's address: saying "no value" of them would be false
     */
    public List<String> elementLines(JsonNode built, Set<String> saidElsewhere, Map<String, Set<String>> heldByCase) {
        Map<String, Set<String>> sent = new java.util.LinkedHashMap<>();
        Map<String, Set<String>> sentClasses = new java.util.HashMap<>();
        for (JsonNode resource : resources(built)) {
            String type = JsonSafe.presentText(resource, "resourceType");
            sent.computeIfAbsent(type, any -> new java.util.HashSet<>()).addAll(names(resource));
            if ("Coverage".equals(type)) {
                sentClasses.computeIfAbsent(type, any -> new java.util.HashSet<>()).addAll(classTypes(resource));
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> type : elements.entrySet()) {
            Set<String> has = sent.get(type.getKey());
            if (has == null) {
                // A type PAVE did not send here has its own line, by key, among those left out.
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String name : type.getValue()) {
                if (!has.contains(name) && !saidElsewhere.contains(name)) {
                    missing.add(name);
                }
            }
            missing = withClasses(type.getKey(), missing, sentClasses.getOrDefault(type.getKey(), Set.of()));
            if (missing.isEmpty()) {
                continue;
            }
            Set<String> held = heldByCase == null ? Set.of() : heldByCase.getOrDefault(type.getKey(), Set.of());
            List<String> holds = missing.stream().filter(held::contains).toList();
            List<String> lacks = missing.stream().filter(name -> !held.contains(name)).toList();
            String start = "The sample's " + Names.shown(type.getKey()) + " also carries " + words(missing) + ". ";
            if (holds.isEmpty()) {
                lines.add(start + "PAVE has no value for " + (missing.size() == 1 ? "it in this case, so it is not sent."
                        : "them in this case, so they are not sent."));
            } else if (lacks.isEmpty()) {
                lines.add(start + "The case holds a value for " + (holds.size() == 1 ? "it" : "them") + ", but PAVE's coverage check does "
                        + "not build " + (holds.size() == 1 ? "it, so it is not sent." : "them, so they are not sent."));
            } else {
                lines.add(start + "PAVE has no value for " + words(lacks) + " in this case, and the case holds " + words(holds)
                        + " but PAVE's coverage check does not build " + (holds.size() == 1 ? "it" : "them") + ". None of them is sent.");
            }
        }
        return lines;
    }

    /**
     * What the sample's resources carry that PAVE's builders never write, whatever the case, for
     * the form, which knows no case. The order's reasonCode and performer are left to their own lines.
     */
    public List<String> buildableLines() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> type : elements.entrySet()) {
            Set<String> can = BUILDABLE.get(type.getKey());
            if (can == null) {
                continue;
            }
            List<String> missing = new ArrayList<>(type.getValue().stream().filter(name -> !can.contains(name)).toList());
            missing = withClasses(type.getKey(), missing, BUILDABLE_CLASSES);
            if (!missing.isEmpty()) {
                lines.add("The sample's " + Names.shown(type.getKey()) + " also carries " + words(missing) + ". PAVE has no field for "
                        + (missing.size() == 1 ? "it, so it is not sent." : "them, so they are not sent."));
            }
        }
        if (reason != null) {
            lines.add("The sample's order carries a diagnosis (reasonCode): the Diagnosis codes of the Service card go there, with the "
                    + "coding system the sample uses.");
        }
        if (performerType != null || elements.getOrDefault("ServiceRequest", List.of()).contains("performer")) {
            lines.add("The sample's order names a performer: the Servicing provider of the Provider card goes there.");
        }
        return lines;
    }

    /** A Coverage's missing names with each class type the sample has and PAVE did not send named as "class of type group". */
    private List<String> withClasses(String type, List<String> missing, Set<String> sentClasses) {
        return withClasses(type, missing, coverageClasses, sentClasses);
    }

    /** As above, for the class types of any Coverage: the compare tool's pasted request has its own. */
    static List<String> withClasses(String type, List<String> missing, List<String> coverageClasses, Set<String> sentClasses) {
        if (!"Coverage".equals(type) || coverageClasses.isEmpty()) {
            return missing;
        }
        List<String> out = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        for (String code : coverageClasses) {
            if (missing.contains("class") || !sentClasses.contains(code)) {
                classes.add("class of type " + code);
            }
        }
        for (String name : missing) {
            if ("class".equals(name)) {
                out.addAll(classes);
                classes.clear();
            } else {
                out.add(name);
            }
        }
        out.addAll(classes);
        return out;
    }

    /** The sample's first order in context.draftOrders, which is where the order a service decides on is sent; one level, by a fixed path. */
    private static JsonNode firstOrder(JsonNode root) {
        for (JsonNode entry : JsonSafe.elements(JsonSafe.child(JsonSafe.child(root, "context"), "draftOrders"), "entry")) {
            JsonNode resource = JsonSafe.child(entry, "resource");
            if ("ServiceRequest".equals(JsonSafe.presentText(resource, "resourceType"))) {
                return resource;
            }
        }
        return null;
    }

    /** What an order of the sample states of its diagnosis, or null when it has no reasonCode. */
    private static Reason reason(JsonNode order) {
        JsonNode reasonCode = JsonSafe.child(order, "reasonCode");
        if (reasonCode.isNull() || reasonCode.isMissingNode() || (reasonCode.isArray() && reasonCode.isEmpty())) {
            return null;
        }
        JsonNode first = JsonSafe.elements(order, "reasonCode").stream().findFirst().orElse(null);
        JsonNode coding = first == null ? null : JsonSafe.elements(first, "coding").stream().findFirst().orElse(null);
        return new Reason(coding == null ? null : stated(JsonSafe.presentText(coding, "system")));
    }

    /** What a resource of the sample states of the member ID, or null when the sample holds no such resource. */
    private static Identity identity(JsonNode resource) {
        if (resource == null) {
            return null;
        }
        List<JsonNode> identifiers = JsonSafe.elements(resource, "identifier");
        JsonNode member = null;
        for (JsonNode identifier : identifiers) {
            for (JsonNode coding : JsonSafe.elements(JsonSafe.child(identifier, "type"), "coding")) {
                if ("MB".equals(JsonSafe.presentText(coding, "code")) && member == null) {
                    member = identifier;
                }
            }
        }
        if (member == null && identifiers.size() == 1) {
            member = identifiers.get(0);
        }
        String system = member == null ? null : stated(JsonSafe.presentText(member, "system"));
        JsonNode coding = member == null ? null : JsonSafe.elements(JsonSafe.child(member, "type"), "coding").stream().findFirst().orElse(null);
        return new Identity(!identifiers.isEmpty(), system, coding == null ? null : stated(JsonSafe.presentText(coding, "system")),
                coding == null ? null : stated(JsonSafe.presentText(coding, "code")), JsonSafe.presentText(resource, "subscriberId") != null);
    }

    /** The system of a resource's only identifier, or null. */
    private static String onlySystem(JsonNode resource) {
        List<JsonNode> identifiers = JsonSafe.elements(resource, "identifier");
        return identifiers.size() == 1 ? stated(JsonSafe.presentText(identifiers.get(0), "system")) : null;
    }

    /** A text the sample states itself: a placeholder is the vendor's variable, whose value PAVE never takes from here. */
    private static String stated(String text) {
        return text == null || placeholder(text) ? null : text.strip();
    }

    /**
     * What the form says of following this sample: what the prefetch leaves out, what its resources
     * carry that PAVE never writes, then the members of context PAVE has no value for.
     */
    public List<String> formLines() {
        List<String> lines = new ArrayList<>(leftOutLines());
        lines.addAll(buildableLines());
        lines.addAll(contextLines());
        return lines;
    }

    /** The lines that say which members of the sample's context PAVE has no value for, by name, so none is sent. */
    public List<String> contextLines() {
        List<String> lines = new ArrayList<>();
        for (String name : contextExtras) {
            lines.add("The sample's context also carries " + name + ". PAVE has no value for it, so it is not sent.");
        }
        return lines;
    }

    /**
     * The layout a call follows: none for a sample whose name says it is a sample of an error, which
     * may send the very arrangement the service refuses in order to show the refusal.
     *
     * @param requestName the name of the collection request the call comes from, or null
     */
    public static PrefetchLayout ofCall(String requestName, String savedBody) {
        if (errorSample(requestName)) {
            return null;
        }
        return read(savedBody);
    }

    /** True when a request's name says it is a sample of an error, as every reader of samples passes it over. */
    public static boolean errorSample(String requestName) {
        return Texts.orElse(requestName, "").toLowerCase(Locale.ROOT).contains("error");
    }

    private static Key key(String name, JsonNode value) {
        if (value == null || value.isNull()) {
            return new Key(name, Kind.NULL, null, null, false, List.of());
        }
        if (value.isString() && placeholder(value.asString())) {
            return new Key(name, Kind.PLACEHOLDER, null, null, false, List.of());
        }
        String type = JsonSafe.presentText(value, "resourceType");
        if (!JsonSafe.isObject(value) || type == null) {
            return new Key(name, Kind.OTHER, null, null, false, List.of());
        }
        if (!"Bundle".equals(type)) {
            return new Key(name, Kind.RESOURCE, type, null, false, List.of());
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode entry : JsonSafe.elements(value, "entry")) {
            entries.add(new Entry(JsonSafe.presentText(JsonSafe.child(entry, "resource"), "resourceType"),
                    JsonSafe.presentText(JsonSafe.child(entry, "search"), "mode")));
        }
        String bundleType = JsonSafe.presentText(value, "type");
        return new Key(name, Kind.BUNDLE, null, bundleType != null && placeholder(bundleType) ? null : bundleType,
                value.has("total"), entries);
    }

    private static boolean placeholder(String text) {
        String value = text.strip();
        return value.startsWith("{{") && value.endsWith("}}");
    }

    /** The sample's keys as the shape's option names them: "patient, coverage". */
    public String keyNames() {
        return String.join(", ", keys.stream().map(key -> Names.shown(key.name())).toList());
    }

    /** The words of the option As the collection's sample, with the keys it would send. */
    public String optionWords() {
        return "As the collection's sample (" + keyNames() + ")";
    }

    /**
     * What a request in this arrangement leaves out whatever the case, one line per key or entry,
     * by key and type and never by value: a type PAVE does not build, a placeholder, a value that is
     * no resource. The same lines stand in the form and in the request's notes.
     */
    public List<String> leftOutLines() {
        List<String> lines = new ArrayList<>();
        for (Key key : keys) {
            String line = leftOutLine(key);
            if (line != null) {
                lines.add(line);
            }
            if (key.kind() == Kind.BUNDLE) {
                for (String type : key.entryTypes()) {
                    if (!BUILT.contains(type)) {
                        lines.add(notBuilt(type, "in the Bundle under " + key.shownName()));
                    }
                }
                if (key.entries().stream().anyMatch(entry -> entry.resourceType() == null)) {
                    lines.add("The sample's Bundle under " + key.shownName() + " has an entry with no resource type. PAVE cannot tell "
                            + "what it would hold, so it is not sent.");
                }
            }
        }
        return lines;
    }

    /** The line for a whole key left out whatever the case, or null when the key can be filled. */
    private static String leftOutLine(Key key) {
        return switch (key.kind()) {
            case RESOURCE -> BUILT.contains(key.resourceType()) ? null : notBuilt(key.resourceType(), "under " + key.shownName());
            case PLACEHOLDER -> "The sample's prefetch writes " + key.shownName() + " as a placeholder. PAVE sends nothing of a sample's "
                    + "values, so it is not sent.";
            case OTHER -> "The sample's prefetch holds something other than a resource or a Bundle under " + key.shownName()
                    + ". PAVE cannot fill it from the case, so it is not sent.";
            case BUNDLE -> !key.entryTypes().isEmpty() && key.entryTypes().stream().noneMatch(BUILT::contains)
                    ? "The sample's Bundle under " + key.shownName() + " holds no type PAVE builds, so the key is not sent." : null;
            case NULL -> null;
        };
    }

    /** "The sample's prefetch also carries Encounter under "encounter". PAVE does not build it, so it is not sent." */
    static String notBuilt(String type, String where) {
        return "The sample's prefetch also carries " + Names.shown(type) + " " + where + ". PAVE does not build it, so it is not sent.";
    }

    static String article(String word) {
        return word != null && !word.isEmpty() && "AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "an" : "a";
    }

    static String words(List<String> items) {
        return items.size() < 2 ? String.join("", items)
                : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
