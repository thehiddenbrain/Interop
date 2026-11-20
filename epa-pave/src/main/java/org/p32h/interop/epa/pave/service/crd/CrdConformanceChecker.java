package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.DelegatedVendors;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.crd.ConformanceScope;
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
 *
 * <p><b>A synthetic list is never passed off as the P32H list.</b> With no export configured the
 * tool loads the synthetic list that ships with it, so that it runs on a fresh checkout, and any
 * other list whose rows carry the marker "Not from a P32H export." says of itself
 * that it is synthetic too, as the sample under samples/ does. What such a list says about a code
 * was made up, and both are treated alike.
 * <ul>
 *   <li>Anywhere but on the mock profile nothing is compared with it. A disagreement between a
 *       real service and invented rows is a finding about neither, and a tester would take it to
 *       the vendor. The result carries one finding for the run that says no P32H list is loaded.</li>
 *   <li>On the mock profile the comparison runs, because there the service's answer is synthetic
 *       as well and showing what a disagreement looks like is the point. Every finding that rests
 *       on the list then says in its detail which list that was.</li>
 * </ul>
 */
public final class CrdConformanceChecker {

    /** The rules {@link #check} applies, shown on every result. */
    public static final ConformanceScope SCOPE = CrdGuideRules.SCOPE;

    private static final String NO_P32H_LIST_TITLE = "The code list was not compared with the service: no P32H code list is loaded";
    // Both sentences hold for every list that says of itself it is synthetic: the bundled placeholder,
    // the sample under samples/ and a tester's own list whose rows carry the marker. None of them is
    // named as the one that ships with the tool.
    private static final String NO_P32H_LIST_DETAIL = "No P32H code list is loaded. The code list in use says of itself that it is "
            + "synthetic (its rows carry the note \"Not from a P32H export.\"), so it says nothing about real codes, "
            + "and nothing was compared with the service's answer. Set codelist.file to the P32H export and restart the tool.";
    private static final String PLACEHOLDER_NOTE = "The code list in use says of itself that it is synthetic, so it is not a P32H list.";

    private final ProcedureCodeDirectory codeList;
    private final boolean mockProfile;

    /**
     * @param codeList the procedure code list. May be null, in which case the two code list checks are skipped
     * @param epa      says which profile the tool runs on. The placeholder list is compared with an
     *                 answer on the mock profile only. Any other mode is treated as live, because
     *                 comparing invented rows with a real service is the mistake that costs more
     */
    public CrdConformanceChecker(ProcedureCodeDirectory codeList, EpaProperties epa) {
        this.codeList = codeList;
        this.mockProfile = epa.mock();
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
            valueSet(findings, where, CrdCodes.COVERED, view.covered());
            valueSet(findings, where, CrdCodes.PA_NEEDED, view.paNeeded());
            valueSet(findings, where, CrdCodes.DOC_NEEDED, view.docNeeded());
            view.docPurpose().forEach(value -> valueSet(findings, where, CrdCodes.DOC_PURPOSE, value));
            view.infoNeeded().forEach(value -> valueSet(findings, where, CrdCodes.INFO_NEEDED, value));
            CrdGuideRules.check(findings, view);
        }

        if (exchange.ok() && coverage.isEmpty()) {
            // CRD 2.0.1 makes the extension optional on every order profile and ships no response
            // model, so its absence is information. The outcome stays "no determination returned".
            findings.add(new Finding(FindingKind.NOT_STATED,
                    "No coverage information in the response",
                    "The service answered successfully but the response carries no coverage information extension, in a system "
                            + "action, on a card or on a suggestion. " + CrdCodes.JUDGED_BY + " does not require one, so this is not a "
                            + "conformance problem, but the response has said nothing about coverage or prior authorization.",
                    null, null, null));
        }

