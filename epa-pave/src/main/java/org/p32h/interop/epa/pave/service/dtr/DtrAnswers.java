package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson.Item;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads what a step of the adaptive loop or a response step was posted: the questionnaire being
 * answered, the QuestionnaireResponse so far and the answers being added. The answers are checked
 * against the questionnaire and turned into FHIR answers.
 *
 * <p>Every problem is collected as a field error, so a tester sees all of them at once and nothing
 * is sent or recorded while there is one. No message here quotes an answer: an answer can be
 * clinical, and an error message is not a place for one.
 */
final class DtrAnswers {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String DATE = "\\d{4}(-\\d{2}(-\\d{2})?)?";
    private static final String DATE_TIME = "\\d{4}(-\\d{2}(-\\d{2}(T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?)?)?)?";
    private static final String TIME = "\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?";
    private static final String AS_OBJECT = "Give the answer as a FHIR answer object with one value, such as {\"valueCoding\": "
            + "{\"system\": \"...\", \"code\": \"...\"}}.";

    private DtrAnswers() {
    }

    /**
     * What was posted, read.
     *
     * @param questionnaire the questionnaire being answered: the one the response carries inside
     *                      itself when it carries one, since that is its current state, otherwise
     *                      the one posted beside it. Null when neither could be read
     * @param response      the QuestionnaireResponse so far, or null when none was posted
     * @param given         the answers being added, by linkId, each as a FHIR answer object. An
     *                      empty list takes back an answer given earlier
     * @param errors        every problem found. Empty when what was posted can be used
     */
    record Posted(JsonNode questionnaire, JsonNode response, Map<String, List<JsonNode>> given, List<FieldError> errors) {

        /** The answers the response so far holds, with the answers being added in place of the earlier ones. */
        Map<String, List<JsonNode>> merged() {
            Map<String, List<JsonNode>> out = new LinkedHashMap<>();
            if (response != null) {
                out.putAll(QuestionnaireJson.answers(response));
            }
            given.forEach((linkId, values) -> {
                if (values.isEmpty()) {
                    out.remove(linkId);
                } else {
                    out.put(linkId, values);
                }
            });
            return out;
        }
    }

    static Posted read(String questionnaireText, String responseText, List<DtrAnswerInput> answers) {
        List<FieldError> errors = new ArrayList<>();
        JsonNode response = null;
        if (Texts.present(responseText)) {
            JsonSafe.Parsed parsed = JsonSafe.parse(responseText);
            if (!parsed.ok()) {
                errors.add(new FieldError("questionnaireResponse", "The QuestionnaireResponse given is not valid JSON. Post it back exactly "
                        + "as the previous step returned it."));
            } else {
                response = QuestionnaireJson.findResponse(parsed.node());
                if (response == null) {
                    errors.add(new FieldError("questionnaireResponse", "What was given here is not a QuestionnaireResponse. Post back the "
                            + "one the previous step returned."));
                }
            }
        }
        JsonNode posted = null;
        if (Texts.present(questionnaireText)) {
            JsonSafe.Parsed parsed = JsonSafe.parse(questionnaireText);
            if (!parsed.ok()) {
                errors.add(new FieldError("questionnaire", "The questionnaire given is not valid JSON. Post it back exactly as the package "
                        + "step returned it."));
            } else if (!QuestionnaireJson.isType(parsed.node(), "Questionnaire")) {
                errors.add(new FieldError("questionnaire", "What was given here is not a Questionnaire resource. Post back the one the "
                        + "package step returned."));
            } else {
                posted = parsed.node();
            }
        }
        JsonNode contained = response == null ? null : QuestionnaireJson.containedQuestionnaire(response);
        JsonNode questionnaire = contained != null ? contained : posted;
        if (contained == null && response != null) {
            String names = JsonSafe.presentText(response, "questionnaire");
            if (names != null && names.startsWith("#")) {
                errors.add(new FieldError("questionnaireResponse", "The QuestionnaireResponse given points at a questionnaire inside itself, "
                        + names + ", and carries none with that id."));
            } else if (names != null && posted != null && QuestionnaireJson.canonical(posted) != null && !QuestionnaireJson.names(names, posted)) {
                errors.add(new FieldError("questionnaireResponse", "The QuestionnaireResponse given says it answers " + names
                        + ", which is not the questionnaire given beside it, " + QuestionnaireJson.canonical(posted) + "."));
            }
        }
        Map<String, List<JsonNode>> given = new LinkedHashMap<>();
        if (questionnaire == null) {
            if (errors.isEmpty()) {
                errors.add(new FieldError("questionnaire", "Give the questionnaire to answer: the one the package step returned, or a "
                        + "QuestionnaireResponse that carries its questionnaire inside itself."));
            }
            return new Posted(null, response, given, errors);
        }
        List<String> problems = QuestionnaireJson.problems(questionnaire);
        if (!problems.isEmpty()) {
            errors.add(new FieldError(contained != null ? "questionnaireResponse" : "questionnaire",
                    "This questionnaire cannot be answered. " + String.join(" ", problems)));
            return new Posted(questionnaire, response, given, errors);
        }
        Map<String, Item> items = new LinkedHashMap<>();
        for (Item item : QuestionnaireJson.flatten(QuestionnaireJson.items(questionnaire))) {
            if (item.linkId() != null) {
                items.put(item.linkId(), item);
            }
        }
        int index = 0;
        for (DtrAnswerInput answer : answers == null ? List.<DtrAnswerInput>of() : answers) {
            String field = "answers[" + index + "]";
            index++;
            String linkId = answer == null ? null : Texts.trimToNull(answer.linkId());
            if (linkId == null) {
                errors.add(new FieldError(field + ".linkId", "Name the question this answer is for by its linkId."));
                continue;
            }
            Item item = items.get(linkId);
            if (item == null) {
                errors.add(new FieldError(field + ".linkId", "The questionnaire has no item with the linkId " + linkId + "."));
                continue;
            }
            if (!item.question()) {
                errors.add(new FieldError(field + ".linkId", "The item " + linkId + " is "
                        + (item.display() ? "display text" : "a group") + ", which takes no answer."));
                continue;
            }
            if (given.containsKey(linkId)) {
                errors.add(new FieldError(field + ".linkId", "The question " + linkId + " is answered twice in this request. Give its "
                        + "answers once, as one list of values."));
                continue;
            }
            if (answer.values().size() > 1 && !item.repeats()) {
                errors.add(new FieldError(field + ".values", "The question " + linkId + " does not repeat, so it takes one answer."));
                continue;
            }
            List<JsonNode> values = new ArrayList<>();
            int valueIndex = 0;
            for (JsonNode value : answer.values()) {
                JsonNode converted = convert(item, value, field + ".values[" + valueIndex + "]", errors);
                if (converted != null) {
                    values.add(converted);
                }
                valueIndex++;
            }
            given.put(linkId, values);
        }
        return new Posted(questionnaire, response, given, errors);
    }

