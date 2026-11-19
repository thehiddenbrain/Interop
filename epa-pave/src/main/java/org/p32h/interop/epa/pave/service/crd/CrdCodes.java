package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The coverage information value sets this tool knows, with a plain language meaning for each code.
 *
 * <p>The codes are the ones the build prompt lists for Da Vinci CRD. They are what QA signs off
 * against. A code outside its value set is never hidden and never mapped to the nearest known
 * one: it is shown as "Unrecognised code: X" and reported as a conformance finding.
 */
public final class CrdCodes {

    /** The url of the coverage information extension. */
    public static final String COVERAGE_INFORMATION =
            "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    public static final String COVERED = "covered";
    public static final String PA_NEEDED = "pa-needed";
    public static final String DOC_NEEDED = "doc-needed";
    public static final String DOC_PURPOSE = "doc-purpose";
    public static final String INFO_NEEDED = "info-needed";
    public static final String SATISFIED_PA_ID = "satisfied-pa-id";
    public static final String DETAIL = "detail";
    /** Not a sub-extension. The name under which a recognised delegated vendor is offered to expectations. */
    public static final String DELEGATED_VENDOR = "delegated-vendor";

    private static final Map<String, Map<String, String>> VALUE_SETS = new LinkedHashMap<>();

    static {
        VALUE_SETS.put(COVERED, ordered(
                "covered", "Covered",
                "not-covered", "Not covered",
                "conditional", "Conditional on information not yet supplied"));
        VALUE_SETS.put(PA_NEEDED, ordered(
                "auth-needed", "Prior authorization is needed",
                "no-auth", "No prior authorization is needed",
                "satisfied", "Prior authorization is already satisfied",
                "performpa", "The performing provider must obtain prior authorization",
                "conditional", "Conditional on information not yet supplied"));
        VALUE_SETS.put(DOC_NEEDED, ordered(
                "clinical", "Clinical documentation is needed",
                "admin", "Administrative documentation is needed",
                "both", "Clinical and administrative documentation is needed",
                "no-doc", "No documentation is needed",
                "conditional", "Conditional on information not yet supplied"));
        VALUE_SETS.put(DOC_PURPOSE, ordered(
                "withclaim", "Send with the claim",
                "withpa", "Send with the prior authorization request",
                "withorder", "Send with the order",
                "retain", "Retain in the record",
                "oth", "Other"));
        VALUE_SETS.put(INFO_NEEDED, ordered(
                "performer", "Who will perform the service",
                "location", "Where the service will be performed",
                "timeframe", "When the service will be performed",
                "oth", "Other"));
    }

    private CrdCodes() {
    }

    /** True when the element is one this tool holds a value set for. */
    public static boolean hasValueSet(String element) {
        return VALUE_SETS.containsKey(element);
    }

    /** The codes of an element's value set, in the order the guide lists them. Empty for an unknown element. */
    public static java.util.Set<String> codes(String element) {
        Map<String, String> set = VALUE_SETS.get(element);
        return set == null ? java.util.Set.of() : set.keySet();
    }

    public static boolean inValueSet(String element, String code) {
        Map<String, String> set = VALUE_SETS.get(element);
        return set != null && code != null && set.containsKey(code);
    }

    /** The plain language meaning of a code, or null when it is not in the element's value set. */
    public static String display(String element, String code) {
        Map<String, String> set = VALUE_SETS.get(element);
        return set == null || code == null ? null : set.get(code);
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
        String display = display(element, code);
        if (display == null) {
            return new CodedValue(Presence.VALUE, code, "Unrecognised code: " + code, false);
        }
        return new CodedValue(Presence.VALUE, code, display, true);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
