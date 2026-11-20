package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parsed body of a response with every place that repeats a secret of its request masked,
 * for the PAS and subscription mappers to read instead of the body as parsed.
 *
 * <p>The wire view masks a secret the service sends back, but a mapper reads the parsed body, and
 * whatever it copies into the designed view or the additional fields would otherwise show the
 * secret on screen: a service that quotes the bearer token in ClaimResponse.disposition puts it in
 * the result the browser receives. Masking the tree once, before any mapper reads it, covers every
 * value a mapper copies, including the ones nobody thought of, with the same masks the wire view
 * uses. A value used in logic sees the mask too. No identifier, code or authorization number this
 * tool reads can hold a secret of the request without the service having copied it there, and
 * then the mask is what may be shown and kept.
 *
 * <p>Only strings and property names are searched. A secret written as a JSON number is not
 * found, as the wire view would not find one written in any form other than its characters.
 */
final class ReturnedSecrets {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ReturnedSecrets() {
    }

    /** The exchange with its parsed body masked, or the exchange itself when nothing in the body repeats a secret. */
    static VendorExchange masked(VendorExchange exchange) {
        if (exchange == null || exchange.json() == null || exchange.request() == null) {
            return exchange;
        }
        SentSecrets secrets = exchange.request().secrets();
        if (secrets == null || secrets.searched() == 0) {
            return exchange;
        }
        int[] places = {0};
        JsonNode masked = mask(exchange.json(), secrets, places);
        return places[0] == 0 ? exchange : exchange.withJson(masked);
    }

    private static JsonNode mask(JsonNode node, SentSecrets secrets, int[] places) {
        if (node.isObject()) {
            ObjectNode out = JSON.objectNode();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                SentSecrets.Masked name = secrets.mask(property.getKey());
                places[0] += name.places();
                String key = name.text();
                // Two names that differ only in a secret would mask to the same name. The second is
                // kept beside the first rather than written over it, so nothing is lost.
                while (out.has(key)) {
                    key = key + " (again)";
                }
                out.set(key, mask(property.getValue(), secrets, places));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.arrayNode();
            for (JsonNode element : node) {
                out.add(mask(element, secrets, places));
            }
            return out;
        }
        if (node.isString()) {
            SentSecrets.Masked value = secrets.mask(node.stringValue());
            if (value.places() > 0) {
                places[0] += value.places();
                return JSON.stringNode(value.text());
            }
        }
        return node;
    }
}