    /** One posted value as a FHIR answer object, or null when it cannot be one, in which case an error says why. */
    private static JsonNode convert(Item item, JsonNode value, String field, List<FieldError> errors) {
        if (value != null && value.isObject()) {
            // A FHIR answer already. It is used as written, which is also how a tester sends an
            // answer of a type the question does not ask for.
            if (QuestionnaireJson.answered(value)) {
                return value.deepCopy();
            }
            errors.add(new FieldError(field, "This answer object carries no value. " + AS_OBJECT));
            return null;
        }
        String text = JsonSafe.text(value);
        if (Texts.blank(text)) {
            errors.add(new FieldError(field, "This answer is empty. To take back an answer, post the question with an empty list of values."));
            return null;
        }
        String stripped = text.strip();
        String type = Texts.orElse(item.type(), "");
        ObjectNode answer = JSON.objectNode();
        switch (type) {
            case "boolean" -> {
                if ("true".equalsIgnoreCase(stripped)) {
                    answer.put("valueBoolean", true);
                } else if ("false".equalsIgnoreCase(stripped)) {
                    answer.put("valueBoolean", false);
                } else {
                    return refuse(field, "This question takes true or false.", errors);
                }
            }
            case "integer" -> {
                try {
                    answer.put("valueInteger", Integer.parseInt(stripped));
                } catch (NumberFormatException e) {
                    return refuse(field, "This question takes a whole number.", errors);
                }
            }
            case "decimal" -> {
                try {
                    answer.put("valueDecimal", new BigDecimal(stripped));
                } catch (NumberFormatException e) {
                    return refuse(field, "This question takes a number.", errors);
                }
            }
            case "date" -> {
                if (!stripped.matches(DATE) || (stripped.length() == 10 && !IsoDates.valid(stripped))) {
                    return refuse(field, "This question takes a date written as YYYY-MM-DD, for example 2026-01-31.", errors);
                }
                answer.put("valueDate", stripped);
            }
            case "dateTime" -> {
                if (!stripped.matches(DATE_TIME)) {
                    return refuse(field, "This question takes a date and time, for example 2026-01-31T09:30:00-05:00.", errors);
                }
                answer.put("valueDateTime", stripped);
            }
            case "time" -> {
                // A time without seconds is the same time. FHIR writes the seconds, so they are added.
                String time = stripped.matches("\\d{2}:\\d{2}") ? stripped + ":00" : stripped;
                if (!time.matches(TIME) || !realTime(time)) {
                    return refuse(field, "This question takes a time of day, for example 09:30:00.", errors);
                }
                answer.put("valueTime", time);
            }
            case "string", "text" -> answer.put("valueString", text);
            case "url" -> answer.put("valueUri", stripped);
            case "choice", "open-choice" -> {
                JsonNode option = option(item, stripped);
                if (option != null) {
                    String key = QuestionnaireJson.valueKey(option, "value");
                    answer.set(key, option.get(key).deepCopy());
                } else if ("open-choice".equals(type)) {
                    answer.put("valueString", text);
                } else if (JsonSafe.elements(item.node(), "answerOption").isEmpty()) {
                    return refuse(field, "This question lists no options of its own: they come from a value set, which this tool does "
                            + "not expand. " + AS_OBJECT, errors);
                } else {
                    return refuse(field, "The answer given is not one of the options of this question. Give the code of one of them.", errors);
                }
            }
            default -> {
                return refuse(field, (type.isEmpty() ? "This question states no type" : "This question is of the type " + type)
                        + ", which this tool cannot turn plain text into an answer for. " + AS_OBJECT, errors);
            }
        }
        return answer;
    }

    /** The option of a question that a posted text chooses: by the code of its coding, else by its value as text. */
    private static JsonNode option(Item item, String text) {
        for (JsonNode option : JsonSafe.elements(item.node(), "answerOption")) {
            String key = QuestionnaireJson.valueKey(option, "value");
            if (key != null && text.equals(QuestionnaireJson.valueText(key, option.get(key)))) {
                return option;
            }
        }
        return null;
    }

    private static boolean realTime(String time) {
        try {
            LocalTime.parse(time);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static JsonNode refuse(String field, String message, List<FieldError> errors) {
        errors.add(new FieldError(field, message));
        return null;
    }
}
