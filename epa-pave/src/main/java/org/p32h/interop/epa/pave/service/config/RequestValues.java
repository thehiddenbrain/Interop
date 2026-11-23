package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.service.crd.PrefetchLayout;
import org.p32h.interop.epa.pave.service.crd.VendorSamples;
import tools.jackson.databind.JsonNode;

/**
 * What the collection request a call is sent from states of the FHIR server and of the payer
 * (D-320). A call takes these values from that request, at the time of the call, when nothing
 * else gives them: the vendor's service refused a coverage check whose Coverage named no payer
 * while the vendor's own request stated one, and FHIR does not allow a Coverage without one.
 *
 * <p>Only these five values, since a request is not valid without them; the member ID system and
 * the PAS types choose content and stay the tester's choice at a save (D-308). Each is read only
 * where PAVE's own builder writes the same value, by fixed paths, so nothing here grows with the
 * depth of the body. Nothing of the member is read: not the beneficiary, not the subscriber, not
 * an identifier of the Coverage.
 */
public final class RequestValues {

    /** The payer of a Coverage as a reference to an Organization the vendor's side holds. */
    public static final String PAYER_REFERENCE = "payerReference";

    /** The values a call takes from its request, in the order of the Edit form. */
    public static final List<String> NAMES = List.of("fhirServer", "payerName", "payerIdentifier", "payerIdentifierSystem", PAYER_REFERENCE);

    /** The payer's values: a line about the payer as a whole is said beside each of them. */
    static final List<String> PAYER = List.of("payerName", "payerIdentifier", "payerIdentifierSystem", PAYER_REFERENCE);

    private RequestValues() {
    }

    /**
     * What one request states, as written, placeholders intact.
     *
     * @param request the request's name with its folders, as a sentence quotes it, or null
     * @param values  by name, in the order of {@link #NAMES}
     * @param lines   by name, why something the request states is not taken
     * @param payer   whether the request states a payer at all, taken or not
     */
    public record Stated(String request, Map<String, String> values, Map<String, List<String>> lines, boolean payer) {

        public static final Stated NONE = new Stated(null, Map.of(), Map.of(), false);

        public Stated {
            values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
            lines = lines == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        }

        /** The lines of one value, empty without any. */
        public List<String> lines(String name) {
            return lines.getOrDefault(name, List.of());
        }

        /** Whether the request states any of the payer's values that can be taken. */
        public boolean statesPayerValue() {
            return PAYER.stream().anyMatch(values::containsKey);
        }

        /** The names only: a value can be the tester's configuration. */
        @Override
        public String toString() {
            return "Stated[" + values.keySet() + ", " + lines.size() + " lines]";
        }
    }

    /**
     * What a call takes from the request it is sent from, each value resolved as the call
     * resolves its body and each refusal said.
     *
     * @param request the request's name with its folders, as a sentence quotes it, or null without one
     * @param values  by name, the text to send, each one a value nothing else may have set
     * @param lines   by name, why a value the request states is not taken
     * @param payer   whether the request states a payer at all, taken or not
     */
    public record ForCall(String request, Map<String, String> values, Map<String, List<String>> lines, boolean payer) {

        public static final ForCall NONE = new ForCall(null, Map.of(), Map.of(), false);

        public ForCall {
            values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
            lines = lines == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        }

        /** The lines of one value, empty without any. */
        public List<String> lines(String name) {
            return lines.getOrDefault(name, List.of());
        }

        /** Every line said of the payer, each once, in the order of the values. */
        public List<String> payerLines() {
            Set<String> out = new LinkedHashSet<>();
            for (String name : PAYER) {
                out.addAll(lines(name));
            }
            return List.copyOf(out);
        }

        /** The names only: a value can hold the tester's configuration. */
        @Override
        public String toString() {
            return "ForCall[" + values.keySet() + ", " + lines.size() + " lines]";
        }
    }

    /** The label of a value, as the Edit form shows it. */
    public static String label(String name) {
        KnownValues.Known known = KnownValues.of(name);
        return known == null ? name : known.label();
    }

