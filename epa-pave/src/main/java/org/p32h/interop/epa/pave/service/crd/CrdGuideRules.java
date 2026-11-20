package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.OnyxCrdConventions;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.ConformanceScope;
import org.p32h.interop.epa.pave.model.crd.CoverageDetailView;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.SubExtensionView;
import java.util.List;

/**
 * The rules of the CRD 2.0.1 coverage information extension other than its value sets, applied to
 * one extension as read: the cardinalities of its sub-extensions, its invariants crd-ci-q1, q3 and
 * q5, and the places it may sit. Each finding names the rule and the version. A part that is
 * required and absent, or present with no value, is {@link FindingKind#REQUIRED_ELEMENT_MISSING}.
 * A broken invariant, an extension where it may not sit and a part sent more often than allowed
 * are {@link FindingKind#GUIDE_RULE_BROKEN}: nothing is missing in them.
 *
 * <p>Two invariants of 2.0.1 are not applied, and {@link #SCOPE} says so on every result:
 * <ul>
 *   <li>crd-ci-q2. Its text says pa-needed should not exist when covered is not-covered. Its
 *       expression, {@code extension.where(url = 'covered' and value != 'not-covered') implies
 *       extension.where(url = 'pa-needed').exists()}, instead requires pa-needed whenever covered is
 *       anything else. The two contradict each other, and a covered answer with no pa-needed is one
 *       this tool already reports as "not stated", so neither reading is enforced.</li>
 *   <li>crd-ci-q4. It forbids doc-purpose "PA" when pa-needed is satisfied. No value set of 2.0.1
 *       holds a code PA, so as written it can never be broken, and enforcing what it may have meant
 *       (withpa, as the 2.2.0 ballot writes it) would apply a later version's rule.</li>
 * </ul>
 * Rules that only the 2.2.0 ballot makes (q6 to q8, and its rewritten q1 to q4) are not applied: a
 * requirement only a later version makes is not raised against a service that follows 2.0.1 (D-136).
 */
final class CrdGuideRules {

    private static final String V = CrdCodes.JUDGED_BY;

    static final ConformanceScope SCOPE = new ConformanceScope(V, List.of(
            "The codes of covered, pa-needed, doc-needed, doc-purpose and info-needed are in their " + V + " value sets (required "
                    + "bindings). A code a later version defines is named with that version.",
            "coverage, covered, date and coverage-assertion-id are each present once (1..1).",
            "pa-needed, doc-needed and satisfied-pa-id are present at most once (0..1), and covered, coverage, date and "
                    + "coverage-assertion-id no more than once.",
            "A coded sub-extension that is sent carries a value.",
            "Each detail carries a code and a value (1..1 each).",
            "crd-ci-q1: a questionnaire or a response is sent only when doc-needed is present and is not no-doc.",
            "crd-ci-q3: when info-needed is sent, at least one of covered, pa-needed and doc-needed is conditional.",
            "crd-ci-q5: satisfied-pa-id is present if and only if pa-needed is satisfied.",
            "The extension sits on a resource. A CDS Hooks card is not one of the places " + V + " allows it."),
            List.of(
                    "crd-ci-q2 is not applied: its text forbids pa-needed when covered is not-covered, and its expression requires "
                            + "pa-needed whenever covered is anything else. The two contradict each other.",
                    "crd-ci-q4 is not applied: it forbids doc-purpose \"PA\" when pa-needed is satisfied, and no " + V + " value set "
                            + "holds a code PA, so as written it cannot be broken.",
                    "The reference targets of coverage, questionnaire and response, the code systems of reason, detail and "
                            + "billingCode, and what the " + V + " cards and hooks pages say: those pages are not in the "
                            + "package this tool was checked against.",
                    "Rules only the " + CrdCodes.LATER + " makes (crd-ci-q6 to q8, and its rewritten q1 to q4) are not applied to "
                            + "a service that may follow " + V + "."));

    private CrdGuideRules() {
    }

