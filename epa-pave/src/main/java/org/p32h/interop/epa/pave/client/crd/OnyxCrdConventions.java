package org.p32h.interop.epa.pave.client.crd;

import org.p32h.interop.epa.pave.model.DelegatedVendors;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * ASSUMPTIONS ABOUT THE VENDOR, ALL IN ONE FILE.
 *
 * <p>The Da Vinci CRD profile has no standard code for "member not found", "provider not found",
 * "coverage inactive" or "this routes to a delegated vendor". How the live service expresses those
 * has not been observed. Every rule below is therefore an assumption. Each one reads a coded
 * element, never card wording, and each is deliberately narrow: when a response does not match a
 * rule, nothing is inferred and the response is shown in full under the outcome the standard codes
 * support.
 *
 * <p>When the real behaviour is known, this is the file to change, together with the mock scenario
 * files that say they model these cases.
 */
public final class OnyxCrdConventions {

    /** Normalised detail codes taken to mean "the value is the delegated vendor". */
    private static final Set<String> VENDOR_DETAIL_CODES = Set.of("delegatedvendor", "delegatedumvendor", "umvendor",
            "delegatedentity", "delegatedto", "delegate", "vendor");

    /** Normalised reason codes taken to mean "coverage is not active on the date of service". */
    private static final Set<String> COVERAGE_INACTIVE_CODES = Set.of("coverageinactive", "inactivecoverage",
            "coveragenotactive", "coverageterminated", "notactive");

    private static final List<String> MEMBER_WORDS = List.of("patient", "member", "coverage", "subscriber", "beneficiary");
    private static final List<String> PROVIDER_WORDS = List.of("practitioner", "provider", "organization", "requester",
            "performer", "npi");

    private OnyxCrdConventions() {
    }

    /**
     * ASSUMPTION: an unknown member or provider arrives as a FHIR OperationOutcome whose issue code
     * is {@code not-found}, and the issue says which by naming the element in {@code expression},
     * {@code location} or a {@code details} coding. An issue that is not-found but names neither is
     * not classified.
     */
    public static Set<VendorSignal> signals(VendorExchange exchange) {
        EnumSet<VendorSignal> signals = EnumSet.noneOf(VendorSignal.class);
        JsonNode body = exchange == null ? null : exchange.json();
        if (body == null || !OperationOutcomes.is(body)) {
            return signals;
        }
        for (JsonNode issue : JsonSafe.elements(body, "issue")) {
            if (!"not-found".equals(JsonSafe.text(issue, "code"))) {
                continue;
            }
            List<String> pointers = new ArrayList<>();
            JsonSafe.elements(issue, "expression").forEach(e -> pointers.add(JsonSafe.text(e)));
            JsonSafe.elements(issue, "location").forEach(e -> pointers.add(JsonSafe.text(e)));
            JsonSafe.elements(JsonSafe.child(issue, "details"), "coding").forEach(c -> pointers.add(JsonSafe.text(c, "code")));
            boolean member = mentions(pointers, MEMBER_WORDS);
            boolean provider = mentions(pointers, PROVIDER_WORDS);
            // An issue that names both is ambiguous, so it is left unclassified.
            if (member && !provider) {
                signals.add(VendorSignal.MEMBER_NOT_FOUND);
            } else if (provider && !member) {
                signals.add(VendorSignal.PROVIDER_NOT_FOUND);
            }
        }
        return signals;
    }

    /**
     * ASSUMPTION: inactive coverage arrives as a coverage information {@code reason} whose coding
     * carries one of a small set of codes. The text of the reason is not consulted.
     *
     * @param reasonCodes the code values of the reason's codings
     */
    public static boolean coverageInactive(List<String> reasonCodes) {
        return reasonCodes != null && reasonCodes.stream().anyMatch(code -> COVERAGE_INACTIVE_CODES.contains(normalise(code)));
    }

    /** The result of looking for a delegated vendor in one detail entry. */
    public record VendorMatch(String vendor, String basis) {
    }

    /**
     * ASSUMPTION: the delegated vendor arrives in the coverage information {@code detail} list,
     * either under a detail code that says so or as a detail value that is itself the name of a
     * current delegated vendor.
     *
     * @param detailCodes the code values and the text of the detail's code
     * @param value       the detail's value as text
     * @return the vendor and how it was recognised, or null when this detail does not name one
     */
    public static VendorMatch delegatedVendor(List<String> detailCodes, String value) {
        if (Texts.blank(value)) {
            return null;
        }
        if (detailCodes != null) {
            for (String code : detailCodes) {
                if (VENDOR_DETAIL_CODES.contains(normalise(code))) {
                    return new VendorMatch(DelegatedVendors.canonical(value), "the detail code \"" + code.strip() + "\"");
                }
            }
        }
        if (DelegatedVendors.known(value)) {
            return new VendorMatch(DelegatedVendors.canonical(value), "a detail value that is the name of a current delegated vendor");
        }
        return null;
    }

    private static boolean mentions(List<String> pointers, List<String> words) {
        for (String pointer : pointers) {
            if (Texts.present(pointer)) {
                String lower = pointer.toLowerCase(Locale.ROOT);
                if (words.stream().anyMatch(lower::contains)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalise(String code) {
        return code == null ? "" : code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
