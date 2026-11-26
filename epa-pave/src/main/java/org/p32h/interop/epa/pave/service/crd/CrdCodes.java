package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The coverage information value sets and sub-extensions this tool knows, held per version of Da
 * Vinci CRD, with a plain language meaning for each code.
 *
 * <p>The tool judges a response by CRD 2.0.1, the version the operator named (D-136). The codes and
 * element names below were read from the 2.0.1 package (the value sets AdditionalDocumentation,
 * DocReason, informationNeeded, coverageInfo and coveragePaDetail, and the snapshot of
 * ext-coverage-information) and from the 2.2.0 ballot package for what a later version changes.
 * Every binding is required and every code is case sensitive: {@code OTH} is a code and
 * {@code oth} is not.
 *
 * <p>A code is in one of four positions, and each is shown and worded differently:
 * <ul>
 *   <li>in the CRD 2.0.1 value set: the answer, with its meaning</li>
 *   <li>defined only in a later version this tool can read: read with that version's meaning and
 *       named with the version, never called wrong, since which version the service follows is
 *       not known</li>
 *   <li>{@code no-doc} for doc-needed: in no value set of either package, yet named by the 2.0.1
 *       package's own rule crd-ci-q1. It is read as "no documentation is needed" and reported as
 *       outside the 2.0.1 value set</li>
 *   <li>anything else: "Unrecognised code: X", never mapped to the nearest known code</li>
 * </ul>
 */
public final class CrdCodes {

    /** The version every conformance statement is made against. */
    public static final String JUDGED_BY = "CRD 2.0.1";
    /** The later version this tool can read, named wherever its content is recognised. */
    public static final String LATER = "CRD 2.2.0 ballot";

    /** The url of the coverage information extension, the same in both versions. */
    public static final String COVERAGE_INFORMATION =
            "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    public static final String COVERAGE = "coverage";
    public static final String COVERED = "covered";
    public static final String PA_NEEDED = "pa-needed";
    public static final String DOC_NEEDED = "doc-needed";
    public static final String DOC_PURPOSE = "doc-purpose";
    public static final String INFO_NEEDED = "info-needed";
    public static final String SATISFIED_PA_ID = "satisfied-pa-id";
    public static final String DETAIL = "detail";
    public static final String DATE = "date";
    public static final String COVERAGE_ASSERTION_ID = "coverage-assertion-id";
    public static final String QUESTIONNAIRE = "questionnaire";
    public static final String RESPONSE = "response";
    public static final String REASON = "reason";
    /** Not a sub-extension. The name under which a recognised delegated vendor is offered to expectations. */
    public static final String DELEGATED_VENDOR = "delegated-vendor";

    /** The code CRD 2.0.1 does not define for doc-needed and still names in crd-ci-q1. */
    public static final String NO_DOC = "no-doc";

    /** The element names a declared expectation may assert on for CRD, in the spelling the response uses. */
    public static final List<String> EXPECTATION_ELEMENTS =
            List.of(COVERED, PA_NEEDED, DOC_NEEDED, DOC_PURPOSE, INFO_NEEDED, SATISFIED_PA_ID, DELEGATED_VENDOR);

    /** doc-needed codes that ask for documentation, in either version. {@code patient} is the 2.2.0 ballot's. */
    public static final Set<String> DOCUMENTATION_WANTED = Set.of("clinical", "admin", "both", "patient");

    /**
     * The sub-extensions of ext-coverage-information in CRD 2.0.1, from its snapshot. {@code response}
     * is here and not in the 2.2.0 ballot; {@code detail.qualification} is the third part of a detail.
     */
    public static final Set<String> ELEMENTS_2_0_1 = Set.of(COVERAGE, COVERED, PA_NEEDED, DOC_NEEDED, DOC_PURPOSE, INFO_NEEDED,
            "billingCode", REASON, DETAIL, "dependency", QUESTIONNAIRE, RESPONSE, DATE, COVERAGE_ASSERTION_ID, SATISFIED_PA_ID,
            "contact", "detail.code", "detail.value", "detail.qualification");

    /** Sub-extensions the 2.2.0 ballot defines and CRD 2.0.1 does not. */
    public static final Set<String> ELEMENTS_LATER_ONLY = Set.of("expiry-date", "detail.category");

    private static final Map<String, Map<String, String>> VALUE_SETS = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> LATER_CODES = new LinkedHashMap<>();

