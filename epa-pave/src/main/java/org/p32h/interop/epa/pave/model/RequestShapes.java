package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The shape each {@link RequestShape} takes in one call, each with where its value came from.
 *
 * <p>The source travels with the value because a departure from FHIR must never look like the
 * tool's own choice: the request notes and the environment's form say whether this environment
 * stated it, the vendor's sample in the collection showed it, or nothing did and the default applies.
 *
 * @param choices the value of each shape that has one stated or found; a shape that is absent takes its default
 */
public record RequestShapes(Map<RequestShape, Choice> choices) {

    /** The environment's own setting decided the value. */
    public static final String FROM_ENVIRONMENT = "this environment";
    /** The saved body of the collection request the call came from showed the value. */
    public static final String FROM_SAMPLE = "the collection's sample";
    /** Nothing stated a value, so the shape FHIR requires applies. */
    public static final String FROM_DEFAULT = "default";

    /** Every shape at its default: what a call carries when its environment states nothing and no sample was read. */
    public static final RequestShapes DEFAULTS = new RequestShapes(Map.of());

    /**
     * The value of one shape and its source.
     *
     * @param value       one of the shape's option values
     * @param from        one of {@link #FROM_ENVIRONMENT}, {@link #FROM_SAMPLE} or {@link #FROM_DEFAULT}
     * @param requestName the name of the collection request whose sample showed the value; set only
     *                    when {@code from} is the collection's sample
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(String value, String from, String requestName) {

        public Choice {
            if (Texts.blank(value)) {
                throw new IllegalArgumentException("A request shape choice needs a value.");
            }
            if (!FROM_ENVIRONMENT.equals(from) && !FROM_SAMPLE.equals(from) && !FROM_DEFAULT.equals(from)) {
                throw new IllegalArgumentException("A request shape choice names where its value came from.");
            }
            requestName = FROM_SAMPLE.equals(from) && Texts.present(requestName) ? requestName.strip() : null;
        }

        /** A value the environment states. */
        public static Choice ofEnvironment(String value) {
            return new Choice(value, FROM_ENVIRONMENT, null);
        }

        /** A value the vendor's sample in the collection shows, with the name of the request that holds it. */
        public static Choice ofSample(String value, String requestName) {
            return new Choice(value, FROM_SAMPLE, requestName);
        }

        /** The default of a shape. */
        public static Choice ofDefault(RequestShape shape) {
            return new Choice(shape.defaultValue(), FROM_DEFAULT, null);
        }

        /** The value and its source; a collection request's name is named by the tester and printed as present only. */
        @Override
        public String toString() {
            return "Choice[" + value + ", from=" + from + (requestName == null ? "" : ", request named") + "]";
        }
    }

    public RequestShapes {
        Map<RequestShape, Choice> copy = new EnumMap<>(RequestShape.class);
        if (choices != null) {
            choices.forEach((shape, choice) -> {
                if (shape == null || choice == null) {
                    return;
                }
                if (!shape.allows(choice.value())) {
                    // A value outside the options would be sent as a shape nobody chose; the reader refuses it first.
                    throw new IllegalArgumentException("A request shape value is not one of its options.");
                }
                copy.put(shape, choice);
            });
        }
        choices = Collections.unmodifiableMap(copy);
    }

    /** The choice for a shape: the one stated or found, else the shape's default marked as such. Never null. */
    public Choice choice(RequestShape shape) {
        Choice stated = choices.get(shape);
        return stated == null ? Choice.ofDefault(shape) : stated;
    }

    /** The value a shape takes in this call. Never null. */
    public String value(RequestShape shape) {
        return choice(shape).value();
    }

    /** True when the shape's element is left out of the request. */
    public boolean leftOut(RequestShape shape) {
        return RequestShape.LEFT_OUT.equals(value(shape));
    }

    /** Each shape's choice as its own toString prints it. */
    @Override
    public String toString() {
        return "RequestShapes" + choices;
    }

    /** These shapes with one choice replaced. */
    public RequestShapes with(RequestShape shape, Choice choice) {
        Map<RequestShape, Choice> next = new EnumMap<>(RequestShape.class);
        next.putAll(choices);
        next.put(shape, choice);
        return new RequestShapes(next);
    }
}
