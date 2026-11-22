package org.p32h.interop.epa.pave.service.lists;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Finds;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundCode;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundMember;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundProvider;
import tools.jackson.databind.JsonNode;

/**
 * The members, providers and procedure codes the requests of a collection state, read from what
 * the collection sends: the body and the url of each request as written, its placeholders filled
 * from the layers given. Saved example responses are not read, and scripts are neither read nor run.
 *
 * <p>A vendor's sample requests carry exactly what a tester cannot guess: the ids of the vendor's
 * own patients and practitioners, the member ID of a test member, an NPI the sandbox knows, the
 * codes it answers for. Nothing is guessed here either: a value is taken only where a request
 * states it, and what is seen but cannot be used is said in one sentence, never dropped in silence.
 *
 * <p>A body is walked with a stack of its own, never by recursion, so a body as deep as the parser
 * reads ends in a result and not in a StackOverflowError. Nothing here logs.
 */
public final class CollectionFinds {

    static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
    /** The environment variable that names the system of the member ID, as the built Coverage reads it. */
    static final String MEMBER_ID_SYSTEM = "memberIdSystem";
    private static final int MAX_MEMBER_ID = 30;

    private static final List<String> MEMBER_FIELDS = List.of("memberId", "vendorPatientId", "firstName", "lastName", "dateOfBirth",
            "gender", "subscriberId", "coverageStatus", "coverageStart", "coverageEnd", "plan");
    private static final List<String> PROVIDER_FIELDS = List.of("npi", "vendorPractitionerId", "firstName", "lastName");
    private static final List<String> CODE_FIELDS = List.of("code", "system", "description");
    private static final Map<String, String> WORDS = Map.ofEntries(Map.entry("memberId", "member ID"),
            Map.entry("vendorPatientId", "vendor patient id"), Map.entry("firstName", "first name"), Map.entry("lastName", "last name"),
            Map.entry("dateOfBirth", "date of birth"), Map.entry("gender", "gender"), Map.entry("subscriberId", "subscriber ID"),
            Map.entry("coverageStatus", "coverage status"), Map.entry("coverageStart", "coverage start"),
            Map.entry("coverageEnd", "coverage end"), Map.entry("plan", "plan"), Map.entry("npi", "NPI"),
            Map.entry("vendorPractitionerId", "vendor practitioner id"), Map.entry("description", "description"));

    /** One entry while the collection is read: its values by field, and the request it was first found in. */
    private static final class Entry {
        final Map<String, String> values = new LinkedHashMap<>();
        final String from;

        Entry(String from) {
            this.from = from;
        }

        void put(String field, String value) {
            if (Texts.present(value)) {
                values.put(field, value.strip());
            }
        }

        String get(String field) {
            return values.get(field);
        }

        @Override
        public String toString() {
            return "Entry[" + values.size() + "]";
        }
    }

    /** The state of one reading. */
    private static final class Reading {
        final List<Entry> members = new ArrayList<>();
        final List<Entry> providers = new ArrayList<>();
        final List<Entry> codes = new ArrayList<>();
        final List<String> notUsed = new ArrayList<>();
        final int[] overflow = new int[3];
    }

    private CollectionFinds() {
    }

