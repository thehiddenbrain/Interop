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
 */
public record SentOrder(String reference, String code) {

    /** How the order is named on a result: its code, else its reference. */
    public String label() {
        return Texts.present(code) ? code : Texts.orElse(reference, "an order with no code and no id");
    }

    /**
     * The orders in a request body the tester supplied, read from {@code context.draftOrders}.
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
            String id = JsonSafe.presentText(resource, "id");
            String reference = type != null && id != null ? type + "/" + id : id;
            List<String> codes = codes(resource);
            out.add(new SentOrder(reference, codes.isEmpty() ? null : codes.get(0)));
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
