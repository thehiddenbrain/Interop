package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Texts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one DTR step hands the step after it, so a tester retypes nothing and the tool keeps no
 * session between steps.
 *
 * <p>The rule: a step hands on everything it was handed, and replaces what it establishes itself.
 * What CRD said about the coverage assertion, the order and the delegated vendor therefore still
 * reaches PAS after any number of DTR steps.
 *
 * <p>A package can hold several questionnaires, and each is answered by itself. So the completed
 * responses add up from step to step: a step keeps the completed responses it was handed for the
 * other questionnaires and adds its own once it is complete. A response it was handed for the
 * questionnaire it is itself answering is dropped, complete or not, because the tester's current
 * answers replace it. PAS is offered only when every questionnaire of the package has a completed
 * response, so a journey cannot move on with one of two questionnaires answered.
 *
 * @param followsRunId   the run a later step is linked to. Every step of answering one
 *                       questionnaire, and the submission that carries the answers, is linked to the
 *                       package fetch the questionnaire came from. A step that was handed no run
 *                       hands on its own, so a later step is still linked to something that exists
 * @param procedureCodes the procedure codes the case is about
 * @param questionnaires the canonical url of every questionnaire of the package, in the order
 *                       returned. For a step that was handed none, the one it is answering
 * @param answering      the canonical url of the questionnaire this step answers. For a response from
 *                       the adaptive loop that is the one the questionnaire inside it is derived
 *                       from. Null for a package fetch, and for a questionnaire that names none
 * @param given          what this step was handed, exactly as it arrived
 */