    static {
        VALUE_SETS.put(COVERED, ordered(
                "covered", "Covered",
                "not-covered", "Not covered",
                "conditional", "Conditional on information not yet supplied"));
        VALUE_SETS.put(PA_NEEDED, ordered(
                "auth-needed", "Prior authorization is needed",
                "no-auth", "No prior authorization is needed",
                "satisfied", "Prior authorization requirements are already met",
                "performpa", "The performing provider must obtain prior authorization",
                "conditional", "Conditional on information not yet supplied"));
        VALUE_SETS.put(DOC_NEEDED, ordered(
                "clinical", "Clinical documentation is needed",
                "admin", "Administrative documentation is needed",
                "both", "Clinical and administrative documentation is needed",
                "conditional", "Conditional on information not yet supplied"));
        VALUE_SETS.put(DOC_PURPOSE, ordered(
                "withpa", "Send with the prior authorization request",
                "withclaim", "Send with the claim",
                "withorder", "Send with the order",
                "retain-doc", "Keep in the record as evidence of medical necessity",
                "OTH", "Another purpose, not coded"));
        VALUE_SETS.put(INFO_NEEDED, ordered(
                "performer", "Who will perform the service",
                "location", "Where the service will be performed",
                "timeframe", "When the service will be performed",
                "contract-window", "The service falls outside the contract window of the current coverage",
                "OTH", "Other information, not coded"));

        LATER_CODES.put(DOC_NEEDED, ordered(
                "patient", "Documentation from the patient or their representative is needed"));
        LATER_CODES.put(INFO_NEEDED, ordered(
                "detail-code", "A more detailed procedure code is needed"));
    }

    private CrdCodes() {
    }

    /** True when the element is one this tool holds a value set for. */
    public static boolean hasValueSet(String element) {
        return VALUE_SETS.containsKey(element);
    }

    /** The CRD 2.0.1 codes of an element's value set, in the order the guide lists them. Empty for an unknown element. */
    public static Set<String> codes(String element) {
        Map<String, String> set = VALUE_SETS.get(element);
        return set == null ? Set.of() : set.keySet();
    }

    /** True when the code is in the element's CRD 2.0.1 value set, compared case sensitively. */
    public static boolean inValueSet(String element, String code) {
        Map<String, String> set = VALUE_SETS.get(element);
        return set != null && code != null && set.containsKey(code);
    }

    /** The plain language meaning of a code in any version this tool reads, or null when no version defines it. */
    public static String display(String element, String code) {
        if (code == null) {
            return null;
        }
        Map<String, String> set = VALUE_SETS.get(element);
        if (set != null && set.containsKey(code)) {
            return set.get(code);
        }
        Map<String, String> later = LATER_CODES.get(element);
        if (later != null && later.containsKey(code)) {
            return later.get(code);
        }
        return DOC_NEEDED.equals(element) && NO_DOC.equals(code) ? "No documentation is needed" : null;
    }

    /** Where a sub-extension is defined, as a phrase shown beside it. */
    public static String definedIn(String element) {
        if (ELEMENTS_2_0_1.contains(element)) {
            return "defined in " + JUDGED_BY;
        }
        if (ELEMENTS_LATER_ONLY.contains(element)) {
            return "defined in " + LATER + ", not in " + JUDGED_BY;
        }
        return "defined in no version of CRD this tool can read";
    }

    /**
     * A coded element as returned, with what the tool knows about the code.
     *
     * @param raw the code as read from the response, carrying whether it was absent, null or empty
     */
    public static CodedValue coded(String element, Shown raw) {
        if (raw == null || raw.presence() == Presence.ABSENT) {
            return CodedValue.notReturned();
        }
        if (!raw.hasValue()) {
            return new CodedValue(raw.presence(), null, null, true);
        }
        String code = raw.value();
        Map<String, String> set = VALUE_SETS.get(element);
        if (set != null && set.containsKey(code)) {
            return new CodedValue(Presence.VALUE, code, set.get(code), true, JUDGED_BY);
        }
        Map<String, String> later = LATER_CODES.get(element);
        if (later != null && later.containsKey(code)) {
            return new CodedValue(Presence.VALUE, code, later.get(code) + " (a " + LATER + " code, not in " + JUDGED_BY + ")", true,
                    LATER);
        }
        if (DOC_NEEDED.equals(element) && NO_DOC.equals(code)) {
            return new CodedValue(Presence.VALUE, code, "No documentation is needed (no-doc is not a " + JUDGED_BY + " code)", true, null);
        }
        return new CodedValue(Presence.VALUE, code, "Unrecognised code: " + code, false, null);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
