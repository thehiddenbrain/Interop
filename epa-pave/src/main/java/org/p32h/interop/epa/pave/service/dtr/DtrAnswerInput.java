package org.p32h.interop.epa.pave.service.dtr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The answer a tester gives to one question.
 *
 * <p>A value is posted in one of two forms. As plain text, a number or true and false, it is
 * turned into the FHIR value the question's type calls for: {@code "8"} becomes an integer for an
 * integer question, and the code of an option becomes that option's coding. As a JSON object it is
 * taken to be a FHIR answer already, such as {@code {"valueCoding": {...}}}, and is used as written.
 *
 * @param linkId the question the answer is for
 * @param values the answers. More than one only for a question that repeats. An empty list takes
 *               back an answer given earlier
 */
public record DtrAnswerInput(String linkId, List<JsonNode> values) {

    public DtrAnswerInput {
        values = values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** An answer given as plain text values. */
    public static DtrAnswerInput of(String linkId, String... texts) {
        List<JsonNode> values = new ArrayList<>();
        for (String text : texts) {
            values.add(JsonNodeFactory.instance.stringNode(text));
        }
        return new DtrAnswerInput(linkId, values);
    }

    /** Prints the question and how many answers, never the answers: they can be clinical. */
    @Override
    public String toString() {
        return "DtrAnswerInput[" + linkId + ", " + values.size() + " value(s)]";
    }
}
