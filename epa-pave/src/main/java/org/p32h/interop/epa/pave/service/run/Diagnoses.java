package org.p32h.interop.epa.pave.service.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.Texts;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The diagnosis codes of a case as each step sends them: the coverage check and the questionnaire
 * request as the order's reasonCode, Submit as the claim's diagnosis (D-321).
 *
 * <p>One rule gives the coding system for all three, so a case is coded the same way at every
 * step: the system the tester typed with the code; else the one the reasonCode of the collection
 * request the call follows uses, where it states one (D-310); else ICD-10-CM when the code has that
 * format, which is a rule about a published code format and is said in a note; else none, which is
 * said too.
 *
 * <p>A diagnosis beside a member is the member's own data. A sentence made here names a diagnosis by
 * its position in the case and never by its code.
 */
public final class Diagnoses {

    /** Where the coding system of one diagnosis came from. */
    public enum Source {
        /** Typed with the code. */
        TYPED,
        /** The system the reasonCode of the collection's sample uses. */
        SAMPLE,
        /** ICD-10-CM, from the format of the code. */
        FORMAT,
        /** None could be given. */
        NONE
    }

    /**
     * The coding system of one diagnosis and where it came from.
     *
     * @param system the system, or null for {@link Source#NONE}
     */
    public record Choice(String system, Source source) {
    }

    public static final String ICD_10_CM = OnyxPasConventions.ICD_10_CM;

    /** The longest code the format is looked at for: an ICD-10-CM code has at most eight characters with its dot. */
    private static final int LONGEST_ICD = 8;

    // Fixed counts only, and the text is cut to eight characters before it is matched, so the match cannot grow with the input.
    private static final Pattern ICD_10_CM_FORMAT = Pattern.compile("[A-TV-Z][0-9][0-9AB](\\.?[0-9A-TV-Z]{1,4})?");

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private Diagnoses() {
    }

    /** True when a code has the format of an ICD-10-CM code, whatever its case. */
    public static boolean icd10Format(String code) {
        if (Texts.blank(code)) {
            return false;
        }
        String value = code.strip();
        return value.length() <= LONGEST_ICD && ICD_10_CM_FORMAT.matcher(value.toUpperCase(Locale.ROOT)).matches();
    }

    /**
     * The coding system of one diagnosis.
     *
     * @param sampleSystem the system the reasonCode of the collection request the call follows uses, or null when the call
     *                     follows none or that request states none
     */
    public static Choice choose(CodeInput code, String sampleSystem) {
        if (Texts.present(code.system())) {
            return new Choice(code.system().strip(), Source.TYPED);
        }
        if (Texts.present(sampleSystem)) {
            return new Choice(sampleSystem.strip(), Source.SAMPLE);
        }
        if (icd10Format(code.code())) {
            return new Choice(ICD_10_CM, Source.FORMAT);
        }
        return new Choice(null, Source.NONE);
    }

    /** One diagnosis as a CodeableConcept with one coding: the system chosen, the code as typed, and its description when one was given. */
    public static ObjectNode concept(CodeInput code, Choice choice) {
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        if (choice.system() != null) {
            coding.put("system", choice.system());
        }
        coding.put("code", code.code().strip());
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        return concept;
    }

    /** The case's diagnoses as the reasonCode of an order, one concept each, in the case's order. */
    public static ArrayNode reasonCodes(List<CodeInput> codes, String sampleSystem) {
        ArrayNode out = JSON.arrayNode();
        for (CodeInput code : codes) {
            out.add(concept(code, choose(code, sampleSystem)));
        }
        return out;
    }

    /**
     * What a request says of the diagnoses it put on its orders: how many, and the coding system of
     * each by its position. Null when the case states none.
     */
    public static String orderNote(List<CodeInput> codes, String sampleSystem) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        // A count and the systems, never the codes: a diagnosis beside a member is the member's own data.
        Map<Choice, List<Integer>> by = new LinkedHashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            Choice choice = choose(codes.get(i), sampleSystem);
            Choice key = choice.source() == Source.TYPED ? new Choice(null, Source.TYPED) : choice;
            by.computeIfAbsent(key, any -> new ArrayList<>()).add(i + 1);
        }
        boolean one = codes.size() == 1;
        String start = "Each order carries " + (one ? "the case's diagnosis code" : "the case's " + codes.size() + " diagnosis codes")
                + " as reasonCode";
        if (by.size() == 1) {
            return start + ", " + clause(by.keySet().iterator().next(), one) + ".";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Choice, List<Integer>> group : by.entrySet()) {
            List<Integer> at = group.getValue();
            parts.add("diagnosis " + positions(at) + " " + clause(group.getKey(), at.size() == 1));
        }
        String joined = String.join("; ", parts);
        return start + ". " + Character.toUpperCase(joined.charAt(0)) + joined.substring(1) + ".";
    }

    /** The words for one source of a system, after the code or codes it is for. */
    static String clause(Choice choice, boolean one) {
        return switch (choice.source()) {
            case TYPED -> "with the coding system typed with " + (one ? "it" : "each");
            case SAMPLE -> "with the system " + choice.system() + ", which the reasonCode of the collection's request uses";
            case FORMAT -> "with the system ICD-10-CM (" + ICD_10_CM + "), taken from the format of the code";
            case NONE -> "with no coding system: none was typed with " + (one ? "it" : "them") + ", and the format is not that of "
                    + "ICD-10-CM. Type the system with the code to send one";
        };
    }

    /** "1", "1 and 3", "1, 2 and 4". */
    static String positions(List<Integer> at) {
        List<String> words = at.stream().map(String::valueOf).toList();
        return words.size() < 2 ? String.join("", words)
                : String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }
}
