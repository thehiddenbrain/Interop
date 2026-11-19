package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import java.util.List;
import java.util.Map;

/**
 * What the mapper made of one CRD response.
 *
 * @param codes            the standard codes the response carried, by element name. This is what a
 *                         declared expectation is compared with
 * @param delegatedVendor  the delegated vendor the response names, or null when it names none
 * @param additionalFields everything the response carried that the designed view did not use
 */
public record CrdReading(CrdDetail detail, CrdOutcome outcome, Map<String, List<String>> codes, String delegatedVendor,
                         List<AdditionalField> additionalFields) {

    /** The codes as {@code name=code} pairs for the ledger. An authorization identifier is left out: it is not a code. */
    public List<String> ledgerCodes() {
        List<String> out = new java.util.ArrayList<>();
        codes.forEach((name, values) -> {
            if (!CrdCodes.SATISFIED_PA_ID.equals(name) && !CrdCodes.DELEGATED_VENDOR.equals(name)) {
                values.forEach(value -> out.add(name + "=" + value));
            }
        });
        return out;
    }
}
