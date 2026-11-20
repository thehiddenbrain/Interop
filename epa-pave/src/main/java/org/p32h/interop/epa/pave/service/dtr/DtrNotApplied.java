package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The rules a questionnaire can carry that this tool does not apply.
 *
 * <p>This tool evaluates enableWhen and enableBehavior and nothing else. The DTR 2.0.1 standard
 * questionnaire profile (dtr-std-questionnaire) marks many more SDC and FHIR extensions as
 * must-support: expressions that decide whether an item is enabled, whether it is required or what
 * its answer is, CQL libraries and launch contexts that pre-populate answers, and limits on an
 * answer. None of them is evaluated here, because that needs a FHIRPath and CQL engine and the data
 * the expressions read, which this tool does not have.
 *
 * <p>What follows from that is stated, never hidden. An item whose display, requiredness or answer
 * one of these rules decides is undetermined: whether it still needs an answer is not known, so it
 * is never reported as a required question with no answer, and a static response that holds one is
 * not called complete either. Each rule found is named to the tester once for the questionnaire,
 * with what it does and what not applying it means.
 */
final class DtrNotApplied {

    /** What a rule decides, which is what not applying it puts in doubt. */
    enum Effect {
        /** Whether the item is shown or enabled. */
        SHOWN,
        /** Whether the item is required. */
        REQUIRED,
        /** What the item's answer is. */
        VALUE,
        /** Neither: a limit on the answer, a unit, a signature, or data for the expressions. */
        OTHER
    }

    /**
     * One rule this tool does not apply.
     *
     * @param name   the name the DTR 2.0.1 profile gives its slice, which is how a tester finds it there
     * @param url    the extension url
     * @param what   what the rule does, as the end of a sentence that starts with its name
     */
    record Rule(String name, String url, Effect effect, String what) {
    }

    private static final String SDC = "http://hl7.org/fhir/uv/sdc/StructureDefinition/";
    private static final String CORE = "http://hl7.org/fhir/StructureDefinition/";
    private static final String CQF_EXPRESSION = CORE + "cqf-expression";

    /** The extensions an item can carry, in the order they are named to a tester. */
    private static final List<Rule> ITEM_RULES = List.of(
            new Rule("enableWhenExpression", SDC + "sdc-questionnaire-enableWhenExpression", Effect.SHOWN,
                    "decides by an expression whether the item is enabled"),
            new Rule("itemHidden", CORE + "questionnaire-hidden", Effect.SHOWN, "hides the item from the person answering"),
            new Rule("initialExpression", SDC + "sdc-questionnaire-initialExpression", Effect.VALUE,
                    "fills in the item's first answer by an expression"),
            new Rule("calculatedExpression", SDC + "sdc-questionnaire-calculatedExpression", Effect.VALUE,
                    "works out the item's answer by an expression"),
            new Rule("candidateExpression", SDC + "sdc-questionnaire-candidateExpression", Effect.OTHER,
                    "offers candidate answers by an expression"),
            new Rule("answerOptionsToggleExpression", SDC + "sdc-questionnaire-answerOptionsToggleExpression", Effect.OTHER,
                    "decides by an expression which answer options are offered"),
            new Rule("contextExpression", SDC + "sdc-questionnaire-contextExpression", Effect.OTHER,
                    "gives context for the item by an expression"),
            new Rule("minValue", CORE + "minValue", Effect.OTHER, "sets the lowest answer accepted"),
            new Rule("maxValue", CORE + "maxValue", Effect.OTHER, "sets the highest answer accepted"),
            new Rule("minQuantity", SDC + "sdc-questionnaire-minQuantity", Effect.OTHER, "sets the lowest quantity accepted"),
            new Rule("maxQuantity", SDC + "sdc-questionnaire-maxQuantity", Effect.OTHER, "sets the highest quantity accepted"),
            new Rule("minLength", CORE + "minLength", Effect.OTHER, "sets the shortest answer accepted"),
            new Rule("maxDecimalPlaces", CORE + "maxDecimalPlaces", Effect.OTHER, "limits the decimal places of an answer"),
            new Rule("regex", CORE + "regex", Effect.OTHER, "sets a pattern the answer must match"),
            new Rule("minOccurs", CORE + "questionnaire-minOccurs", Effect.OTHER, "sets how many answers are needed at least"),
            new Rule("maxOccurs", CORE + "questionnaire-maxOccurs", Effect.OTHER, "sets how many answers are accepted at most"),
            new Rule("mimeType", CORE + "mimeType", Effect.OTHER, "limits the kinds of file an attachment answer may be"),
            new Rule("maxSize", CORE + "maxSize", Effect.OTHER, "limits the size of an attachment answer"),
            new Rule("unitOption", CORE + "questionnaire-unitOption", Effect.OTHER, "names a unit a quantity answer may use"),
            new Rule("unitValueSet", CORE + "questionnaire-unitValueSet", Effect.OTHER, "names the units a quantity answer may use"),
            new Rule("unit", CORE + "questionnaire-unit", Effect.OTHER, "names the unit of a numeric answer"),
            new Rule("itemSignatureRequired", CORE + "questionnaire-signatureRequired", Effect.OTHER, "asks for a signature on the item"),
            new Rule("itemConstraint", CORE + "questionnaire-constraint", Effect.OTHER, "states a rule the answers must meet"),
            new Rule("referenceResource", CORE + "questionnaire-referenceResource", Effect.OTHER,
                    "names the kind of resource a reference answer must point to"),
            new Rule("lookupQuestionnaire", SDC + "sdc-questionnaire-lookupQuestionnaire", Effect.OTHER,
                    "names a questionnaire to look an answer up with"));

