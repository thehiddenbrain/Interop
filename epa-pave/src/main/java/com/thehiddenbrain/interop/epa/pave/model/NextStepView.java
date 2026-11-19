package com.thehiddenbrain.interop.epa.pave.model;

import java.util.Map;

/**
 * What the tester should do next, stated by the tool so nobody has to infer it.
 *
 * @param action   the step the screen can offer
 * @param headline the instruction in plain language
 * @param reason   why this is the next step, tied to what the response said
 * @param carry    values the next step starts from, such as the questionnaire a DTR link points at
 */
public record NextStepView(NextAction action, String headline, String reason, Map<String, String> carry) {

    public NextStepView {
        carry = carry == null ? Map.of() : Map.copyOf(carry);
    }

    public static NextStepView of(NextAction action, String headline, String reason) {
        return new NextStepView(action, headline, reason, Map.of());
    }
}