    /**
     * The note on a built request that says which values came from the request it is sent from, by
     * label and never by value beyond what the body itself shows; null when none did.
     */
    public static String takenNote(String request, List<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String name : NAMES) {
            if (names.contains(name)) {
                labels.add(label(name));
            }
        }
        boolean one = labels.size() == 1;
        return words(labels) + (one ? " comes" : " come") + " from the collection's request \"" + request + "\", which this call is sent "
                + "from: nothing else sets " + (one ? "it." : "them.");
    }

    /**
     * What a call takes of what its request states: each value resolved as the call resolves its
     * body, and left out, with the rule that kept it out, when it names only itself, resolves
     * through a secret or holds a credential, is longer than a stored value may be (the rules of
     * {@link SampleValues#refusal}), or needs a placeholder nothing defines.
     *
     * @param resolve resolves a text through the layers of the call, without anything of the request
     */
    public static ForCall forCall(Stated stated, java.util.function.Function<String, org.p32h.interop.epa.pave.postman.Resolution> resolve) {
        if (stated == null || (stated.values().isEmpty() && stated.lines().isEmpty())) {
            return ForCall.NONE;
        }
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        stated.lines().forEach((name, said) -> said.forEach(each -> line(lines, name, each)));
        String where = "The collection's request \"" + Texts.orElse(stated.request(), "") + "\"";
        for (Map.Entry<String, String> each : stated.values().entrySet()) {
            String name = each.getKey();
            SampleValues.Taken taken = new SampleValues.Taken(name, each.getValue(), Texts.orElse(stated.request(), ""));
            org.p32h.interop.epa.pave.postman.Resolution resolution = resolve == null ? null : resolve.apply(each.getValue().strip());
            String refusal = SampleValues.refusal(taken, resolution);
            if (refusal != null) {
                line(lines, name, label(name) + ": " + refusal);
                continue;
            }
            if (resolution == null || !resolution.complete() || !Texts.present(resolution.send())) {
                List<String> missing = new ArrayList<>();
                for (String unresolved : resolution == null ? List.<String>of() : resolution.unresolved()) {
                    missing.add("{{" + Names.shown(unresolved) + "}}");
                }
                // By the names of what is missing, never the rest of the text, which is the tester's configuration.
                line(lines, name, where + " writes " + label(name) + " with " + (missing.isEmpty() ? "a placeholder" : words(missing))
                        + ", which nothing defines, so it is not taken.");
                continue;
            }
            String sent = resolution.send().strip();
            if (sent.length() > StoredSettings.VALUE_MAX) {
                line(lines, name, where + " states " + label(name) + " in more than " + StoredSettings.VALUE_MAX + " characters once its "
                        + "placeholders are filled, so it is not taken.");
                continue;
            }
            values.put(name, sent);
        }
        return new ForCall(stated.request(), values, lines, stated.payer());
    }

    // ------------------------------------------------------------------------------------------
    // Reading a request
    // ------------------------------------------------------------------------------------------

    /**
     * What a request's saved body states, read where PAVE's builder writes the same values: a CDS
     * Hooks body gives fhirServer at its top and the payer of the first Coverage of its prefetch; a
     * Parameters body the payer of the first Coverage among its parameters; a Bundle that carries a
     * Claim the Organization Claim.insurer refers to, as D-308 reads it.
     *
     * @param request the request's name with its folders, as a sentence quotes it, or null
     * @param name    the request's own name, which tells a sample of an error
     * @param body    its saved body, placeholders intact, or null
     */
    public static Stated read(String request, String name, String body) {
        if (!Texts.present(body)) {
            return Stated.NONE;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(VendorSamples.placeholdersAsNull(body));
        if (!parsed.ok() || !JsonSafe.isObject(parsed.node())) {
            return Stated.NONE;
        }
        String where = "The collection's request \"" + Texts.orElse(request, "") + "\"";
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        JsonNode root = parsed.node();
        boolean payer;
        if (JsonSafe.presentText(root, "hook") != null) {
            take(values, lines, "fhirServer", JsonSafe.child(root, "fhirServer"), where);
            payer = coverage(values, lines, prefetchCoverage(root), prefetchCarried(root), where);
        } else if ("Parameters".equals(JsonSafe.presentText(root, "resourceType"))) {
            payer = coverage(values, lines, parametersCoverage(root), parametersCarried(root), where);
        } else {
            payer = insurer(values, lines, root, where);
        }
        if (PrefetchLayout.errorSample(name) && (!values.isEmpty() || payer)) {
            // A sample of an error may state what the service refuses, to show the refusal (D-309).
            Map<String, List<String>> refused = new LinkedHashMap<>();
            for (String each : NAMES) {
                line(refused, each, where + " is a sample of an error, so nothing it states is taken.");
            }
            return new Stated(request, Map.of(), refused, payer);
        }
        return new Stated(request, ordered(values), lines, payer);
    }

    /** The first Coverage of a prefetch: alone under a key, or the first Coverage entry of a Bundle under a key. */
    static JsonNode prefetchCoverage(JsonNode root) {
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(JsonSafe.child(root, "prefetch"))) {
            JsonNode value = member.getValue();
            String type = JsonSafe.presentText(value, "resourceType");
            if ("Coverage".equals(type)) {
                return value;
            }
            if ("Bundle".equals(type)) {
                for (JsonNode entry : JsonSafe.elements(value, "entry")) {
                    JsonNode resource = JsonSafe.child(entry, "resource");
                    if ("Coverage".equals(JsonSafe.presentText(resource, "resourceType"))) {
                        return resource;
                    }
                }
            }
        }
        return null;
    }

    /** Every resource a CDS Hooks body carries where a reference can find it, with its fullUrl where it has one. */
    private static List<Carried> prefetchCarried(JsonNode root) {
        List<Carried> out = new ArrayList<>();
        for (JsonNode entry : JsonSafe.elements(JsonSafe.child(JsonSafe.child(root, "context"), "draftOrders"), "entry")) {
            carried(out, JsonSafe.presentText(entry, "fullUrl"), JsonSafe.child(entry, "resource"));
        }
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(JsonSafe.child(root, "prefetch"))) {
            JsonNode value = member.getValue();
            if ("Bundle".equals(JsonSafe.presentText(value, "resourceType"))) {
                for (JsonNode entry : JsonSafe.elements(value, "entry")) {
                    carried(out, JsonSafe.presentText(entry, "fullUrl"), JsonSafe.child(entry, "resource"));
                }
            } else {
                carried(out, null, value);
            }
        }
        return out;
    }

    /** The first Coverage among a Parameters body's parameters. */
    static JsonNode parametersCoverage(JsonNode root) {
        for (JsonNode parameter : JsonSafe.elements(root, "parameter")) {
            JsonNode resource = JsonSafe.child(parameter, "resource");
            if ("Coverage".equals(JsonSafe.presentText(resource, "resourceType"))) {
                return resource;
            }
        }
        return null;
    }

    private static List<Carried> parametersCarried(JsonNode root) {
        List<Carried> out = new ArrayList<>();
        for (JsonNode parameter : JsonSafe.elements(root, "parameter")) {
            carried(out, null, JsonSafe.child(parameter, "resource"));
        }
        return out;
    }

    private record Carried(String fullUrl, JsonNode resource) {
    }

    private static void carried(List<Carried> out, String fullUrl, JsonNode resource) {
        if (JsonSafe.isObject(resource) && JsonSafe.presentText(resource, "resourceType") != null) {
            out.add(new Carried(fullUrl, resource));
        }
    }

    /**
     * The payer of a Coverage, when it has exactly one payor. Its display gives the payer name, its
     * identifier the payer identifier and system. A reference to something the request carries
     * gives that Organization's name and one identifier, and is not taken, since PAVE does not send
     * that resource and the reference would point at nothing; a reference to nothing the request
     * carries names what the vendor's side holds, and is taken as written.
     *
     * @return whether the Coverage states a payer at all
     */
    private static boolean coverage(Map<String, String> values, Map<String, List<String>> lines, JsonNode coverage, List<Carried> carried,
                                    String where) {
        if (coverage == null) {
            return false;
        }
        List<JsonNode> payors = JsonSafe.elements(coverage, "payor");
        if (payors.isEmpty()) {
            payerLine(lines, where + " names no payer on its Coverage.");
            return false;
        }
        if (payors.size() > 1) {
            payerLine(lines, where + " names " + payors.size() + " payers on its Coverage, so none is taken: choose one and type it.");
            return true;
        }
        JsonNode payor = payors.get(0);
        if (!JsonSafe.isObject(payor)) {
            payerLine(lines, where + " states the payer of its Coverage as something other than a reference, so it is not taken.");
            return true;
        }
        take(values, lines, "payerName", JsonSafe.child(payor, "display"), where);
        JsonNode identifier = JsonSafe.child(payor, "identifier");
        if (JsonSafe.presentText(identifier, "value") != null) {
            take(values, lines, "payerIdentifier", JsonSafe.child(identifier, "value"), where);
            take(values, lines, "payerIdentifierSystem", JsonSafe.child(identifier, "system"), where);
        }
        JsonNode referenceNode = JsonSafe.child(payor, "reference");
        String reference = JsonSafe.presentText(referenceNode);
        if (reference == null) {
            if (!JsonSafe.nullOrMissing(referenceNode) && !referenceNode.isString()) {
                line(lines, PAYER_REFERENCE, where + " states the reference of its payer as something other than a text, so it is not "
                        + "taken.");
            }
            return true;
        }
        Found found = find(coverage, carried, reference.strip());
        if (found.count() == 0) {
            take(values, lines, PAYER_REFERENCE, referenceNode, where);
            return true;
        }
        line(lines, PAYER_REFERENCE, where + " refers its payer to a resource it carries itself. PAVE does not send that resource, so the "
                + "reference is not taken" + (found.count() > 1 ? ", and it names more than one, so nothing of them is taken." : "."));
        if (found.count() == 1 && "Organization".equals(JsonSafe.presentText(found.resource(), "resourceType"))) {
            organization(values, lines, found.resource(), where);
        }
        return true;
    }

    private record Found(int count, JsonNode resource) {
    }

    /**
     * The resource a reference names within the request: a contained resource of the Coverage by
     * "#id", or a resource the body carries by type and id or by its fullUrl. A reference that
     * names two is counted as two, and nothing of either is read.
     */
    private static Found find(JsonNode coverage, List<Carried> carried, String reference) {
        if (reference.startsWith("#")) {
            String id = reference.substring(1);
            int count = 0;
            JsonNode found = null;
            for (JsonNode contained : JsonSafe.elements(coverage, "contained")) {
                if (id.equals(JsonSafe.presentText(contained, "id"))) {
                    count++;
                    found = contained;
                }
            }
            return new Found(count, found);
        }
        int count = 0;
        JsonNode found = null;
        for (Carried each : carried) {
            String type = JsonSafe.presentText(each.resource(), "resourceType");
            String id = JsonSafe.presentText(each.resource(), "id");
            String typed = id == null ? null : type + "/" + id;
            boolean names = reference.equals(each.fullUrl()) || reference.equals(typed) || (typed != null && each.fullUrl() != null
                    && each.fullUrl().endsWith("/" + typed) && reference.endsWith("/" + typed));
            if (names) {
                count++;
                found = each.resource();
            }
        }
        return new Found(count, found);
    }

    /**
     * The payer of a Submit or an inquiry Bundle, as D-308 reads it: the Organization Claim.insurer
     * refers to, its name and its one identifier.
     *
     * @return whether the Bundle states an insurer at all
     */
    private static boolean insurer(Map<String, String> values, Map<String, List<String>> lines, JsonNode root, String where) {
        List<JsonNode> claims = SampleValues.claims(root);
        if (claims.size() != 1) {
            return false;
        }
        JsonNode payer = SampleValues.referred(root, JsonSafe.presentText(JsonSafe.child(claims.get(0), "insurer"), "reference"),
                "Organization");
        if (payer == null) {
            return false;
        }
        organization(values, lines, payer, where);
        return true;
    }

    /** An Organization's name, and its identifier when it has exactly one, each only where the payor did not state its own. */
    private static void organization(Map<String, String> values, Map<String, List<String>> lines, JsonNode organization, String where) {
        if (!values.containsKey("payerName")) {
            take(values, lines, "payerName", JsonSafe.child(organization, "name"), where);
        }
        if (values.containsKey("payerIdentifier")) {
            return;
        }
        List<JsonNode> identifiers = JsonSafe.elements(organization, "identifier").stream()
                .filter(each -> JsonSafe.presentText(each, "value") != null).toList();
        if (identifiers.size() == 1) {
            take(values, lines, "payerIdentifier", JsonSafe.child(identifiers.get(0), "value"), where);
            take(values, lines, "payerIdentifierSystem", JsonSafe.child(identifiers.get(0), "system"), where);
        } else if (identifiers.size() > 1) {
            List<String> systems = new ArrayList<>();
            for (JsonNode identifier : identifiers) {
                String system = JsonSafe.presentText(identifier, "system");
                systems.add(system == null ? "one with no system" : Names.shown(system));
            }
            // The systems by name, never the values: which identifier is meant is the tester's to say.
            line(lines, "payerIdentifier", where + " gives the payer " + identifiers.size() + " identifiers (" + words(systems)
                    + "), so none is taken: choose one and type it.");
        }
    }

    private static void take(Map<String, String> values, Map<String, List<String>> lines, String name, JsonNode node, String where) {
        String value = JsonSafe.presentText(node);
        if (value != null) {
            values.put(name, value);
        } else if (!JsonSafe.nullOrMissing(node) && !node.isString()) {
            line(lines, name, where + " states " + label(name) + " as something other than a text, so it is not taken.");
        }
    }

    private static void payerLine(Map<String, List<String>> lines, String line) {
        for (String name : PAYER) {
            line(lines, name, line);
        }
    }

    static void line(Map<String, List<String>> lines, String name, String line) {
        List<String> said = lines.computeIfAbsent(name, key -> new ArrayList<>());
        if (!said.contains(line)) {
            said.add(line);
        }
    }

    private static Map<String, String> ordered(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : NAMES) {
            if (values.containsKey(name)) {
                out.put(name, values.get(name));
            }
        }
        return out;
    }

    static String words(List<String> items) {
        return items.size() < 2 ? String.join("", items)
                : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
