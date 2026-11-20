package org.p32h.interop.epa.pave.client.crd;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One order a CRD request carried: how its resource is referred to and the procedure code on it.
 *
 * <p>This is what lets an answer be tied to the code it is for. A response answers orders, not
 * codes, so without the list of what was sent the tool could only assume which answer belongs to
 * which code, and could not see that a code received no answer at all.
 *
 * @param reference the order as the response would refer to it, for example
 *                  {@code ServiceRequest/service-request-1}. Null when the resource had no id
 * @param code      the procedure code on the order, or null when it carried none
 * @param position  where the order stands among the orders of a body the tester supplied,
 *                  counting from one. Null for an order this tool built, which always has a code
 */
public record SentOrder(String reference, String code, Integer position) {

    /**
     * The resource types CRD 2.0.1 profiles as orders that an order hook carries in draftOrders.
     * Anything else in the bundle, a Patient for one, is not an order and is not read as one.
     */
    public static final List<String> ORDER_TYPES = List.of("ServiceRequest", "DeviceRequest", "MedicationRequest", "NutritionOrder",
            "CommunicationRequest", "VisionPrescription");

    /** An order this tool built, which carries a code. */
    public SentOrder(String reference, String code) {
        this(reference, code, null);
    }

    /**
     * How the order is named on a result and on the ledger line: its code, else its position. An
     * order with no code is never named by its id: in a body the tester wrote, an id is free text
     * and can be a member's name or date of birth.
     */
    public String label() {
        if (Texts.present(code)) {
            return code;
        }
        return position != null ? "order " + position + " (no code)" : "unidentified-order";
    }

    /**
     * The orders in a request body the tester supplied, read from {@code context.draftOrders}: the
     * entries whose resource is one of {@link #ORDER_TYPES}, numbered in the order they stand.
     * Empty when the body is not JSON or carries no draft orders: the result then names answers by
     * the resource they apply to, and no code is said to be unanswered.
     */
    public static List<SentOrder> fromBody(String body) {
        List<SentOrder> out = new ArrayList<>();
        JsonSafe.Parsed parsed = JsonSafe.parse(body);
        if (!parsed.ok()) {
            return out;
        }
        JsonNode bundle = JsonSafe.child(JsonSafe.child(parsed.node(), "context"), "draftOrders");
        for (JsonNode entry : JsonSafe.elements(bundle, "entry")) {
            JsonNode resource = JsonSafe.child(entry, "resource");
            if (!resource.isObject()) {
                continue;
            }
            String type = JsonSafe.presentText(resource, "resourceType");
            if (!ORDER_TYPES.contains(type)) {
                continue;
            }
            String id = JsonSafe.presentText(resource, "id");
            String reference = id != null ? type + "/" + id : null;
            List<String> codes = codes(resource);
            out.add(new SentOrder(reference, codes.isEmpty() ? null : codes.get(0), out.size() + 1));
        }
        return out;
    }

    /**
     * The codes an order resource carries, in order. A ServiceRequest holds them in {@code code};
     * the other order types CRD accepts hold them under a differently named concept.
     */
    public static List<String> codes(JsonNode resource) {
        List<String> out = new ArrayList<>();
        for (String field : List.of("code", "codeCodeableConcept", "medicationCodeableConcept")) {
            for (JsonNode coding : JsonSafe.elements(JsonSafe.child(resource, field), "coding")) {
                String code = JsonSafe.presentText(coding, "code");
                if (code != null && !out.contains(code)) {
                    out.add(code);
                }
            }
        }
        return out;
    }
}