        // The code list is compared with a response that answered something. A fault, or a response
        // with no coverage information, already has its own finding and outcome for the whole call.
        if (codeList != null && exchange.ok() && !coverage.isEmpty()) {
            // Asked once for the whole check. Which list is loaded cannot differ from one code to the next.
            boolean placeholder = codeList.selfCheck().placeholder();
            if (placeholder && !mockProfile) {
                // One finding for the run and not one for each code: the reason is the same for all of them.
                findings.add(new Finding(FindingKind.CODE_LIST_NOT_COMPARED, NO_P32H_LIST_TITLE, NO_P32H_LIST_DETAIL, null, null, null));
            } else {
                List<Finding> fromTheList = new ArrayList<>();
                for (String code : reading.requestedCodes()) {
                    List<ProcedureCode> rows = rowsToCompare(fromTheList, code, lineOfBusiness, dateOfService);
                    if (rows.isEmpty()) {
                        continue;
                    }
                    List<CoverageInformationView> answers = coverage.stream().filter(v -> v.procedureCodes().contains(code)).toList();
                    if (answers.isEmpty()) {
                        notCompared(fromTheList, code, "The response carries no answer this tool could tie to " + code + ".");
                    }
                    for (CoverageInformationView view : answers) {
                        compareWithCodeList(fromTheList, code, view, rows);
                    }
                }
                // The note is added here, to whatever the comparison raised, and not where each
                // finding is made: a finding added to the comparison later cannot then go without it.
                findings.addAll(placeholder ? fromTheList.stream().map(CrdConformanceChecker::fromThePlaceholder).toList() : fromTheList);
            }
        }
        return findings;
    }

    /** The same finding, saying in its detail that the list it rests on is the placeholder. */
    private static Finding fromThePlaceholder(Finding finding) {
        return new Finding(finding.kind(), finding.title(), finding.detail() + " " + PLACEHOLDER_NOTE, finding.subject(),
                finding.codeListSays(), finding.serviceSays());
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
            // Null for a blank cell, and also for one with no letter or digit in it, such as a dash
            // written for "none". Neither names a vendor, so neither is a routing to compare.
            String vendor = DelegatedVendors.canonical(row.delegatedVendor());
            if (vendor != null) {
                listVendors.add(vendor);
            }
        });
        if (listVendors.size() == 1) {
            String listVendor = listVendors.iterator().next();
            if (Texts.blank(view.delegatedVendor())) {
                // Only an answer that leaves a submission to make has a reason to name a vendor.
                // Satisfied means prior authorization can be bypassed, and a not-covered answer has
                // nothing to submit, so neither is blamed for naming none.
                if (submissionToRoute(view)) {
                    findings.add(new Finding(FindingKind.CODE_LIST_DISAGREES_ON_VENDOR,
                            "The code list routes " + code + " to a vendor and the service names none",
                            "One of them is wrong, and this tool cannot tell which.", code,
                            "The code list routes this code to " + listVendor + line + ".",
                            "The service named no delegated vendor."));
                }
            } else if (submissionToRoute(view) && !DelegatedVendors.same(listVendor, view.delegatedVendor())) {
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
            // CRD 2.0.1 defines satisfied as: prior authorization would typically be needed, and the
            // conditions it evaluates have already been evaluated. The code list's "required" is a
            // statement about the code, so satisfied agrees with it.
            case "auth-needed", "performpa", "satisfied" -> Boolean.TRUE;
            case "no-auth" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** True when the answer leaves a prior authorization request to submit, the only case where routing matters. */
    private static boolean submissionToRoute(CoverageInformationView view) {
        return !view.covered().is("not-covered") && (view.paNeeded().is("auth-needed") || view.paNeeded().is("performpa"));
    }

    /**
     * A code judged by the CRD 2.0.1 value set. A code of a later version is named as such and not
     * called wrong; no-doc is read and reported with what CRD 2.0.1 says instead; anything else is
     * outside every value set this tool can read, and the 2.0.1 list is quoted as the 2.0.1 list.
     */
    private static void valueSet(List<Finding> findings, String where, String element, CodedValue value) {
        if (value.code() == null || CrdCodes.JUDGED_BY.equals(value.version())) {
            return;
        }
        String list = String.join(", ", CrdCodes.codes(element));
        String serviceSays = "The service returned " + element + "=" + value.code() + ".";
        if (CrdCodes.LATER.equals(value.version())) {
            findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT,
                    element + "=" + value.code() + " is a " + CrdCodes.LATER + " code, not a " + CrdCodes.JUDGED_BY + " code",
                    "This tool judges by " + CrdCodes.JUDGED_BY + ", whose value set for " + element + " is: " + list + ". The code "
                            + value.code() + " is defined in a later version, " + CrdCodes.LATER + ", where it means: "
                            + lower(CrdCodes.display(element, value.code())) + ". It is read with that meaning and not called wrong, "
                            + "because which version the service follows is not known.", where, null, serviceSays));
        } else if (CrdCodes.DOC_NEEDED.equals(element) && CrdCodes.NO_DOC.equals(value.code())) {
            findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET,
                    "doc-needed=no-doc is not in the " + CrdCodes.JUDGED_BY + " value set",
                    "The " + CrdCodes.JUDGED_BY + " value set for doc-needed (AdditionalDocumentation) is: " + list + ", and "
                            + CrdCodes.JUDGED_BY + " says that no documentation is needed by leaving doc-needed out. The 2.0.1 "
                            + "package's own rule crd-ci-q1 does mention no-doc, so a service may well send it. This tool reads it "
                            + "as no documentation needed.", where, null, serviceSays));
        } else {
            findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET,
                    element + "=" + value.code() + " is not in the " + CrdCodes.JUDGED_BY + " value set",
                    "The " + CrdCodes.JUDGED_BY + " value set for " + element + " is: " + list + ". Codes are compared exactly, "
                            + "letter case included. No version of the guide this tool can read (" + CrdCodes.JUDGED_BY + " and "
                            + CrdCodes.LATER + ") defines " + value.code() + ", so it is shown as unrecognised.", where, null,
                    serviceSays));
        }
    }

    private static String lower(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
