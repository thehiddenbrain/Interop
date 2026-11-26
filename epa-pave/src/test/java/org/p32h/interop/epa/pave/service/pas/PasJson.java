package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds PAS request and response bodies for tests, in the shape the Da Vinci guide gives them, so
 * each test states only the part it is about. Every value here is synthetic.
 */
public final class PasJson {

    public static final String X12_306 = "https://codesystem.x12.org/005010/306";

    private PasJson() {
    }

    /** A review action carrying the given code, with the code part named by its full url. */
    public static String action(String code) {
        return action(code, "");
    }

    /** A review action with further parts after the code, each written as JSON and starting with a comma. */
    public static String action(String code, String furtherParts) {
        return "{\"url\":\"" + OnyxPasConventions.REVIEW_ACTION + "\",\"extension\":[{\"url\":\"" + OnyxPasConventions.REVIEW_ACTION_CODE
                + "\",\"valueCodeableConcept\":{\"coding\":[{\"system\":\"" + X12_306 + "\",\"code\":\"" + code + "\"}]}}" + furtherParts + "]}";
    }

    /** A review action whose parts are given exactly. */
    public static String actionOf(String parts) {
        return "{\"url\":\"" + OnyxPasConventions.REVIEW_ACTION + "\",\"extension\":[" + parts + "]}";
    }

    /** An item whose one adjudication carries the given extensions. */
    public static String item(int sequence, String... adjudicationExtensions) {
        return "{\"itemSequence\":" + sequence + ",\"adjudication\":[{\"category\":{\"coding\":[{\"code\":\"submitted\"}]}"
                + (adjudicationExtensions.length == 0 ? "" : ",\"extension\":[" + String.join(",", adjudicationExtensions) + "]") + "}]}";
    }

    /** An item decided by one review action code. */
    public static String decided(int sequence, String code) {
        return item(sequence, action(code));
    }

    /** A ClaimResponse with the id cr-1 and the given further fields, each written as "name":value. */
    public static String claimResponse(String... fields) {
        return claimResponseWithId("cr-1", fields);
    }

    public static String claimResponseWithId(String id, String... fields) {
        StringBuilder out = new StringBuilder("{\"resourceType\":\"ClaimResponse\",\"id\":\"" + id + "\"");
        for (String field : fields) {
            out.append(',').append(field);
        }
        return out.append('}').toString();
    }

    public static String items(String... items) {
        return "\"item\":[" + String.join(",", items) + "]";
    }

    public static String bundle(String... resources) {
        List<String> entries = new ArrayList<>();
        for (String resource : resources) {
            entries.add("{\"resource\":" + resource + "}");
        }
        return "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[" + String.join(",", entries) + "]}";
    }

    /** A request bundle carrying a claim with the given identifier, or none when it is null, and one item for each code. */
    public static String request(String claimId, String... procedureCodes) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < procedureCodes.length; i++) {
            items.append(i == 0 ? "" : ",").append("{\"sequence\":").append(i + 1)
                    .append(",\"productOrService\":{\"coding\":[{\"code\":\"").append(procedureCodes[i]).append("\"}]}}");
        }
        String identifier = claimId == null ? "" : "\"identifier\":[{\"value\":\"" + claimId + "\"}],";
        return "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Claim\",\"id\":\"claim-1\","
                + identifier + "\"use\":\"preauthorization\",\"item\":[" + items + "]}}]}";
    }

    /** A request that carried one item for each code, numbered from one, under the claim identifier claim-test-1. */
    public static SentClaim sent(String... procedureCodes) {
        List<SentClaim.Item> items = new ArrayList<>();
        for (int i = 0; i < procedureCodes.length; i++) {
            items.add(new SentClaim.Item(i + 1, procedureCodes[i]));
        }
        return new SentClaim("claim-test-1", items);
    }

    /** A PAS response with the given status and body, with the vendor signals applied as the clients apply them. */
    public static VendorExchange response(int status, String body) {
        VendorExchange exchange = TestExchanges.of(EpaOperation.PAS_SUBMIT, status, body, true);
        return exchange.withSignals(OnyxPasConventions.signals(exchange));
    }
}
