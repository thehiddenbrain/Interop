package org.p32h.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The masks of held secrets in a value one step hands on to the next.
 *
 * <p>Every answer is read with its masks in place, so a value an answer gave that repeats a secret
 * this tool holds is handed on as the mask, such as {@code [secret clientSecret]}, never as the
 * value. A mask is something to look at, not something to use: put into a field it reads as a
 * value the service gave, and sent on it asks the service about a thing nobody named. So a value
 * with a mask in it fills no field and leaves in no request, and the tester is told why. The clear
 * value is not recovered from anywhere: whoever knows it types it.
 *
 * <p>A mask is recognised by the form {@link Secrets#mask(String)} gives it. Nothing here holds or
 * looks at a secret's value.
 */
public final class CarriedMasks {

    private static final String OPEN = Secrets.mask("").substring(0, Secrets.mask("").length() - 1);
    private static final char CLOSE = ']';

    private CarriedMasks() {
    }

    /**
     * The masks a text holds, by themselves and joined by " and ", or null when it holds none. A
     * single pass from left to right, so the cost is the length of the text.
     */
    public static String in(String text) {
        if (text == null) {
            return null;
        }
        List<String> masks = new ArrayList<>();
        int at = text.indexOf(OPEN);
        while (at >= 0) {
            int end = text.indexOf(CLOSE, at + OPEN.length());
            if (end < 0) {
                break;
            }
            String mask = text.substring(at, end + 1);
            if (!masks.contains(mask)) {
                masks.add(mask);
            }
            at = text.indexOf(OPEN, end + 1);
        }
        return masks.isEmpty() ? null : String.join(" and ", masks);
    }

    /**
     * A value handed on that holds a mask.
     *
     * @param key   the carry key, one of the CARRY constants of {@link NextStepView}
     * @param masks the masks the value holds, as {@link #in(String)} gives them
     */
    public record Found(String key, String masks) {

        /** The key alone: the masks name secrets, and a log line has no use for which. */
        @Override
        public String toString() {
            return "Found[" + key + "]";
        }
    }

    /**
     * The first value handed on that holds a mask, in the order the hand-over lists its keys and
     * values, or null when none does.
     */
    public static Found first(Map<String, List<String>> carry) {
        if (carry == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : carry.entrySet()) {
            for (String value : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                String masks = in(value);
                if (masks != null) {
                    return new Found(entry.getKey(), masks);
                }
            }
        }
        return null;
    }

    /**
     * Why a step that would be built from a masked value handed on is not sent. The same words
     * whether the step came from a screen, a chain or a suite.
     */
    public static String refusal(Found found) {
        String what = NextStepView.CARRY_LABELS.getOrDefault(found.key(), found.key());
        return refusal(what, found.masks(), "Type the value on the form, or settle the secret in the Config tab.");
    }

    /**
     * The same refusal for a value handed on that has a remedy of its own: a questionnaire the
     * package gave is not something a tester can type, so for it the remedy is to settle the
     * secret and ask for the package again.
     */
    public static String refusal(String what, String masks, String remedy) {
        return what + ", handed on from the step before, was given masked, as " + masks + ", because the answer it came from "
                + "repeats a secret this tool holds. A mask is never sent, so this step was not sent. " + remedy;
    }

    /** Why a field holding a mask is refused: the value is the mask of a held secret, not one the service gave. */
    public static String typed(String what, String masks) {
        return "This is not " + what + " but a mask, " + masks + ": an answer gave the value masked because it repeats a secret this tool "
                + "holds. A mask is never sent. Type the value itself, or settle the secret in the Config tab.";
    }
}
