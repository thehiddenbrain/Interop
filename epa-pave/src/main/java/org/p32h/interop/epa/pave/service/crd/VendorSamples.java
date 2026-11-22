package org.p32h.interop.epa.pave.service.crd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import tools.jackson.databind.JsonNode;

/**
 * Reads the request shapes a vendor's own sample requests show (R-37). The vendor's sandbox
 * refused a FHIR-valid CRD request that its own collection never sent, so the collection's
 * order-sign sample is the best evidence of the shape it accepts. Evidence is only ever read from
 * a body the vendor wrote; a shape the samples say nothing about keeps its default.
 */
public final class VendorSamples {

    private VendorSamples() {
    }

    /**
     * The shapes the collection's CRD order-sign sample shows, one entry per shape it decides.
     *
     * <p>The sample read is the request a run would use for order-sign when it names none: the
     * first request, in the order the file lists them, that detection maps to order-sign and whose
     * name does not contain "error". A request named for an error is taken for a sample of an
     * error, and such a sample may send the very shape the vendor refuses in order to show the
     * refusal. Reading the same request a run uses keeps the form's evidence and the evidence at
     * run time ({@link #fromBody}) from disagreeing.
     *
     * @return the evidence, empty when the collection has no such request, or its body is not JSON
     *         or does not show the shape
     */
    public static List<ShapeEvidence> read(PostmanCollection collection) {
        List<ShapeEvidence> found = new ArrayList<>();
        PostmanRequest sample = orderSignSample(collection);
        if (sample == null) {
            return found;
        }
        for (RequestShape shape : RequestShape.values()) {
            String value = fromBody(shape, sample.body());
            if (value != null) {
                found.add(new ShapeEvidence(shape, value, sample.name()));
            }
        }
        return found;
    }

    /**
     * The value of the shape a saved request body shows.
     *
     * @param savedBody the body as saved in the collection, placeholders intact, or null
     * @return one of the shape's option values, or null when there is no body, it is not JSON, or
     *         it does not show the shape
     */
    public static String fromBody(RequestShape shape, String savedBody) {
        if (shape == null || !Texts.present(savedBody)) {
            return null;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(placeholdersAsNull(savedBody));
        if (!parsed.ok()) {
            return null;
        }
        return switch (shape) {
            case CRD_DRAFT_ORDERS_TYPE -> draftOrdersType(parsed.node());
            // A CRD sample says nothing about the PAS guide; only the environment states it.
            case PAS_GUIDE -> null;
        };
    }

    private static String draftOrdersType(JsonNode body) {
        JsonNode draftOrders = JsonSafe.at(body, "context", "draftOrders");
        if (!JsonSafe.isObject(draftOrders)) {
            return null;
        }
        // A type key of any value, null included, is a sample that sends the key, which is the
        // FHIR shape and the default; only its absence is evidence for leaving it out.
        return draftOrders.has("type") ? RequestShape.SENT : RequestShape.LEFT_OUT;
    }

    private static PostmanRequest orderSignSample(PostmanCollection collection) {
        if (collection == null) {
            return null;
        }
        for (PostmanRequest request : collection.requests()) {
            OperationDetector.Detection detection = OperationDetector.detect(request.name(), request.method(), request.url(),
                    request.folderPath());
            if (detection.operation() != EpaOperation.CRD_ORDER_SIGN) {
                continue;
            }
            if (Texts.orElse(request.name(), "").toLowerCase(Locale.ROOT).contains("error")) {
                continue;
            }
            return request;
        }
        return null;
    }

    /**
     * The body with every placeholder written outside a JSON string replaced by null. Postman
     * bodies write a number or an object as a bare placeholder, and such a body is still the
     * vendor's sample: refusing to read it would lose the evidence over a value the shape never
     * depends on. A placeholder inside a string is already valid JSON and stays as written. One
     * pass over the characters, so its depth never grows with the body.
     */
    static String placeholdersAsNull(String body) {
        StringBuilder out = new StringBuilder(body.length());
        boolean inString = false;
        int i = 0;
        int length = body.length();
        while (i < length) {
            char c = body.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < length) {
                    out.append(body.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '{' && i + 1 < length && body.charAt(i + 1) == '{') {
                int close = body.indexOf("}}", i + 2);
                if (close > i + 2 && body.substring(i + 2, close).indexOf('{') < 0) {
                    out.append("null");
                    i = close + 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
