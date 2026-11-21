package org.p32h.interop.epa.pave.service.run;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.Texts;
import tools.jackson.databind.JsonNode;

/**
 * The member identifiers a request body states, for a run that names no member of its own: a
 * request sent as written. Its note, tester reference and expectation are then checked against
 * these, as every other run's are checked against the member on its form ({@link MemberInText}).
 *
 * <p>Read where the tool itself reads a member from a body of the tester's own: the identifiers of
 * every Patient and Coverage, and the subscriber ID of every Coverage, wherever the resource sits
 * (a Bundle entry, a CDS Hooks prefetch, a contained resource, a Parameters part). A body that is
 * not JSON, or holds neither resource, states no identifier, and nothing can be checked against it.
 *
 * <p>{@link #readWithPatientIds} also reads where a tester names the patient by an id of their
 * own, as a body of the tester's own sent from a protocol tab may: the id of a Patient, the CDS
 * Hooks {@code context.patientId} and a {@code Patient/<id>} reference. The id the tool's own
 * builders give the patient ({@link CaseResources#PATIENT_ID}) names nobody and is left out, so a
 * note that mentions it is not refused.
 */
public final class MembersInBody {

    private MembersInBody() {
    }

    /** One member per identifier found, holding only that identifier. Empty when none is found. */
    public static List<MemberDetail> read(String body) {
        return read(body, false);
    }

    /** The same, with the ids a body gives its patient read as member IDs as well. */
    public static List<MemberDetail> readWithPatientIds(String body) {
        return read(body, true);
    }

    private static List<MemberDetail> read(String body, boolean patientIds) {
        List<MemberDetail> members = new ArrayList<>();
        if (Texts.blank(body)) {
            return members;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(body);
        if (!parsed.ok()) {
            return members;
        }
        Set<String> memberIds = new LinkedHashSet<>();
        Set<String> subscriberIds = new LinkedHashSet<>();
        collect(parsed.node(), memberIds, subscriberIds, patientIds, 0);
        memberIds.forEach(id -> members.add(MemberDetail.builder().memberId(id).build()));
        subscriberIds.forEach(id -> members.add(MemberDetail.builder().subscriberId(id).build()));
        return members;
    }

    /** How deep a body is walked. A request nests a resource a few levels down, never hundreds. */
    private static final int DEEPEST = 64;

    private static void collect(JsonNode node, Set<String> memberIds, Set<String> subscriberIds, boolean patientIds, int depth) {
        if (node == null || depth > DEEPEST) {
            return;
        }
        String type = JsonSafe.isObject(node) ? JsonSafe.presentText(node, "resourceType") : null;
        if (patientIds && "Patient".equals(type)) {
            addPatientId(memberIds, JsonSafe.presentText(node, "id"));
        }
        if (patientIds && JsonSafe.isObject(node)) {
            addPatientId(memberIds, JsonSafe.presentText(JsonSafe.child(node, "context"), "patientId"));
            String reference = JsonSafe.presentText(node, "reference");
            if (reference != null && reference.startsWith("Patient/")) {
                addPatientId(memberIds, reference.substring("Patient/".length()));
            }
        }
        if ("Patient".equals(type) || "Coverage".equals(type)) {
            for (JsonNode identifier : JsonSafe.elements(node, "identifier")) {
                add(memberIds, JsonSafe.presentText(identifier, "value"));
            }
            if ("Coverage".equals(type)) {
                add(subscriberIds, JsonSafe.presentText(node, "subscriberId"));
            }
        }
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(node)) {
            collect(property.getValue(), memberIds, subscriberIds, patientIds, depth + 1);
        }
        for (JsonNode element : JsonSafe.elements(node)) {
            collect(element, memberIds, subscriberIds, patientIds, depth + 1);
        }
    }

    private static void addPatientId(Set<String> ids, String value) {
        if (Texts.present(value) && !CaseResources.PATIENT_ID.equals(value.strip())) {
            add(ids, value);
        }
    }

    private static void add(Set<String> ids, String value) {
        if (Texts.present(value)) {
            ids.add(value.strip());
        }
    }
}
