package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the mapper made of one CRD response.
 *
 * @param requestedCodes   the procedure codes the request asked about, in the order sent. Empty
 *                         when the request is not known
 * @param unanswered       the orders that were sent and that no answer in the response is for, each
 *                         named by its code. Empty unless the response answered something
 * @param delegatedVendor  the delegated vendor the response names, or null when it names none
 * @param additionalFields everything the response carried that the designed view did not use
 */
public record CrdReading(CrdDetail detail, CrdOutcome outcome, List<String> requestedCodes, List<String> unanswered,
                         String delegatedVendor, List<AdditionalField> additionalFields) {

    public CrdReading {
        requestedCodes = requestedCodes == null ? List.of() : List.copyOf(requestedCodes);
        unanswered = unanswered == null ? List.of() : List.copyOf(unanswered);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /**
     * True when the result holds more than one thing to tell apart: several answers, or an answer
     * and an order left unanswered. Each is then named by its subject wherever it is shown.
     */
    public boolean several() {
        return detail.coverageInformation().size() + unanswered.size() > 1;
    }

    /**
     * The answers a declared expectation is compared with: one per coverage information extension,
     * each with its own codes, and one marked missing for every order left unanswered. The codes of
     * different answers are never pooled.
     */
    public List<Expectations.Answer> answers() {
        boolean named = several();
        List<Expectations.Answer> out = new ArrayList<>();
        for (CoverageInformationView view : detail.coverageInformation()) {
            out.add(Expectations.Answer.of(named ? view.subject() : null, codes(view)));
        }
        unanswered.forEach(label -> out.add(Expectations.Answer.missing(label)));
        return out;
    }

    /** The standard codes one answer carried, by the element names an expectation uses. */
    public static Map<String, List<String>> codes(CoverageInformationView view) {
        Map<String, List<String>> codes = new LinkedHashMap<>();
        add(codes, CrdCodes.COVERED, view.covered());
        add(codes, CrdCodes.PA_NEEDED, view.paNeeded());
        add(codes, CrdCodes.DOC_NEEDED, view.docNeeded());
        view.docPurpose().forEach(value -> add(codes, CrdCodes.DOC_PURPOSE, value));
        view.infoNeeded().forEach(value -> add(codes, CrdCodes.INFO_NEEDED, value));
        if (view.satisfiedPaId().hasValue()) {
            add(codes, CrdCodes.SATISFIED_PA_ID, view.satisfiedPaId().value());
        }
        if (Texts.present(view.delegatedVendor())) {
            add(codes, CrdCodes.DELEGATED_VENDOR, view.delegatedVendor());
        }
        return codes;
    }

    /**
     * The codes for the ledger, as {@code name=code} pairs. When the result holds several answers
     * each pair starts with its subject, {@code 95810: pa-needed=auth-needed}, and an order left
     * unanswered is written as {@code 95811: (no answer)}, so a later run in which two codes swap
     * answers does not write the same line. An authorization identifier and a vendor name are left
     * out: they are not codes and have their own places on the record.
     */
    public List<String> ledgerCodes() {
        boolean named = several();
        List<String> out = new ArrayList<>();
        for (CoverageInformationView view : detail.coverageInformation()) {
            String prefix = named ? view.subject() + ": " : "";
            codes(view).forEach((name, values) -> {
                if (!CrdCodes.SATISFIED_PA_ID.equals(name) && !CrdCodes.DELEGATED_VENDOR.equals(name)) {
                    values.forEach(value -> out.add(prefix + name + "=" + value));
                }
            });
        }
        unanswered.forEach(label -> out.add(label + ": (no answer)"));
        return out;
    }

    private static void add(Map<String, List<String>> codes, String element, CodedValue value) {
        if (value != null && Texts.present(value.code())) {
            add(codes, element, value.code());
        }
    }

    private static void add(Map<String, List<String>> codes, String element, String value) {
        List<String> values = codes.computeIfAbsent(element, key -> new ArrayList<>());
        if (!values.contains(value)) {
            values.add(value);
        }
    }
}