    /**
     * Reads every request of the collection.
     *
     * @param layers where placeholder values come from, in the order they are asked: the
     *               environment, then the collection's own variables
     */
    public static Finds read(PostmanCollection collection, List<VariableLayer> layers, VariableResolver resolver) {
        Reading reading = new Reading();
        String memberIdSystem = memberIdSystem(layers, resolver);
        for (PostmanRequest request : collection.requests()) {
            readRequest(request, layers, resolver, memberIdSystem, reading);
        }
        String[] lists = {"members", "providers", "procedure codes"};
        for (int i = 0; i < lists.length; i++) {
            if (reading.overflow[i] > 0) {
                reading.notUsed.add("The collection states more than " + TestListRules.MAX_ENTRIES + " " + lists[i] + ", the most a list "
                        + "keeps: " + reading.overflow[i] + " more " + (reading.overflow[i] == 1 ? "is" : "are") + " not listed.");
            }
        }
        List<FoundMember> members = new ArrayList<>();
        for (Entry entry : reading.members) {
            members.add(new FoundMember(entry.get("memberId"), entry.get("vendorPatientId"), entry.get("firstName"), entry.get("lastName"),
                    entry.get("dateOfBirth"), entry.get("gender"), entry.get("subscriberId"), entry.get("coverageStatus"),
                    entry.get("coverageStart"), entry.get("coverageEnd"), entry.get("plan"), entry.from));
        }
        List<FoundProvider> providers = new ArrayList<>();
        for (Entry entry : reading.providers) {
            providers.add(new FoundProvider(entry.get("npi"), entry.get("vendorPractitionerId"), entry.get("firstName"),
                    entry.get("lastName"), entry.from));
        }
        List<FoundCode> codes = new ArrayList<>();
        for (Entry entry : reading.codes) {
            codes.add(new FoundCode(entry.get("code"), entry.get("system"), CodeSystems.shortName(entry.get("system")),
                    entry.get("description"), entry.from));
        }
        return new Finds(members, providers, codes, reading.notUsed);
    }

    /** The environment's member ID system, when a layer states one that is not a secret. */
    private static String memberIdSystem(List<VariableLayer> layers, VariableResolver resolver) {
        Resolution resolution = resolver.resolve("{{" + MEMBER_ID_SYSTEM + "}}", layers);
        if (!resolution.complete() || secret(resolution)) {
            return null;
        }
        return Texts.trimToNull(resolution.send());
    }

    private static boolean secret(Resolution resolution) {
        return resolution.pieces().stream().anyMatch(piece -> piece.kind() == Resolution.Kind.SECRET);
    }

    // ------------------------------------------------------------------------------------------
    // One request
    // ------------------------------------------------------------------------------------------

    private static void readRequest(PostmanRequest request, List<VariableLayer> layers, VariableResolver resolver, String memberIdSystem,
            Reading reading) {
        String where = "\"" + request.quotedName() + "\"";
        String from = Names.shown(request.name());
        List<Entry> members = new ArrayList<>();
        List<Entry> providers = new ArrayList<>();
        List<Entry> codes = new ArrayList<>();
        readUrl(request.url(), layers, resolver, from, members, providers);
        String body = request.body();
        if (Texts.present(body)) {
            Resolution resolution = resolver.resolve(body, layers);
            if (!resolution.complete()) {
                reading.notUsed.add(where + ": " + undefined(resolution.unresolved()) + ", so nothing was read from its body.");
            } else if (secret(resolution)) {
                reading.notUsed.add(where + ": its body uses a secret, so nothing was read from it.");
            } else {
                JsonSafe.Parsed parsed = JsonSafe.parse(resolution.send());
                if (!parsed.ok()) {
                    reading.notUsed.add(where + ": its body is not JSON" + (parsed.tooDeep() ? " this tool reads (it nests too deep)" : "")
                            + ", so nothing was read from it.");
                } else {
                    readBody(parsed.node(), where, from, memberIdSystem, members, providers, codes, reading.notUsed);
                }
            }
        }
        for (Entry entry : members) {
            merge(reading.members, entry, MEMBER_FIELDS, CollectionFinds::sameMember, where, "patient", reading, 0);
        }
        for (Entry entry : providers) {
            merge(reading.providers, entry, PROVIDER_FIELDS, CollectionFinds::sameProvider, where, "practitioner", reading, 1);
        }
        for (Entry entry : codes) {
            merge(reading.codes, entry, CODE_FIELDS, CollectionFinds::sameCode, where, "code", reading, 2);
        }
    }