    static void check(List<Finding> findings, CoverageInformationView view) {
        String where = view.subject();
        String at = "The coverage information at " + view.foundAt();

        required(findings, where, at, CrdCodes.COVERAGE, view.coverage().presence());
        required(findings, where, at, CrdCodes.COVERED, view.covered().presence());
        required(findings, where, at, CrdCodes.DATE, view.date().presence());
        required(findings, where, at, CrdCodes.COVERAGE_ASSERTION_ID, view.coverageAssertionId().presence());

        noValue(findings, where, CrdCodes.COVERED, view.covered().presence(), view.sentWithNoValue(CrdCodes.COVERED));
        noValue(findings, where, CrdCodes.PA_NEEDED, view.paNeeded().presence(), view.sentWithNoValue(CrdCodes.PA_NEEDED));
        noValue(findings, where, CrdCodes.DOC_NEEDED, view.docNeeded().presence(), view.sentWithNoValue(CrdCodes.DOC_NEEDED));
        for (int i = 0; i < view.docPurpose().size(); i++) {
            noValue(findings, where, CrdCodes.DOC_PURPOSE, view.docPurpose().get(i).presence(),
                    view.sentWithNoValue(CrdCodes.DOC_PURPOSE + "[" + i + "]"));
        }
        for (int i = 0; i < view.infoNeeded().size(); i++) {
            noValue(findings, where, CrdCodes.INFO_NEEDED, view.infoNeeded().get(i).presence(),
                    view.sentWithNoValue(CrdCodes.INFO_NEEDED + "[" + i + "]"));
        }

        for (SubExtensionView other : view.otherSubExtensions()) {
            if (other.url().endsWith(CrdResponseMapper.REPEATED)) {
                String name = other.url().substring(0, other.url().length() - CrdResponseMapper.REPEATED.length());
                findings.add(new Finding(FindingKind.GUIDE_RULE_BROKEN,
                        V + " allows " + name + " once, and it was sent more than once",
                        at + " carries " + name + " more than once. " + V + " ext-coverage-information allows it at most once. The "
                                + "first was read as the answer, and each further one is listed with the sub-extensions as repeated.",
                        where, null, null));
            } else if (CrdCodes.ELEMENTS_LATER_ONLY.contains(other.url())) {
                findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT,
                        other.url() + " is defined in " + CrdCodes.LATER + ", not in " + V,
                        at + " carries " + other.url() + ", which " + V + " does not define and " + CrdCodes.LATER
                                + " does. It is shown as returned and not called wrong, because which version the service follows "
                                + "is not known.", where, null, null));
            }
        }

        int index = 0;
        for (CoverageDetailView detail : view.details()) {
            required(findings, where, at, "detail[" + index + "].code", detail.code().presence());
            required(findings, where, at, "detail[" + index + "].value", detail.value().presence());
            if (detail.category().presence() != Presence.ABSENT) {
                findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT,
                        "detail.category is defined in " + CrdCodes.LATER + ", not in " + V,
                        at + " gives detail[" + index + "] a category. " + V + " defines a detail as code, value and qualification; "
                                + CrdCodes.LATER + " adds category. It is shown as returned and not called wrong, because which version "
                                + "the service follows is not known.", where, null, null));
            }
            index++;
        }

        for (String reason : view.laterReasonCodes()) {
            findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT,
                    "The reason " + reason.substring(reason.indexOf('|') + 1) + " is a " + CrdCodes.LATER + " code",
                    at + " gives the reason " + reason + ". " + V + " defines only gold-card and detail-code as reasons; "
                            + CrdCodes.LATER + " defines this one, and it was read with that version's meaning: "
                            + laterReasonMeaning(reason) + ".", where, null, null));
        }

        boolean formNamed = view.questionnaires().stream().anyMatch(q -> q.presence() != Presence.ABSENT)
                || view.responses().stream().anyMatch(r -> r.presence() != Presence.ABSENT);
        if (formNamed && (view.docNeeded().code() == null || view.docNeeded().is(CrdCodes.NO_DOC))) {
            findings.add(new Finding(FindingKind.GUIDE_RULE_BROKEN,
                    V + " crd-ci-q1: a questionnaire or a response is named without doc-needed",
                    at + " names a questionnaire or a response, and doc-needed is "
                            + (view.docNeeded().code() == null ? "not stated" : "no-doc") + ". " + V + " rule crd-ci-q1 allows a "
                            + "questionnaire or a response only when doc-needed is present and is not no-doc.", where, null, null));
        }

        boolean infoAsked = view.infoNeeded().stream().anyMatch(i -> i.code() != null);
        boolean anyConditional = view.covered().is("conditional") || view.paNeeded().is("conditional")
                || view.docNeeded().is("conditional");
        if (infoAsked && !anyConditional) {
            findings.add(new Finding(FindingKind.GUIDE_RULE_BROKEN,
                    V + " crd-ci-q3: info-needed is sent and nothing is conditional",
                    at + " asks for information (info-needed), and none of covered, pa-needed and doc-needed is conditional. " + V
                            + " rule crd-ci-q3 requires at least one of them to be conditional when information is asked for.",
                    where, null, null));
        }

        boolean satisfied = view.paNeeded().is("satisfied");
        boolean identifier = view.satisfiedPaId().presence() != Presence.ABSENT;
        if (satisfied != identifier) {
            findings.add(new Finding(FindingKind.GUIDE_RULE_BROKEN,
                    satisfied ? V + " crd-ci-q5: pa-needed is satisfied and satisfied-pa-id is missing"
                            : V + " crd-ci-q5: satisfied-pa-id is sent and pa-needed is not satisfied",
                    at + (satisfied ? " says prior authorization requirements are already met and gives no satisfied-pa-id."
                            : " carries satisfied-pa-id while pa-needed is " + stated(view.paNeeded()) + ".")
                            + " " + V + " rule crd-ci-q5 requires satisfied-pa-id if and only if pa-needed is satisfied.",
                    where, null, null));
        }

        if (view.onACard()) {
            // Nothing is missing and no value set is involved: the rule broken is where the extension may sit.
            findings.add(new Finding(FindingKind.GUIDE_RULE_BROKEN,
                    "Coverage information sits on a card, which " + V + " does not allow",
                    at + " is on a CDS Hooks card. " + V + " defines ext-coverage-information for these resources only: "
                            + "QuestionnaireResponse, Encounter, NutritionOrder, CommunicationRequest, DeviceRequest, ServiceRequest and "
                            + "MedicationRequest. A card is not a FHIR resource. The answer was still read, so it can be seen.",
                    where, null, null));
        }
    }

    private static void required(List<Finding> findings, String where, String at, String element, Presence presence) {
        if (presence == Presence.ABSENT) {
            findings.add(new Finding(FindingKind.REQUIRED_ELEMENT_MISSING,
                    V + " requires " + element + " on coverage information",
                    at + " does not carry " + element + ". " + V + " ext-coverage-information requires it once (1..1).",
                    where, null, null));
        }
    }

    /**
     * @param noValueAtAll true when the sub-extension carried no value[x] at all, which the view
     *                     shows as empty, as it does a value of "": the finding says which it was
     */
    private static void noValue(List<Finding> findings, String where, String element, Presence presence, boolean noValueAtAll) {
        if (presence == Presence.NULL || presence == Presence.EMPTY) {
            String what = noValueAtAll ? "it carries no value[x] at all"
                    : presence == Presence.NULL ? "its value is null" : "its value is empty";
            findings.add(new Finding(FindingKind.REQUIRED_ELEMENT_MISSING,
                    element + " was sent with no value",
                    "The sub-extension " + element + " is present but " + what
                            + ". " + V + " requires a value on it (value[x] 1..1). It is not treated as an answer.", where, null, null));
        }
    }

    private static String stated(CodedValue value) {
        return value.code() == null ? "not stated" : value.code();
    }

    static String laterReasonMeaning(String reason) {
        if (OnyxCrdConventions.LATER_NO_MEMBER_FOUND.equals(reason)) {
            return "the service could not find a matching member, so no coverage information can be given";
        }
        if (OnyxCrdConventions.LATER_NO_ACTIVE_COVERAGE.equals(reason)) {
            return "the coverage named is not active, so no coverage information can be given";
        }
        return "authorization is needed if the service is delivered out of network";
    }
}
