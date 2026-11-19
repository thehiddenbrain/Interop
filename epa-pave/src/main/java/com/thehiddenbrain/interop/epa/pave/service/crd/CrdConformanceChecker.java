package com.thehiddenbrain.interop.epa.pave.service.crd;

import com.thehiddenbrain.interop.epa.pave.model.AuthRequirement;
import com.thehiddenbrain.interop.epa.pave.model.CodeInput;
import com.thehiddenbrain.interop.epa.pave.model.DelegatedVendors;
import com.thehiddenbrain.interop.epa.pave.model.Finding;
import com.thehiddenbrain.interop.epa.pave.model.FindingKind;
import com.thehiddenbrain.interop.epa.pave.model.Presence;
import com.thehiddenbrain.interop.epa.pave.model.ProcedureCode;
import com.thehiddenbrain.interop.epa.pave.model.Texts;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.model.crd.CodedValue;
import com.thehiddenbrain.interop.epa.pave.model.crd.CoverageInformationView;
import com.thehiddenbrain.interop.epa.pave.service.ProcedureCodeDirectory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a CRD response against what the tool knows, and reports what a person should look at.
 *
 * <p>A finding is not a failure of the call. It is kept apart from the determination, and where
 * the code list and the service disagree the finding states both positions and blames neither,
 * since either side can be the one that is wrong. That disagreement is the single most useful
 * thing this tool reports: it is the question the programme exists to answer.
 */
public final class CrdConformanceChecker {

    private final ProcedureCodeDirectory codeList;

    /** @param codeList the procedure code list. May be null, in which case the two code list checks are skipped */
    public CrdConformanceChecker(ProcedureCodeDirectory codeList) {
        this.codeList = codeList;
    }

    /**
     * @param codes          the procedure codes that were asked about, in the order they were sent
     * @param lineOfBusiness the member's line of business, or null when it is not known
     * @param dateOfService  the date of service, or null
     */
    public List<Finding> check(CrdReading reading, VendorExchange exchange, List<CodeInput> codes, String lineOfBusiness,
                               LocalDate dateOfService) {
        List<Finding> findings = new ArrayList<>();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();

        for (CoverageInformationView view : coverage) {
            String where = Texts.orElse(view.appliesTo().orNull(), view.foundAt());
            outsideValueSet(findings, where, CrdCodes.COVERED, view.covered());
            outsideValueSet(findings, where, CrdCodes.PA_NEEDED, view.paNeeded());
            outsideValueSet(findings, where, CrdCodes.DOC_NEEDED, view.docNeeded());
            view.docPurpose().forEach(value -> outsideValueSet(findings, where, CrdCodes.DOC_PURPOSE, value));
            view.infoNeeded().forEach(value -> outsideValueSet(findings, where, CrdCodes.INFO_NEEDED, value));

            if (view.covered().code() == null && view.paNeeded().code() == null) {
                findings.add(new Finding(FindingKind.REQUIRED_ELEMENT_MISSING,
                        "Coverage information with no answer",
                        "The coverage information at " + view.foundAt() + " carries neither covered nor pa-needed. It has told the "
                                + "tester nothing about this service.", where, null, null));
            }
            emptyElement(findings, where, CrdCodes.COVERED, view.covered());
            emptyElement(findings, where, CrdCodes.PA_NEEDED, view.paNeeded());
            emptyElement(findings, where, CrdCodes.DOC_NEEDED, view.docNeeded());
        }

        if (exchange.ok() && coverage.isEmpty()) {
            findings.add(new Finding(FindingKind.REQUIRED_ELEMENT_MISSING,
                    "No coverage information in the response",
                    "The service answered successfully but the response carries no coverage information extension, in a system "
                            + "action, on a card or on a suggestion. There is no determination to check.", null, null, null));
        }

        if (codeList != null && exchange.ok() && !coverage.isEmpty() && codes != null) {
            for (int i = 0; i < codes.size(); i++) {
                CoverageInformationView view = viewFor(coverage, i);
                if (view != null && codes.get(i) != null && Texts.present(codes.get(i).code())) {
                    compareWithCodeList(findings, codes.get(i).code().strip(), view, lineOfBusiness, dateOfService);
                }
            }
        }
        return findings;
    }

    /**
     * The coverage information that answers the code at this position. Each code is sent as
     * {@code service-request-N}, so an answer attached to that id is the answer for that code. A
     * single answer with no such id is taken to cover the whole order. Anything else cannot be
     * paired with a code and is not compared.
     */
    private static CoverageInformationView viewFor(List<CoverageInformationView> coverage, int index) {
        String wanted = "ServiceRequest/service-request-" + (index + 1);
        for (CoverageInformationView view : coverage) {
            if (wanted.equals(view.appliesTo().orNull())) {
                return view;
            }
        }
        if (coverage.size() == 1 && !coverage.get(0).appliesTo().hasValue()) {
            return coverage.get(0);
        }
        return null;
    }

