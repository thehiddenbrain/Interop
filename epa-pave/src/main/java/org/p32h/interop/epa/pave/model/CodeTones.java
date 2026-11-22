package org.p32h.interop.epa.pave.model;

import java.util.Map;

/**
 * The tone of one standard code on a determination row, so the screen can colour the code chip by
 * what the code means and keeps no table of codes of its own.
 *
 * <p>The element names are the ones the rows carry in {@code system} (the CRD sub-extension names and
 * the PAS review action), written out here so the model needs nothing from the services. A test
 * checks every code against the code lists the services read by, so a code named here is one they
 * recognise. A code not listed gets no tone, never a guess: an element such as the documentation
 * purpose or the information needed says what, not whether, and an unknown code is drawn plain.
 */
public final class CodeTones {

    private static final Map<String, Map<String, Tone>> TONES = Map.of(
            "covered", Map.of("covered", Tone.CLEAR, "not-covered", Tone.STOP, "conditional", Tone.ACTION),
            "pa-needed", Map.of("auth-needed", Tone.ACTION, "no-auth", Tone.CLEAR, "satisfied", Tone.CLEAR, "performpa", Tone.ACTION,
                    "conditional", Tone.ACTION),
            // patient is a CRD 2.2.0 ballot code the services recognise; it asks for documentation as clinical and admin do.
            "doc-needed", Map.of("clinical", Tone.ACTION, "admin", Tone.ACTION, "both", Tone.ACTION, "conditional", Tone.ACTION,
                    "patient", Tone.ACTION, "no-doc", Tone.CLEAR),
            "review-action", Map.of("A1", Tone.CLEAR, "A2", Tone.ACTION, "A3", Tone.STOP, "A4", Tone.PENDING, "A6", Tone.CLEAR,
                    "C", Tone.NEUTRAL, "CT", Tone.ACTION, "NA", Tone.CLEAR));

    private CodeTones() {
    }

    /**
     * The tone of a code of an element, or null when the element or the code has none.
     *
     * @param element the element or code system the code came from, as a row carries it in {@code system}
     * @param code    the code as returned
     */
    public static Tone of(String element, String code) {
        if (Texts.blank(element) || Texts.blank(code)) {
            return null;
        }
        Map<String, Tone> codes = TONES.get(element.strip());
        // Codes are compared exactly as returned: a code in another case is not the standard's code.
        return codes == null ? null : codes.get(code);
    }

    /** Every element with toned codes and its codes, for the test that checks them against the services' code lists. */
    public static Map<String, Map<String, Tone>> all() {
        return TONES;
    }
}
