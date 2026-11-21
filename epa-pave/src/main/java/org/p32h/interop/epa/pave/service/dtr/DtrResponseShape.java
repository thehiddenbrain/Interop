package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a QuestionnaireResponse this tool builds or adds to states about itself beyond its answers:
 * what it is intended for, and in which form it names its coverage.
 *
 * <p>DTR 2.0.1 requires both of a response. The intended use is a fact about the case, so it is
 * never assumed: it comes from what the request states, through the variable
 * {@link #VAR_INTENDED_USE}, and otherwise from what CRD said the documentation is for. With
 * neither, it is left out and the result says so. The form of the coverage is a constant of the
 * guide, so it is sent as DTR 2.0.1 has it unless the request asks for the later form by name.
 *
 * @param intendedUses      one CodeableConcept for each intended use to state. Empty when nothing stated one
 * @param intendedUseSource where they came from, as the end of a sentence, or null when there are none
 * @param laterCoverageForm true when the request asked for the coverage in the qr-coverage
 *                          extension of the DTR 2.2.0 ballot rather than in qr-context
 * @param notes             one plain sentence for each value that was read and not used as it stood
 */
public record DtrResponseShape(List<ObjectNode> intendedUses, String intendedUseSource, boolean laterCoverageForm, List<String> notes) {

    /**
     * The variable that states the intended use of the response: one code or several separated by
     * commas, each a code of the CRD 2.0.1 value set DocReason or a {@code system|code} pair.
     */
    public static final String VAR_INTENDED_USE = "dtrIntendedUse";
    /**
     * The variable that asks for the form the coverage is named in: {@link #FORM_201}, the default,
     * or {@link #FORM_LATER}.
     */
    public static final String VAR_COVERAGE_EXTENSION = "dtrCoverageExtension";
    public static final String FORM_201 = "qr-context";
    public static final String FORM_LATER = "qr-coverage";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    public DtrResponseShape {
        intendedUses = intendedUses == null ? List.of() : List.copyOf(intendedUses);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** States no intended use and names the coverage as DTR 2.0.1 does. */
    public static DtrResponseShape none() {
        return new DtrResponseShape(List.of(), null, false, List.of());
    }

    /**
     * @param variables resolves a placeholder against the run values and the active environment. May be null
     * @param carry     what the step before handed on
     */
    public static DtrResponseShape read(Function<String, ResolvedText> variables, Map<String, List<String>> carry) {
        List<String> notes = new ArrayList<>();
        List<ObjectNode> uses = new ArrayList<>();
        String source = null;
        String stated = CaseResources.value(variables, VAR_INTENDED_USE);
        // A value that holds no code, such as a lone comma, states nothing, so what CRD said is read instead.
        if (stated != null && java.util.Arrays.stream(stated.split(",")).allMatch(part -> part.isBlank())) {
            notes.add("The variable " + VAR_INTENDED_USE + " is set but holds no code, so it was read as not set.");
            stated = null;
        }
        if (stated != null) {
            for (String part : stated.split(",")) {
                ObjectNode concept = stated(part.strip(), notes);
                if (concept != null) {
                    uses.add(concept);
                }
            }
            source = uses.isEmpty() ? null : "as the variable " + VAR_INTENDED_USE + " states";
        } else {
            for (String code : DtrHandoff.values(carry, NextStepView.CARRY_DOC_PURPOSE)) {
                String system = DtrCodes.DOC_REASON_201.get(code);
                if (system == null) {
                    // CRD's own answer, not the tester's: a code DTR 2.0.1 cannot bind is not passed on as an intended use.
                    notes.add("CRD said the documentation is for " + code + ", which is not a code of the CRD 2.0.1 value set DocReason that "
                            + "DTR 2.0.1 binds the intended use to (" + String.join(", ", DtrCodes.DOC_REASON_201.keySet())
                            + "), so it was not stated as an intended use.");
                } else {
                    uses.add(concept(system, code));
                }
            }
            source = uses.isEmpty() ? null : "as CRD said the documentation is for (doc-purpose)";
        }
        String form = CaseResources.value(variables, VAR_COVERAGE_EXTENSION);
        boolean later = form != null && FORM_LATER.equals(form.toLowerCase(Locale.ROOT));
        if (form != null && !later && !FORM_201.equals(form.toLowerCase(Locale.ROOT))) {
            notes.add("The variable " + VAR_COVERAGE_EXTENSION + " holds a value that names no form this tool knows: " + FORM_201
                    + " (DTR 2.0.1) or " + FORM_LATER + " (DTR 2.2.0-ballot). The coverage is named in " + FORM_201 + ", as DTR 2.0.1 has it.");
        }
        return new DtrResponseShape(uses, source, later, notes);
    }

    /** True when the response is to state at least one intended use. */
    public boolean statesIntendedUse() {
        return !intendedUses.isEmpty();
    }

    /** The codes of the intended uses, for a note. */
    public List<String> codes() {
        return intendedUses.stream().map(use -> Texts.orElse(JsonSafe.presentText(use.at("/coding/0"), "code"), "")).toList();
    }

    private static ObjectNode stated(String part, List<String> notes) {
        if (part.isEmpty()) {
            return null;
        }
        int bar = part.indexOf('|');
        if (bar > 0 && bar < part.length() - 1) {
            // The tester named the system, so the pair is sent as written, valid or not.
            String system = part.substring(0, bar).strip();
            String code = part.substring(bar + 1).strip();
            boolean bound = system.equals(DtrCodes.DOC_REASON_201.get(code));
            String later = DtrCodes.DOC_REASON_LATER_SYSTEM.equals(system) && DtrCodes.DOC_REASON_201.containsKey(code)
                    ? " The CRD 2.2.0 ballot puts " + code + " in that code system; CRD 2.0.1 puts it in " + DtrCodes.DOC_REASON_201.get(code) + "."
                    : "";
            notes.add("The variable " + VAR_INTENDED_USE + " names the code system " + system + " for the intended use " + code
                    + ", so the pair was sent as written. " + (bound ? "It is a code of the CRD 2.0.1 value set DocReason."
                    : "It is not a code of the CRD 2.0.1 value set DocReason that DTR 2.0.1 binds the intended use to ("
                    + String.join(", ", DtrCodes.DOC_REASON_201.keySet()) + ", each in its own code system)." + later));
            return concept(system, code);
        }
        String system = DtrCodes.DOC_REASON_201.get(part);
        if (system != null) {
            return concept(system, part);
        }
        notes.add("The variable " + VAR_INTENDED_USE + " states the intended use " + part + ", which is not a code of "
                + "the CRD 2.0.1 value set DocReason that DTR 2.0.1 binds the intended use to (" + String.join(", ",
                DtrCodes.DOC_REASON_201.keySet()) + "). It was sent as stated, with no code system.");
        return concept(null, part);
    }

    private static ObjectNode concept(String system, String code) {
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        if (system != null) {
            coding.put("system", system);
        }
        coding.put("code", code);
        return concept;
    }
}