    /** The extension on {@code _required} that decides by an expression whether the item is required. */
    private static final Rule REQUIRED_EXPRESSION = new Rule("requiredExpression", CQF_EXPRESSION, Effect.REQUIRED,
            "decides by an expression whether the item is required");

    /** The extensions the questionnaire itself can carry. */
    private static final List<Rule> QUESTIONNAIRE_RULES = List.of(
            new Rule("cqf-library", CORE + "cqf-library", Effect.OTHER,
                    "names a CQL library whose expressions the initialExpression and similar extensions of the items call to pre-populate "
                            + "answers. This tool runs no CQL, so no answer is pre-populated from it. A question that carries no such "
                            + "extension of its own is judged on the answers given, so a required one with no answer is still reported as "
                            + "unanswered"),
            new Rule("launchContext", SDC + "sdc-questionnaire-launchContext", Effect.OTHER,
                    "names the data the expressions of the questionnaire read"),
            new Rule("variable", CORE + "variable", Effect.OTHER, "defines a value the expressions of the questionnaire read"),
            new Rule("itemPopulationContext", SDC + "sdc-questionnaire-itemPopulationContext", Effect.OTHER,
                    "names the data repeating items are pre-populated from"),
            new Rule("signatureRequired", CORE + "questionnaire-signatureRequired", Effect.OTHER,
                    "asks for a signature on the questionnaire"),
            new Rule("constraint", CORE + "questionnaire-constraint", Effect.OTHER, "states a rule the answers must meet"));

    private DtrNotApplied() {
    }

    /**
     * The rules on one item that decide whether it is shown, whether it is required or what its
     * answer is. Empty when none does.
     */
    static List<Rule> governing(JsonNode item) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : on(item)) {
            if (rule.effect() != Effect.OTHER) {
                out.add(rule);
            }
        }
        return out;
    }

    /** Every rule this tool does not apply that one item carries, in the order of {@link #ITEM_RULES}. */
    static List<Rule> on(JsonNode item) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : ITEM_RULES) {
            for (JsonNode extension : JsonSafe.elements(item, "extension")) {
                if (rule.url().equals(JsonSafe.presentText(extension, "url")) && counts(rule, extension) && !out.contains(rule)) {
                    out.add(rule);
                }
            }
        }
        for (JsonNode extension : JsonSafe.elements(JsonSafe.child(item, "_required"), "extension")) {
            if (CQF_EXPRESSION.equals(JsonSafe.presentText(extension, "url")) && !out.contains(REQUIRED_EXPRESSION)) {
                out.add(REQUIRED_EXPRESSION);
            }
        }
        return out;
    }

    /** A hidden extension that says false hides nothing. */
    private static boolean counts(Rule rule, JsonNode extension) {
        return !"itemHidden".equals(rule.name()) || !Boolean.FALSE.equals(JsonSafe.bool(extension, "valueBoolean"));
    }

    /**
     * The rule that decides whether an item is shown when its enableWhen reads a question whose
     * answer one of {@code filling} fills in, and that question has no answer. Whether the condition
     * holds rests on an answer this tool does not work out, so the item is undetermined.
     *
     * @param filling the rules on the question read that decide its answer
     * @param linkId  the linkId of the question read
     */
    static Rule through(List<Rule> filling, String linkId) {
        return new Rule(names(filling) + " on " + linkId, filling.get(0).url(), Effect.SHOWN,
                "fills in the answer of " + linkId + ", which the item's enableWhen reads");
    }

    /** True when one of the rules hides the item from the person answering. */
    static boolean hides(List<Rule> rules) {
        return rules.stream().anyMatch(rule -> "itemHidden".equals(rule.name()));
    }

    /** The names of rules, for a sentence. */
    static String names(List<Rule> rules) {
        return String.join(", ", rules.stream().map(Rule::name).toList());
    }

    /**
     * One plain sentence for each rule a questionnaire carries that this tool does not apply,
     * saying what it does, on how many items, and what not applying it means here. Empty when it
     * carries none.
     */
    static List<String> describe(JsonNode questionnaire) {
        List<String> out = new ArrayList<>();
        if (questionnaire == null) {
            return out;
        }
        for (Rule rule : QUESTIONNAIRE_RULES) {
            boolean found = JsonSafe.elements(questionnaire, "extension").stream()
                    .anyMatch(extension -> rule.url().equals(JsonSafe.presentText(extension, "url")));
            if (found) {
                out.add("The questionnaire carries " + rule.name() + ", which " + rule.what() + ". This tool does not apply it.");
            }
        }
        Map<Rule, Integer> counted = new LinkedHashMap<>();
        for (QuestionnaireJson.Item item : QuestionnaireJson.flatten(QuestionnaireJson.items(questionnaire))) {
            on(item.node()).forEach(rule -> counted.merge(rule, 1, Integer::sum));
        }
        for (Rule rule : ITEM_RULES) {
            add(out, rule, counted.get(rule));
        }
        add(out, REQUIRED_EXPRESSION, counted.get(REQUIRED_EXPRESSION));
        return out;
    }

    private static void add(List<String> out, Rule rule, Integer items) {
        if (items == null) {
            return;
        }
        String where = items == 1 ? "1 item carries " : items + " items carry ";
        String consequence = rule.effect() == Effect.OTHER ? " This tool does not apply it, so an answer is not checked against it."
                : " This tool does not apply it, so whether " + (items == 1 ? "that item still needs" : "those items still need")
                + " an answer is not known: " + (items == 1 ? "it is" : "they are") + " marked undetermined, and never reported "
                + "as a required question with no answer." + (rule.effect() == Effect.VALUE
                ? " An item whose enableWhen reads " + (items == 1 ? "its" : "their") + " answer, while there is none, is marked "
                + "undetermined too." : "");
        out.add(where + rule.name() + ", which " + rule.what() + "." + consequence);
    }
}
