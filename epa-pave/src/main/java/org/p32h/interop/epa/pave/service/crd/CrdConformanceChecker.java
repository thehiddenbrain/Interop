package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.DelegatedVendors;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
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
 *
 * <p>When the two could not be compared for a code, that is a finding too, with the reason. A
 * result with no disagreement must mean the two agree, never that nothing was looked at.
 */
public final class CrdConformanceChecker {

    private final ProcedureCodeDirectory codeList;

    /** @param codeList the procedure code list. May be null, in which case the two code list checks are skipped */
    public CrdConformanceChecker(ProcedureCodeDirectory codeList) {
        this.codeList = codeList;
    }

    /**
     * @param lineOfBusiness the member's line of business, or null when it is not known
     * @param dateOfService  the date of service, or null
     */
    public List<Finding> check(CrdReading reading, VendorExchange exchange, String lineOfBusiness, LocalDate dateOfService) {
        List<Finding> findings = new ArrayList<>();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();

        for (CoverageInformationView view : coverage) {
            String where = view.subject();
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

        // The code list is compared with a response that answered something. A fault, or a response
        // with no coverage information, already has its own finding and outcome for the whole call.
        if (codeList != null && exchange.ok() && !coverage.isEmpty()) {
            for (String code : reading.requestedCodes()) {
                List<ProcedureCode> rows = rowsToCompare(findings, code, lineOfBusiness, dateOfService);
                if (rows.isEmpty()) {
                    continue;
                }
                List<CoverageInformationView> answers = coverage.stream().filter(v -> v.procedureCodes().contains(code)).toList();
                if (answers.isEmpty()) {
                    notCompared(findings, code, "The response carries no answer this tool could tie to " + code + ".");
                }
                for (CoverageInformationView view : answers) {
                    compareWithCodeList(findings, code, view, rows);
                }
            }
        }
        return findings;
    }

    /**
     * The code list rows that can speak for this code and this member. Empty, with a finding that
     * says why, when there are none.
     *
     * <p>Lines of business are never conflated. When the member's line is known, the rows are the
     * ones for that line and the ones that state no line. When it is not known, only rows that
     * state no line can be used: picking a named line's row would be a guess about the member.
     */
    private List<ProcedureCode> rowsToCompare(List<Finding> findings, String code, String lineOfBusiness, LocalDate dateOfService) {
        String onDate = dateOfService == null ? "" : " on " + dateOfService;
        if (Texts.present(lineOfBusiness)) {
            List<ProcedureCode> rows = codeList.rowsFor(code, lineOfBusiness, dateOfService);
            if (rows.isEmpty()) {
                List<String> otherLines = lines(codeList.rowsFor(code, null, dateOfService));
                notCompared(findings, code, otherLines.isEmpty()
                        ? "The code list has no row for " + code + onDate + "."
                        : "The code list has no row for " + code + " under " + lineOfBusiness.strip() + onDate + ". It lists the code "
                        + "under: " + String.join(", ", otherLines) + ". A row for another line of business is not this member's answer.");
            }
            return rows;
        }
        List<ProcedureCode> every = codeList.rowsFor(code, null, dateOfService);
        List<ProcedureCode> unnamed = every.stream().filter(row -> Texts.blank(row.lineOfBusiness())).toList();
        if (every.isEmpty()) {
            notCompared(findings, code, "The code list has no row for " + code + onDate + ".");
        } else if (unnamed.isEmpty()) {
            notCompared(findings, code, "The member's line of business is not known, and the code list has " + code + " only under "
                    + "named lines: " + String.join(", ", lines(every)) + ". Choosing one of them would be a guess about the member.");
        }
        return unnamed;
    }

    private void compareWithCodeList(List<Finding> findings, String code, CoverageInformationView view, List<ProcedureCode> rows) {
        List<String> rowLines = lines(rows);
        String line = rowLines.isEmpty() ? " (rows that state no line of business)" : " (rows for " + String.join(", ", rowLines) + ")";

        Set<AuthRequirement> stated = new LinkedHashSet<>();
        rows.forEach(row -> {
            if (row.authRequired() != AuthRequirement.UNKNOWN) {
                stated.add(row.authRequired());
            }
        });
        Boolean serviceRequires = serviceRequiresAuth(view.paNeeded());
        // Compared only when both sides take a position. A list that does not say is not a "no",
        // and neither is a service that does not say. Each of those is reported as not compared.
        if (stated.isEmpty()) {
            notCompared(findings, code, "The code list does not say whether " + code + " needs prior authorization" + line + ".");
        } else if (stated.size() > 1) {
            notCompared(findings, code, "The code list rows for " + code + " disagree with each other on prior authorization" + line
                    + ", so the list has no single position to compare.");
        } else if (serviceRequires == null) {
            notCompared(findings, code, "The service's answer for " + code + " does not say whether prior authorization is needed ("
                    + (view.paNeeded().code() == null ? "pa-needed was not returned" : "pa-needed=" + view.paNeeded().code())
                    + "), so there is nothing to compare with the code list.");
        } else {
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
        } else if (listVendors.size() > 1) {
            notCompared(findings, code, "The code list rows for " + code + " name different vendors" + line + ": "
                    + String.join(", ", listVendors) + ". The list has no single routing to compare.");
        } else if (Texts.present(view.delegatedVendor())) {
            // The list naming no vendor may mean the review is done in house, or only that the
            // list has no vendor column. Either way it has taken no position, so nothing is blamed.
            notCompared(findings, code, "The service routes " + code + " to " + view.delegatedVendor() + ". The code list names no "
                    + "vendor for the code" + line + ", so the routing could not be compared.");
        }
    }

    private static void notCompared(List<Finding> findings, String code, String reason) {
        findings.add(new Finding(FindingKind.CODE_LIST_NOT_COMPARED,
                "The code list was not compared with the service for " + code, reason, code, null, null));
    }

    /** The lines of business a set of rows names, as written in the list, without repeats. Empty when none names one. */
    private static List<String> lines(List<ProcedureCode> rows) {
        List<String> out = new ArrayList<>();
        for (ProcedureCode row : rows) {
            if (Texts.present(row.lineOfBusiness()) && !out.contains(row.lineOfBusiness().strip())) {
                out.add(row.lineOfBusiness().strip());
            }
        }
        return out;
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
