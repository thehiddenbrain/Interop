package org.p32h.interop.epa.pave.service.run;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.crd.PrefetchLayout;
import org.p32h.interop.epa.pave.service.crd.VendorSamples;
import tools.jackson.databind.JsonNode;

/**
 * What the collection request a step is sent from says of the diagnosis and of who performs the
 * service, by presence and by type only (D-321): whether its order carries a reasonCode and which
 * system that uses, whether it names a performer and the resource type it refers to, and, for a
 * Submit, whether its Claim carries a diagnosis or a care team provider.
 *
 * <p>No code, no id and no name of the sample is read into here, only presence, a coding system
 * address and a resource type. The body is read once as a tree and only by fixed paths, at most
 * three levels to a resource, so nothing here grows with the depth of the body.
 *
 * @param reason          the order carries a reasonCode with at least one element
 * @param reasonSystem    the system of the first coding of that reasonCode, or null when it states none or a placeholder
 * @param performer       the order carries a performer with at least one element
 * @param performerType   the resource type the order's first performer refers to, such as Practitioner, or null
 * @param claimDiagnosis  a Claim carries a diagnosis with at least one element
 * @param careTeam        a Claim carries a care team member with a provider
 */
public record SampleOrder(boolean reason, String reasonSystem, boolean performer, String performerType, boolean claimDiagnosis,
                          boolean careTeam) {

    /** A call that follows no sample, or a sample that says nothing of either. */
    public static final SampleOrder NONE = new SampleOrder(false, null, false, null, false, false);

    /** A FHIR resource type: a capital, then letters, at most forty in all. Anything else (a placeholder, a url) is not kept. */
    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Za-z]{1,39}");

    /** True when the sample carries a diagnosis where its step sends one: on its order, or on its Claim. */
    public boolean carriesDiagnosis() {
        return reason || claimDiagnosis;
    }

    /** True when the sample names who performs the service: its order's performer, or a care team provider. */
    public boolean carriesPerformer() {
        return performer || careTeam;
    }

    /**
     * What the sample of a call says, or {@link #NONE} for a call with no sample, or one whose name
     * says it is a sample of an error, which is never followed (D-309).
     */
    public static SampleOrder ofCall(String requestName, String savedBody) {
        if (PrefetchLayout.errorSample(requestName)) {
            return NONE;
        }
        return read(savedBody);
    }

    /** What a saved body says; {@link #NONE} for no body or one that is not JSON. */
    public static SampleOrder read(String savedBody) {
        if (!Texts.present(savedBody)) {
            return NONE;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(VendorSamples.placeholdersQuoted(savedBody));
        if (!parsed.ok() || !JsonSafe.isObject(parsed.node())) {
            return NONE;
        }
        JsonNode root = parsed.node();
        JsonNode order = firstOrder(root);
        JsonNode claim = first(root, "Claim");
        boolean reason = !JsonSafe.elements(order, "reasonCode").isEmpty();
        String system = null;
        if (reason) {
            JsonNode concept = JsonSafe.elements(order, "reasonCode").get(0);
            List<JsonNode> codings = JsonSafe.elements(concept, "coding");
            system = codings.isEmpty() ? null : stated(JsonSafe.presentText(codings.get(0), "system"));
        }
        List<JsonNode> performers = JsonSafe.elements(order, "performer");
        String type = performers.isEmpty() ? null : referredType(performers.get(0));
        boolean careTeam = false;
        for (JsonNode member : JsonSafe.elements(claim, "careTeam")) {
            careTeam |= JsonSafe.isObject(JsonSafe.child(member, "provider"));
        }
        return new SampleOrder(reason, system, !performers.isEmpty(), type, !JsonSafe.elements(claim, "diagnosis").isEmpty(), careTeam);
    }

    /** The type a Reference refers to: its own type, else the part of its reference before the first slash. */
    private static String referredType(JsonNode reference) {
        String type = JsonSafe.presentText(reference, "type");
        if (type == null) {
            String written = JsonSafe.presentText(reference, "reference");
            int slash = written == null ? -1 : written.indexOf('/');
            type = slash > 0 ? written.substring(0, slash) : null;
        }
        return type != null && TYPE.matcher(type.strip()).matches() ? type.strip() : null;
    }

    /**
     * The order a service decides on: the first ServiceRequest of a CDS Hooks request's
     * context.draftOrders, else of its prefetch (alone under a key or an entry of a Bundle), else
     * the first ServiceRequest among the parameters of a Parameters body or the entries of a Bundle.
     */
    static JsonNode firstOrder(JsonNode root) {
        for (JsonNode entry : JsonSafe.elements(JsonSafe.child(JsonSafe.child(root, "context"), "draftOrders"), "entry")) {
            if (is(JsonSafe.child(entry, "resource"), "ServiceRequest")) {
                return JsonSafe.child(entry, "resource");
            }
        }
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(JsonSafe.child(root, "prefetch"))) {
            JsonNode value = member.getValue();
            if (is(value, "ServiceRequest")) {
                return value;
            }
            if (is(value, "Bundle")) {
                for (JsonNode entry : JsonSafe.elements(value, "entry")) {
                    if (is(JsonSafe.child(entry, "resource"), "ServiceRequest")) {
                        return JsonSafe.child(entry, "resource");
                    }
                }
            }
        }
        return first(root, "ServiceRequest");
    }

    /** The first resource of a type among the parameters of a Parameters body or the entries of a Bundle, or null. */
    private static JsonNode first(JsonNode root, String type) {
        for (String list : List.of("parameter", "entry")) {
            for (JsonNode item : JsonSafe.elements(root, list)) {
                JsonNode resource = JsonSafe.child(item, "resource");
                if (is(resource, type)) {
                    return resource;
                }
            }
        }
        return null;
    }

    private static boolean is(JsonNode resource, String type) {
        return JsonSafe.isObject(resource) && type.equals(JsonSafe.presentText(resource, "resourceType"));
    }

    /** A system the sample states itself: a placeholder is the vendor's variable, whose value is never taken from here. */
    private static String stated(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        return value.startsWith("{{") && value.endsWith("}}") ? null : value;
    }

    /** Presence and types only. */
    @Override
    public String toString() {
        return "SampleOrder[reason=" + reason + ", performer=" + performer + "]";
    }
}
