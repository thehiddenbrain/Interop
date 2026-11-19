package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.dtr.DtrCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The FHIR value sets a questionnaire and its response draw their codes from, with a plain
 * language meaning for each code, and the names under which a DTR result offers its facts to a
 * declared expectation.
 *
 * <p>The codes are the ones the FHIR R4 specification defines for these elements, as the builder
 * knows them. A code outside its value set is never hidden and never mapped to the nearest known
 * one: it is shown as "Unrecognised code: X" and reported as a finding.
 */
public final class DtrCodes {

    /** The value set of {@code Questionnaire.item.type}. */
    public static final String ITEM_TYPE = "item-type";
    /** The value set of {@code Questionnaire.status}. */
    public static final String QUESTIONNAIRE_STATUS = "questionnaire-status";
    /** The value set of {@code QuestionnaireResponse.status}. */
    public static final String RESPONSE_STATUS = "response-status";
    /** The value set of {@code Questionnaire.item.enableWhen.operator}. */
    public static final String OPERATOR = "enable-when-operator";
    /** The value set of {@code Questionnaire.item.enableBehavior}. */
    public static final String ENABLE_BEHAVIOR = "enable-behavior";

    /** Adaptive, static or undetermined, for one questionnaire. */
    public static final String QUESTIONNAIRE_KIND = "questionnaire-kind";
    /** How many questionnaires a package holds. */
    public static final String QUESTIONNAIRES = "questionnaires";
    /** How many questions a questionnaire holds so far. */
    public static final String QUESTIONS = "questions";
    /** How many of them have an answer. */
    public static final String ANSWERED = "answered";
    /** How many required questions that are enabled have no answer. */
    public static final String REQUIRED_UNANSWERED = "required-unanswered";

    /**
     * The element names a declared expectation may assert on for DTR. One list serves the three
     * steps: a name a step does not report simply is not carried by its result, and an assertion
     * on it fails saying so.
     */
    public static final List<String> EXPECTATION_ELEMENTS = List.of(QUESTIONNAIRE_KIND, QUESTIONNAIRE_STATUS, QUESTIONNAIRES,
            QUESTIONS, ANSWERED, REQUIRED_UNANSWERED, RESPONSE_STATUS);

    private static final Map<String, Map<String, String>> VALUE_SETS = new LinkedHashMap<>();

    static {
        VALUE_SETS.put(ITEM_TYPE, ordered(
                "group", "Group of items",
                "display", "Display text",
                "boolean", "Yes or no",
                "decimal", "Decimal number",
                "integer", "Whole number",
                "date", "Date",
                "dateTime", "Date and time",
                "time", "Time of day",
                "string", "Short text",
                "text", "Long text",
                "url", "Web address",
                "choice", "One of the options listed",
                "open-choice", "One of the options listed, or other text",
                "attachment", "Attachment",
                "reference", "Reference to a record",
                "quantity", "Quantity with a unit"));
        VALUE_SETS.put(QUESTIONNAIRE_STATUS, ordered(
                "draft", "Draft",
                "active", "Active",
                "retired", "Retired",
                "unknown", "Not known"));
        VALUE_SETS.put(RESPONSE_STATUS, ordered(
                "in-progress", "In progress",
                "completed", "Completed",
                "amended", "Amended after it was completed",
                "entered-in-error", "Entered in error",
                "stopped", "Stopped before it was completed"));
        VALUE_SETS.put(OPERATOR, ordered(
                "exists", "has an answer, or has none",
                "=", "equals",
                "!=", "does not equal",
                ">", "is greater than",
                "<", "is less than",
                ">=", "is greater than or equal to",
                "<=", "is less than or equal to"));
        VALUE_SETS.put(ENABLE_BEHAVIOR, ordered(
                "all", "Every condition must hold",
                "any", "At least one condition must hold"));
    }

    private DtrCodes() {
    }

    /** The codes of a value set, in the order the specification lists them. Empty for an unknown set. */
    public static Set<String> codes(String valueSet) {
        Map<String, String> set = VALUE_SETS.get(valueSet);
        return set == null ? Set.of() : set.keySet();
    }

    public static boolean inValueSet(String valueSet, String code) {
        Map<String, String> set = VALUE_SETS.get(valueSet);
        return set != null && code != null && set.containsKey(code);
    }

    /** The plain language meaning of a code, or null when it is not in the value set. */
    public static String display(String valueSet, String code) {
        Map<String, String> set = VALUE_SETS.get(valueSet);
        return set == null || code == null ? null : set.get(code);
    }

    /**
     * A coded element as returned, with what the tool knows about the code.
     *
     * @param raw the code as read from the response, carrying whether it was absent, null or empty
     */
    public static DtrCode coded(String valueSet, Shown raw) {
        if (raw == null || raw.presence() == Presence.ABSENT) {
            return DtrCode.notReturned();
        }
        if (!raw.hasValue()) {
            return new DtrCode(raw.presence(), null, null, true);
        }
        String code = raw.value();
        String display = display(valueSet, code);
        if (display == null) {
            return new DtrCode(Presence.VALUE, code, "Unrecognised code: " + code, false);
        }
        return new DtrCode(Presence.VALUE, code, display, true);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