    private void compareWithCodeList(List<Finding> findings, String code, CoverageInformationView view, String lineOfBusiness,
                                     LocalDate dateOfService) {
        if (Texts.present(lineOfBusiness) && !codeList.statesLine(lineOfBusiness)) {
            // The list does not know this line at all, so it has no position to compare. Comparing
            // against another line's rows would conflate lines of business.
            return;
        }
        List<ProcedureCode> rows = codeList.rowsFor(code, lineOfBusiness, dateOfService);
        if (rows.isEmpty()) {
            return;
        }
        String line = Texts.present(lineOfBusiness) ? " for " + lineOfBusiness.strip() : "";

        Set<AuthRequirement> stated = new LinkedHashSet<>();
        rows.forEach(row -> {
            if (row.authRequired() != AuthRequirement.UNKNOWN) {
                stated.add(row.authRequired());
            }
        });
        Boolean serviceRequires = serviceRequiresAuth(view.paNeeded());
        // Compared only when both sides take a position. A list that does not say is not a "no".
        if (stated.size() == 1 && serviceRequires != null) {
            boolean listRequires = stated.contains(AuthRequirement.REQUIRED);
            if (listRequires != serviceRequires) {
                findings.add(new Finding(FindingKind.CODE_LIST_DISAGREES_ON_AUTH,
                        "The code list and the service disagree on prior authorization for " + code,
                        "One of them is wrong, and this tool cannot tell which.", code,
                        "The code list says prior authorization is " + (listRequires ? "required" : "not required") + line + ".",
                        "The service answered pa-needed=" + view.paNeeded().code() + "."));
            }
        }

        Set<String> listVendors = new LinkedHashSet<>();
        rows.forEach(row -> {
            if (Texts.present(row.delegatedVendor())) {
                listVendors.add(DelegatedVendors.canonical(row.delegatedVendor()));
            }
        });
        if (listVendors.size() == 1) {
            String listVendor = listVendors.iterator().next();
            if (Texts.blank(view.delegatedVendor())) {
                // A response that needs no authorization has no reason to name a vendor.
                if (Boolean.TRUE.equals(serviceRequires)) {
                    findings.add(new Finding(FindingKind.CODE_LIST_DISAGREES_ON_VENDOR,
                            "The code list routes " + code + " to a vendor and the service names none",
                            "One of them is wrong, and this tool cannot tell which.", code,
                            "The code list routes this code to " + listVendor + line + ".",
                            "The service named no delegated vendor."));
                }
            } else if (!DelegatedVendors.same(listVendor, view.delegatedVendor())) {
                findings.add(new Finding(FindingKind.CODE_LIST_DISAGREES_ON_VENDOR,
                        "The code list and the service route " + code + " to different vendors",
                        "One of them is wrong, and this tool cannot tell which.", code,
                        "The code list routes this code to " + listVendor + line + ".",
                        "The service routes it to " + view.delegatedVendor() + "."));
            }
        }
    }

    /** Whether the service's answer amounts to "authorization is needed", or null when it takes no position. */
    private static Boolean serviceRequiresAuth(CodedValue paNeeded) {
        if (paNeeded.code() == null) {
            return null;
        }
        return switch (paNeeded.code()) {
            // Satisfied means an authorization was needed and one exists, so it agrees with "required".
            case "auth-needed", "performpa", "satisfied" -> Boolean.TRUE;
            case "no-auth" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static void outsideValueSet(List<Finding> findings, String where, String element, CodedValue value) {
        if (value.code() != null && !value.recognised()) {
            findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET,
                    "Non-conformant code in " + element,
                    "The value \"" + value.code() + "\" is not in the value set the implementation guide defines for " + element
                            + ", which is: " + String.join(", ", CrdCodes.codes(element)) + ".", where, null,
                    "The service returned " + element + "=" + value.code() + "."));
        }
    }

    private static void emptyElement(List<Finding> findings, String where, String element, CodedValue value) {
        if (value.presence() == Presence.NULL || value.presence() == Presence.EMPTY) {
            findings.add(new Finding(FindingKind.REQUIRED_ELEMENT_MISSING,
                    element + " was sent with no value",
                    "The sub-extension " + element + " is present but " + (value.presence() == Presence.NULL ? "its value is null" : "it is empty")
                            + ". It is not treated as an answer.", where, null, null));
        }
    }
}