public record DtrHandoff(String followsRunId, List<String> procedureCodes, List<String> questionnaires, String answering,
                         Map<String, List<String>> given) {

    /**
     * The keys a DTR step passes on unchanged. They are the ones CRD writes and a DTR step has
     * nothing to add to. A key that is not one of the CARRY constants is never passed on. What CRD
     * said the documentation is for is among them, because the step that builds a response reads
     * it as the intended use, and that step comes after the package fetch and the loop. So is the
     * reference to a partly completed response CRD named for DTR to start from: no step of this
     * tool fetches it, and it stays visible on every step's result until the journey leaves DTR.
     */
    private static final List<String> PASSED_ON = List.of(NextStepView.CARRY_COVERAGE_ASSERTION_ID, NextStepView.CARRY_ORDER,
            NextStepView.CARRY_DELEGATED_VENDOR, NextStepView.CARRY_DTR_LINK_URL, NextStepView.CARRY_DTR_APP_CONTEXT,
            NextStepView.CARRY_DOC_PURPOSE, NextStepView.CARRY_QUESTIONNAIRE_RESPONSE_REF);

    public DtrHandoff {
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        questionnaires = questionnaires == null ? List.of() : List.copyOf(questionnaires);
        given = given == null ? Map.of() : given;
    }

    /**
     * The hand-off of a package fetch. It is where the questionnaires come from, so it starts
     * over: a completed response handed on from an earlier fetch is not carried past it.
     */
    public static DtrHandoff afterPackage(String runId, List<String> procedureCodes, List<String> questionnaires, Map<String, List<String>> given) {
        Map<String, List<String>> kept = new LinkedHashMap<>(given == null ? Map.of() : given);
        kept.remove(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        return new DtrHandoff(runId, procedureCodes, questionnaires, null, kept);
    }

    /**
     * The hand-off of a step that answers a questionnaire.
     *
     * @param runId     the id of this run, handed on only when the step was handed no run itself
     * @param answering the canonical url of the questionnaire being answered, or null when it names none
     */
    public static DtrHandoff afterAnswering(String runId, List<String> procedureCodes, String answering, Map<String, List<String>> given) {
        List<String> ofThePackage = values(given, NextStepView.CARRY_QUESTIONNAIRE);
        return new DtrHandoff(Texts.orElse(first(given, NextStepView.CARRY_RUN_ID), runId), procedureCodes,
                ofThePackage.isEmpty() && answering != null ? List.of(answering) : ofThePackage, answering, given);
    }

    /**
     * What the next step starts from.
     *
     * @param completedResponse the QuestionnaireResponse this step completed, as compact JSON text,
     *                          or null while it is not complete. An unfinished response is never
     *                          handed on under this key, because the step that reads it is the
     *                          prior authorization request
     */
    public Map<String, List<String>> carry(String completedResponse) {
        Map<String, List<String>> carry = new LinkedHashMap<>();
        for (String key : PASSED_ON) {
            carry.put(key, values(given, key));
        }
        carry.put(NextStepView.CARRY_PROCEDURE_CODES, procedureCodes);
        carry.put(NextStepView.CARRY_QUESTIONNAIRE, questionnaires);
        carry.put(NextStepView.CARRY_RUN_ID, Texts.present(followsRunId) ? List.of(followsRunId) : List.of());
        carry.put(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, completedResponses(completedResponse));
        return carry;
    }

    /**
     * Every completed response there is once this step is done: the ones handed on for the other
     * questionnaires, then this step's own when it is complete.
     */
    public List<String> completedResponses(String completedResponse) {
        List<String> out = new ArrayList<>();
        // Not through values(): a response is kept exactly as it was handed on, not stripped or compared as text.
        List<String> handedOn = given.get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        if (handedOn != null) {
            for (String response : handedOn) {
                if (Texts.present(response) && answeredBy(response).stream().noneMatch(canonical -> same(answering, canonical))) {
                    out.add(response);
                }
            }
        }
        if (Texts.present(completedResponse)) {
            out.add(completedResponse);
        }
        return out;
    }

    /**
     * The questionnaires of the package that still have no completed response once this step is
     * done. Empty when every one has, which is when the journey can move on.
     */
    public List<String> stillToAnswer(String completedResponse) {
        List<List<String>> answered = new ArrayList<>();
        for (String response : completedResponses(completedResponse)) {
            answered.add(answeredBy(response));
        }
        List<String> remaining = new ArrayList<>();
        for (String wanted : questionnaires) {
            // One response answers one questionnaire. A response that names no version cannot stand for two versions of one url.
            List<String> by = answered.stream().filter(names -> names(names, wanted)).findFirst().orElse(null);
            if (by == null) {
                remaining.add(wanted);
            } else {
                answered.remove(by);
            }
        }
        return remaining;
    }

    /**
     * True when a response names one of the questionnaires of the package, so that it is counted
     * towards them. A complete response that names none of them, or none at all, is still handed
     * on, and the result says that this tool could not tell which questionnaire it answers.
     */
    public boolean counts(String response) {
        List<String> names = answeredBy(response);
        return questionnaires.stream().anyMatch(wanted -> names(names, wanted));
    }

    /**
     * Every canonical url that names the questionnaire a QuestionnaireResponse answers, as the
     * vendor conventions read it. Empty when it names none, or is not a QuestionnaireResponse at all.
     */
    static List<String> answeredBy(String responseJson) {
        JsonSafe.Parsed parsed = JsonSafe.parse(responseJson);
        return parsed.ok() ? OnyxDtrConventions.completes(parsed.node()) : List.of();
    }

    private static boolean names(List<String> canonicals, String wanted) {
        return canonicals.stream().anyMatch(canonical -> same(wanted, canonical));
    }

    /**
     * True when two canonical urls name one questionnaire: they are equal, or one of them states
     * no version and the urls are equal. Two different versions are two questionnaires. Null names
     * nothing, so it matches nothing.
     */
    static boolean same(String one, String other) {
        if (Texts.blank(one) || Texts.blank(other)) {
            return false;
        }
        if (one.equals(other)) {
            return true;
        }
        boolean oneVersioned = one.contains("|");
        boolean otherVersioned = other.contains("|");
        return oneVersioned != otherVersioned && url(one).equals(url(other));
    }

    private static String url(String canonical) {
        int bar = canonical.indexOf('|');
        return bar < 0 ? canonical : canonical.substring(0, bar);
    }

    /** The values handed on under a key, without blanks and without repeats. Empty when there are none. */
    public static List<String> values(Map<String, List<String>> carry, String key) {
        List<String> out = new ArrayList<>();
        List<String> values = carry == null ? null : carry.get(key);
        if (values != null) {
            for (String value : values) {
                if (Texts.present(value) && !out.contains(value.strip())) {
                    out.add(value.strip());
                }
            }
        }
        return out;
    }

    /** The first value handed on under a key, or null. */
    public static String first(Map<String, List<String>> carry, String key) {
        List<String> values = values(carry, key);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Prints the names of what is handed on and never the values: a completed response holds the tester's answers. */
    @Override
    public String toString() {
        return "DtrHandoff[given=" + given.keySet() + "]";
    }
}
