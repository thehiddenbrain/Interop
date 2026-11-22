package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A part of a request where a vendor's sandbox departs from FHIR or the guides, so the shape sent
 * is a setting of the environment rather than a fixed choice.
 *
 * <p>Shapes that the guides allow and the vendor accepted are simply what the tool sends, and are
 * not listed here. Only a departure from FHIR becomes a setting, and its default is always the shape
 * FHIR requires: turning a departure on needs evidence (the environment states it, or the vendor's
 * own sample in the collection shows it), never a guess.
 */
public enum RequestShape {
    CRD_DRAFT_ORDERS_TYPE("draftOrders type", "context.draftOrders.type",
            List.of(new Option(RequestShape.SENT, "Sent (FHIR requires it)"),
                    new Option(RequestShape.LEFT_OUT, "Left out (this vendor refuses it)")),
            RequestShape.SENT,
            "The vendor's sandbox refused context.draftOrders.type with HTTP 400; FHIR requires Bundle.type.");

    /** The value of a shape whose element is sent as FHIR requires. */
    public static final String SENT = "SENT";
    /** The value of a shape whose element is left out because the vendor refuses it. */
    public static final String LEFT_OUT = "LEFT_OUT";

    /**
     * One value a shape can take, with the words a tester picks it by.
     *
     * @param value the stored value, such as SENT
     * @param words the option as the form shows it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(String value, String words) {

        /** The value only, by the rule every record holding text keeps: its toString prints nothing free-form. */
        @Override
        public String toString() {
            return "Option[" + value + "]";
        }
    }

    private final String label;
    private final String field;
    private final List<Option> options;
    private final String defaultValue;
    private final String why;

    RequestShape(String label, String field, List<Option> options, String defaultValue, String why) {
        this.label = label;
        this.field = field;
        this.options = List.copyOf(options);
        this.defaultValue = defaultValue;
        this.why = why;
    }

    /** The setting's name in the environment's form. */
    public String label() {
        return label;
    }

    /** The element of the request the setting decides, as a path a tester finds in the wire view. */
    public String field() {
        return field;
    }

    /** The values the setting takes, the default first. */
    public List<Option> options() {
        return options;
    }

    /** The value FHIR requires, which applies when nothing states another. */
    public String defaultValue() {
        return defaultValue;
    }

    /** One line on why this is a setting at all. */
    public String why() {
        return why;
    }

    /** True when the value is one of this shape's options, exactly as stored. */
    public boolean allows(String value) {
        return words(value) != null;
    }

    /** The words of one of this shape's values, or null when the value is not one of its options. */
    public String words(String value) {
        for (Option option : options) {
            if (option.value().equals(value)) {
                return option.words();
            }
        }
        return null;
    }

    /** The shape a stored or posted key names, or null when it names none, so an unknown key is refused, never dropped. */
    public static RequestShape parse(String name) {
        if (Texts.blank(name)) {
            return null;
        }
        String wanted = name.strip();
        for (RequestShape shape : values()) {
            if (shape.name().equals(wanted)) {
                return shape;
            }
        }
        return null;
    }
}
