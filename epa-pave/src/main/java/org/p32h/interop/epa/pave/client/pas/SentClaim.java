package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What a PAS request carried that a response is read against: the identifier on the claim and
 * each item with its procedure code.
 *
 * <p>A ClaimResponse answers items by their sequence, and a tester asks about procedure codes.
 * Without the list of what was sent the tool could only assume which decision belongs to which
 * code, and could not see that an item received no decision at all.
 *
 * @param claimId the identifier on the claim, or null when the request carried none
 * @param items   each item sent, in the order sent. Empty when the request is not known
 */
public record SentClaim(String claimId, List<Item> items) {

    /**
     * One item of the claim.
     *
     * @param sequence    the item's sequence, which is how a response refers to it. Null when the item had none
     * @param code        the procedure code on the item, or null when it carried none
     * @param traceNumber the value of the item's first itemTraceNumber, which PAS 2.0.1 lets a
     *                    ClaimResponse item echo. Null when the item carried none
     */
    public record Item(Integer sequence, String code, String traceNumber) {

        /** An item that carried no trace number. */
        public Item(Integer sequence, String code) {
            this(sequence, code, null);
        }

        /** How the item is named on a result: its code, else its sequence. */
        public String label() {
            return Texts.present(code) ? code : sequence == null ? "unidentified-item" : "item " + sequence;
        }
    }

    public SentClaim {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A request about which nothing is known. No item is then said to be undecided. */
    public static SentClaim unknown() {
        return new SentClaim(null, List.of());
    }

    /**
     * The claim in a request body the tester supplied, or in a body a mock is answering: the first
     * Claim of the bundle, or the body itself when it is a Claim. Nothing is known about a body
     * that is not JSON or carries no claim.
     */
    public static SentClaim fromBody(String body) {
        JsonSafe.Parsed parsed = JsonSafe.parse(body);
        if (!parsed.ok()) {
            return unknown();
        }
        for (JsonNode resource : OnyxPasConventions.resources(parsed.node())) {
            if (!"Claim".equals(JsonSafe.text(resource, "resourceType"))) {
                continue;
            }
            String claimId = null;
            for (JsonNode identifier : JsonSafe.elements(resource, "identifier")) {
                claimId = JsonSafe.presentText(identifier, "value");
                if (claimId != null) {
                    break;
                }
            }
            List<Item> items = new ArrayList<>();
            for (JsonNode item : JsonSafe.elements(resource, "item")) {
                String code = null;
                for (JsonNode coding : JsonSafe.elements(JsonSafe.child(item, "productOrService"), "coding")) {
                    code = JsonSafe.presentText(coding, "code");
                    if (code != null) {
                        break;
                    }
                }
                items.add(new Item(JsonSafe.integer(item, "sequence"), code, traceNumbers(item).stream().findFirst().orElse(null)));
            }
            return new SentClaim(claimId, items);
        }
        return unknown();
    }

    /**
     * The values of the itemTraceNumber extensions on a Claim item or a ClaimResponse item, in
     * order. Read without consuming anything, so the extensions are still shown where they are.
     */
    public static List<String> traceNumbers(JsonNode item) {
        List<String> out = new ArrayList<>();
        for (JsonNode extension : JsonSafe.elements(item, "extension")) {
            if (OnyxPasConventions.pasExtension(JsonSafe.presentText(extension, "url"), OnyxPasConventions.ITEM_TRACE_NUMBER)) {
                String value = JsonSafe.presentText(JsonSafe.child(extension, "valueIdentifier"), "value");
                if (value != null) {
                    out.add(value.strip());
                }
            }
        }
        return out;
    }

    /** The procedure codes of the items, in order, without repeats and without items that carry none. */
    public List<String> codes() {
        List<String> out = new ArrayList<>();
        for (Item item : items) {
            if (Texts.present(item.code()) && !out.contains(item.code())) {
                out.add(item.code());
            }
        }
        return out;
    }
}