    private static String undefined(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(i == names.size() - 1 ? " and " : ", ");
            }
            out.append("{{").append(Names.shown(names.get(i))).append("}}");
        }
        return out + (names.size() == 1 ? " has" : " have") + " no value in the environment or the collection";
    }

    /** A url that reads a patient or a practitioner by id: {@code .../Patient/<id>}, {@code .../Practitioner/<id>}. */
    private static void readUrl(String url, List<VariableLayer> layers, VariableResolver resolver, String from, List<Entry> members,
            List<Entry> providers) {
        if (url == null) {
            return;
        }
        int end = url.length();
        for (char stop : new char[] {'?', '#'}) {
            int at = url.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= end; i++) {
            if (i == end || url.charAt(i) == '/') {
                segments.add(url.substring(start, i));
                start = i + 1;
            }
        }
        for (int i = 0; i + 1 < segments.size(); i++) {
            boolean patient = "Patient".equals(segments.get(i));
            if (!patient && !"Practitioner".equals(segments.get(i))) {
                continue;
            }
            Resolution resolution = resolver.resolve(segments.get(i + 1), layers);
            String id = resolution.complete() && !secret(resolution) ? resolution.send() : null;
            // An operation such as $match or _search, or a placeholder with no value, names no one.
            if (id == null || !TestListRules.fitsResourceId(id)) {
                continue;
            }
            Entry entry = new Entry(from);
            entry.put(patient ? "vendorPatientId" : "vendorPractitionerId", id);
            (patient ? members : providers).add(entry);
        }
    }

    // ------------------------------------------------------------------------------------------
    // One body
    // ------------------------------------------------------------------------------------------

    private static void readBody(JsonNode root, String where, String from, String memberIdSystem, List<Entry> members,
            List<Entry> providers, List<Entry> codes, List<String> notUsed) {
        hookContext(root, where, from, members, providers, notUsed);
        // Every resource in the body, in document order, wherever it sits: a prefetch value, a
        // Bundle entry, a contained resource, a Parameters part. fullUrls keeps the fullUrl of a
        // Bundle entry's resource, so a Coverage that names its patient by fullUrl finds it.
        List<JsonNode> resources = new ArrayList<>();
        Map<JsonNode, String> fullUrls = new IdentityHashMap<>();
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            List<JsonNode> children = new ArrayList<>();
            if (JsonSafe.isObject(node)) {
                if (JsonSafe.presentText(node, "resourceType") != null) {
                    resources.add(node);
                }
                JsonNode resource = JsonSafe.child(node, "resource");
                String fullUrl = JsonSafe.presentText(node, "fullUrl");
                if (fullUrl != null && JsonSafe.isObject(resource)) {
                    fullUrls.put(resource, fullUrl);
                }
                JsonSafe.properties(node).forEach(property -> children.add(property.getValue()));
            } else if (JsonSafe.isArray(node)) {
                children.addAll(JsonSafe.elements(node));
            }
            // Pushed last first, so they come off the stack in the order the body writes them.
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        Map<String, Entry> patients = new LinkedHashMap<>();
        for (JsonNode resource : resources) {
            String type = JsonSafe.presentText(resource, "resourceType");
            switch (type) {
                case "Patient" -> patient(resource, fullUrls.get(resource), where, from, memberIdSystem, members, patients, notUsed);
                case "Practitioner" -> practitioner(resource, where, from, providers, notUsed);
                case "ServiceRequest", "Procedure" -> concept(JsonSafe.child(resource, "code"), where, from, codes, notUsed);
                case "DeviceRequest" -> concept(JsonSafe.child(resource, "codeCodeableConcept"), where, from, codes, notUsed);
                case "Claim" -> claim(resource, where, from, codes, notUsed);
                default -> {
                    // Another resource states nothing a saved list keeps; a Coverage is read once every patient is known.
                }
            }
        }
        for (JsonNode resource : resources) {
            if ("Coverage".equals(JsonSafe.presentText(resource, "resourceType"))) {
                coverage(resource, where, patients, notUsed);
            }
        }
    }

    /** A CDS Hooks request names its patient and its user by id in its context. */
    private static void hookContext(JsonNode root, String where, String from, List<Entry> members, List<Entry> providers,
            List<String> notUsed) {
        JsonNode context = JsonSafe.child(root, "context");
        if (JsonSafe.presentText(root, "hook") == null || !JsonSafe.isObject(context)) {
            return;
        }
        String patientId = JsonSafe.presentText(context, "patientId");
        if (patientId != null) {
            String id = patientId.startsWith("Patient/") ? patientId.substring("Patient/".length()) : patientId;
            if (TestListRules.fitsResourceId(id)) {
                Entry entry = new Entry(from);
                entry.put("vendorPatientId", id);
                members.add(entry);
            } else {
                notUsed.add(where + ": its context.patientId is not an id a FHIR server gives (letters, digits, - and . only), so it "
                        + "was left out.");
            }
        }
        String userId = JsonSafe.presentText(context, "userId");
        if (userId != null) {
            String id = userId.startsWith("Practitioner/") ? userId.substring("Practitioner/".length()) : null;
            if (id != null && TestListRules.fitsResourceId(id)) {
                Entry entry = new Entry(from);
                entry.put("vendorPractitionerId", id);
                providers.add(entry);
            } else if (id == null) {
                notUsed.add(where + ": its context.userId names no Practitioner (it does not read Practitioner/<id>), so it was left out.");
            } else {
                notUsed.add(where + ": its context.userId is not an id a FHIR server gives (letters, digits, - and . only), so it was "
                        + "left out.");
            }
        }
    }

    private static void patient(JsonNode resource, String fullUrl, String where, String from, String memberIdSystem, List<Entry> members,
            Map<String, Entry> patients, List<String> notUsed) {
        Entry entry = new Entry(from);
        String id = JsonSafe.presentText(resource, "id");
        if (id != null && TestListRules.fitsResourceId(id)) {
            entry.put("vendorPatientId", id);
        } else if (id != null) {
            notUsed.add(where + ": the id of a Patient is not an id a FHIR server gives (letters, digits, - and . only), so it was left out.");
        }
        name(resource, entry);
        String born = JsonSafe.presentText(resource, "birthDate");
        if (born != null) {
            if (wholeDate(born)) {
                entry.put("dateOfBirth", born);
            } else {
                notUsed.add(where + ": the birth date of a Patient is not a whole date (yyyy-MM-dd), so it was left out.");
            }
        }
        String gender = JsonSafe.presentText(resource, "gender");
        if (gender != null) {
            String lower = gender.strip().toLowerCase(Locale.ROOT);
            if (TestListRules.GENDERS.contains(lower)) {
                entry.put("gender", lower);
            } else {
                notUsed.add(where + ": the gender of a Patient is not female, male, other or unknown, so it was left out.");
            }
        }
        String memberId = memberId(resource, memberIdSystem, where, notUsed);
        if (memberId != null && memberId.length() > MAX_MEMBER_ID) {
            notUsed.add(where + ": the member ID of a Patient is longer than 30 characters, so it was left out.");
        } else {
            entry.put("memberId", memberId);
        }
        if (entry.get("memberId") == null && entry.get("vendorPatientId") == null) {
            notUsed.add(where + ": a Patient states neither a member ID nor an id, so it cannot be saved.");
            return;
        }
        members.add(entry);
        if (entry.get("vendorPatientId") != null) {
            patients.put("Patient/" + entry.get("vendorPatientId"), entry);
        }
        if (fullUrl != null) {
            patients.put(fullUrl, entry);
        }
    }

    /**
     * The member ID: the identifier of the environment's member ID system when it states one; else
     * the one identifier typed MB; else the only identifier. Two that cannot be told apart give none.
     */
    private static String memberId(JsonNode resource, String memberIdSystem, String where, List<String> notUsed) {
        List<JsonNode> identifiers = new ArrayList<>();
        for (JsonNode identifier : JsonSafe.elements(resource, "identifier")) {
            if (JsonSafe.presentText(identifier, "value") != null) {
                identifiers.add(identifier);
            }
        }
        if (identifiers.isEmpty()) {
            return null;
        }
        if (memberIdSystem != null) {
            List<JsonNode> bySystem = identifiers.stream().filter(each -> memberIdSystem.equals(JsonSafe.presentText(each, "system"))).toList();
            if (bySystem.size() == 1) {
                return JsonSafe.presentText(bySystem.get(0), "value");
            }
            if (bySystem.size() > 1) {
                notUsed.add(where + ": a Patient has " + bySystem.size() + " identifiers of the member ID system, so its member ID was "
                        + "not taken.");
                return null;
            }
        }
        List<JsonNode> typedMb = new ArrayList<>();
        for (JsonNode identifier : identifiers) {
            for (JsonNode coding : JsonSafe.elements(JsonSafe.child(identifier, "type"), "coding")) {
                if ("MB".equals(JsonSafe.presentText(coding, "code"))) {
                    typedMb.add(identifier);
                    break;
                }
            }
        }
        if (typedMb.size() == 1) {
            return JsonSafe.presentText(typedMb.get(0), "value");
        }
        if (typedMb.size() > 1) {
            notUsed.add(where + ": a Patient has " + typedMb.size() + " identifiers typed MB, so its member ID was not taken.");
            return null;
        }
        if (identifiers.size() == 1) {
            return JsonSafe.presentText(identifiers.get(0), "value");
        }
        notUsed.add(where + ": a Patient has " + identifiers.size() + " identifiers and none is typed MB"
                + (memberIdSystem == null ? " (the environment names no member ID system)" : " or of the member ID system")
                + ", so its member ID was not taken.");
        return null;
    }

    /** The official name, else the first: its given names and its family name, as written. */
    private static void name(JsonNode resource, Entry entry) {
        List<JsonNode> names = JsonSafe.elements(resource, "name");
        JsonNode chosen = names.stream().filter(each -> "official".equals(JsonSafe.presentText(each, "use"))).findFirst()
                .orElse(names.isEmpty() ? null : names.get(0));
        if (chosen == null) {
            return;
        }
        List<String> given = new ArrayList<>();
        for (JsonNode part : JsonSafe.elements(chosen, "given")) {
            String text = JsonSafe.presentText(part);
            if (text != null) {
                given.add(text.strip());
            }
        }
        entry.put("firstName", given.isEmpty() ? null : String.join(" ", given));
        entry.put("lastName", JsonSafe.presentText(chosen, "family"));
    }

    private static void practitioner(JsonNode resource, String where, String from, List<Entry> providers, List<String> notUsed) {
        Entry entry = new Entry(from);
        String id = JsonSafe.presentText(resource, "id");
        if (id != null && TestListRules.fitsResourceId(id)) {
            entry.put("vendorPractitionerId", id);
        } else if (id != null) {
            notUsed.add(where + ": the id of a Practitioner is not an id a FHIR server gives (letters, digits, - and . only), so it was "
                    + "left out.");
        }
        name(resource, entry);
        for (JsonNode identifier : JsonSafe.elements(resource, "identifier")) {
            String value = JsonSafe.presentText(identifier, "value");
            if (value == null || !NPI_SYSTEM.equals(JsonSafe.presentText(identifier, "system"))) {
                continue;
            }
            if (Npi.isValid(value.strip())) {
                if (entry.get("npi") == null) {
                    entry.put("npi", value);
                }
            } else if (value.strip().length() == 10 && value.strip().chars().allMatch(c -> c >= '0' && c <= '9')) {
                notUsed.add(where + ": the NPI of a Practitioner was left out, because its last digit does not match the other nine.");
            } else {
                notUsed.add(where + ": the NPI of a Practitioner was left out, because it is not 10 digits.");
            }
        }
        if (entry.get("npi") == null && entry.get("vendorPractitionerId") == null) {
            notUsed.add(where + ": a Practitioner states neither an NPI nor an id, so it cannot be saved.");
            return;
        }
        providers.add(entry);
    }

    private static void coverage(JsonNode resource, String where, Map<String, Entry> patients, List<String> notUsed) {
        String beneficiary = JsonSafe.presentText(JsonSafe.child(resource, "beneficiary"), "reference");
        Entry patient = beneficiary == null ? null : patients.get(beneficiary.strip());
        if (patient == null) {
            notUsed.add(where + ": a Coverage names no Patient of the same request as its beneficiary, so it was not used.");
            return;
        }
        put(patient, "subscriberId", JsonSafe.presentText(resource, "subscriberId"), where, notUsed);
        put(patient, "coverageStatus", JsonSafe.presentText(resource, "status"), where, notUsed);
        JsonNode period = JsonSafe.child(resource, "period");
        for (String[] end : new String[][] {{"start", "coverageStart"}, {"end", "coverageEnd"}}) {
            String written = JsonSafe.presentText(period, end[0]);
            if (written == null) {
                continue;
            }
            String day = dayOf(written);
            if (day != null) {
                put(patient, end[1], day, where, notUsed);
            } else {
                notUsed.add(where + ": the " + WORDS.get(end[1]) + " of a Coverage is not a date, so it was left out.");
            }
        }
        for (JsonNode kind : JsonSafe.elements(resource, "class")) {
            boolean plan = false;
            for (JsonNode coding : JsonSafe.elements(JsonSafe.child(kind, "type"), "coding")) {
                plan = plan || "plan".equals(JsonSafe.presentText(coding, "code"));
            }
            if (plan) {
                put(patient, "plan", Texts.firstPresent(JsonSafe.presentText(kind, "name"), JsonSafe.presentText(kind, "value")), where,
                        notUsed);
                break;
            }
        }
    }

    /** Sets a value on an entry of this request, keeping the first where two disagree. */
    private static void put(Entry entry, String field, String value, String where, List<String> notUsed) {
        if (!Texts.present(value)) {
            return;
        }
        String before = entry.get(field);
        if (before == null) {
            entry.put(field, value);
        } else if (!before.equals(value.strip())) {
            notUsed.add(where + ": states two values for the " + WORDS.get(field) + " of one patient; the first is kept.");
        }
    }

    private static void claim(JsonNode resource, String where, String from, List<Entry> codes, List<String> notUsed) {
        for (JsonNode item : JsonSafe.elements(resource, "item")) {
            concept(JsonSafe.child(item, "productOrService"), where, from, codes, notUsed);
            for (JsonNode detail : JsonSafe.elements(item, "detail")) {
                concept(JsonSafe.child(detail, "productOrService"), where, from, codes, notUsed);
                for (JsonNode subDetail : JsonSafe.elements(detail, "subDetail")) {
                    concept(JsonSafe.child(subDetail, "productOrService"), where, from, codes, notUsed);
                }
            }
        }
    }

    /** Each coding of a concept with a code: the code, its system as written, its display or else the concept's text. */
    private static void concept(JsonNode concept, String where, String from, List<Entry> codes, List<String> notUsed) {
        if (!JsonSafe.isObject(concept)) {
            return;
        }
        String text = JsonSafe.presentText(concept, "text");
        for (JsonNode coding : JsonSafe.elements(concept, "coding")) {
            String code = JsonSafe.presentText(coding, "code");
            if (code == null) {
                continue;
            }
            String system = JsonSafe.presentText(coding, "system");
            if (system != null && CodeSystems.MEDICATION.contains(system.strip())) {
                notUsed.add(where + ": a medication code (" + (system.contains("rxnorm") ? "RxNorm" : "NDC") + ") is not a procedure code, "
                        + "so it was left out.");
                continue;
            }
            if (!TestListRules.fitsCode(code.strip())) {
                notUsed.add(where + ": a code longer than " + TestListRules.MAX_CODE + " characters, or with a space in it, was left out.");
                continue;
            }
            Entry entry = new Entry(from);
            entry.put("code", code);
            entry.put("system", system);
            entry.put("description", Texts.firstPresent(JsonSafe.presentText(coding, "display"), text));
            codes.add(entry);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The same entry found twice
    // ------------------------------------------------------------------------------------------

    private interface Same {
        boolean test(Entry one, Entry other);
    }

    private static boolean sameMember(Entry one, Entry other) {
        String a = one.get("memberId");
        String b = other.get("memberId");
        if (a != null && b != null && MemberIds.noSpace(a).equals(MemberIds.noSpace(b))) {
            return true;
        }
        return one.get("vendorPatientId") != null && one.get("vendorPatientId").equals(other.get("vendorPatientId"));
    }

    private static boolean sameProvider(Entry one, Entry other) {
        return (one.get("npi") != null && one.get("npi").equals(other.get("npi")))
                || (one.get("vendorPractitionerId") != null && one.get("vendorPractitionerId").equals(other.get("vendorPractitionerId")));
    }

    private static boolean sameCode(Entry one, Entry other) {
        return one.get("code").equals(other.get("code")) && Texts.orElse(one.get("system"), "").equals(Texts.orElse(other.get("system"), ""));
    }

    /**
     * Adds the entry to the list, or folds it into the entries that are the same: a field one
     * states and the other does not is taken from the one that states it; where both state it and
     * disagree, the first found is kept and a sentence says so. An entry that joins two found
     * earlier (a member ID matching one, a vendor id the other) makes them one.
     */
    private static void merge(List<Entry> list, Entry entry, List<String> fields, Same same, String where, String what, Reading reading,
            int which) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (same.test(list.get(i), entry) || same.test(entry, list.get(i))) {
                matches.add(i);
            }
        }
        if (matches.isEmpty()) {
            if (list.size() >= TestListRules.MAX_ENTRIES) {
                reading.overflow[which]++;
                return;
            }
            list.add(entry);
            return;
        }
        Entry kept = list.get(matches.get(0));
        List<Entry> into = new ArrayList<>();
        for (int k = 1; k < matches.size(); k++) {
            into.add(list.get(matches.get(k)));
        }
        into.add(entry);
        for (Entry other : into) {
            for (String field : fields) {
                String value = other.get(field);
                String before = kept.get(field);
                if (value == null) {
                    continue;
                }
                if (before == null) {
                    kept.put(field, value);
                } else if (!agree(field, before, value)) {
                    reading.notUsed.add(where + ": states another " + WORDS.get(field) + " for a " + what + " that \"" + kept.from
                            + "\" states too; the one from \"" + kept.from + "\" is kept.");
                }
            }
        }
        for (int k = matches.size() - 1; k >= 1; k--) {
            list.remove((int) matches.get(k));
        }
    }

    private static boolean agree(String field, String one, String other) {
        if ("memberId".equals(field)) {
            return MemberIds.noSpace(one).equals(MemberIds.noSpace(other));
        }
        return one.equals(other);
    }

    // ------------------------------------------------------------------------------------------
    // Dates
    // ------------------------------------------------------------------------------------------

    private static boolean wholeDate(String text) {
        if (text.length() != 10) {
            return false;
        }
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /** The day of a FHIR date or dateTime that states a whole day, as yyyy-MM-dd; null for a partial date or no date. */
    private static String dayOf(String text) {
        String value = text.strip();
        if (wholeDate(value)) {
            return value;
        }
        if (value.length() > 10 && value.charAt(10) == 'T' && wholeDate(value.substring(0, 10))) {
            return value.substring(0, 10);
        }
        return null;
    }
}
