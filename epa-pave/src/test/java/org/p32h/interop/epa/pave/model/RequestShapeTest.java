package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestShapeTest {

    private static final RequestShape TYPE = RequestShape.CRD_DRAFT_ORDERS_TYPE;

    @Test
    void draftOrdersTypeIsSentByDefaultWithItsWords() {
        assertThat(TYPE.label()).isEqualTo("draftOrders type");
        assertThat(TYPE.field()).isEqualTo("context.draftOrders.type");
        assertThat(TYPE.defaultValue()).isEqualTo("SENT");
        assertThat(TYPE.options()).extracting(RequestShape.Option::value).containsExactly("SENT", "LEFT_OUT");
        assertThat(TYPE.words("SENT")).isEqualTo("Sent (FHIR requires it)");
        assertThat(TYPE.words("LEFT_OUT")).isEqualTo("Left out (this vendor refuses it)");
        assertThat(TYPE.why()).isEqualTo("The vendor's sandbox refused context.draftOrders.type with HTTP 400; FHIR requires Bundle.type.");
    }

    @Test
    void anUnknownValueIsNotAnOption() {
        assertThat(TYPE.allows("LEFT_OUT")).isTrue();
        assertThat(TYPE.allows("left_out")).isFalse();
        assertThat(TYPE.allows(null)).isFalse();
        assertThat(TYPE.words("OMITTED")).isNull();
    }

    @Test
    void parseNamesAShapeOrNothing() {
        assertThat(RequestShape.parse("CRD_DRAFT_ORDERS_TYPE")).isEqualTo(TYPE);
        assertThat(RequestShape.parse("CRD_DRAFT_ORDER_TYPE")).isNull();
        assertThat(RequestShape.parse(null)).isNull();
    }

    @Test
    void theDefaultsSendEveryShapeAsFhirRequiresAndSaySo() {
        for (RequestShape shape : RequestShape.values()) {
            assertThat(RequestShapes.DEFAULTS.value(shape)).isEqualTo(shape.defaultValue());
            assertThat(RequestShapes.DEFAULTS.choice(shape).from()).isEqualTo("default");
            assertThat(RequestShapes.DEFAULTS.choice(shape).requestName()).isNull();
        }
        assertThat(RequestShapes.DEFAULTS.leftOut(TYPE)).isFalse();
    }

    @Test
    void aValueFromTheSampleKeepsTheRequestThatShowedIt() {
        RequestShapes shapes = RequestShapes.DEFAULTS.with(TYPE, RequestShapes.Choice.ofSample("LEFT_OUT", "order-sign Happy Path"));
        assertThat(shapes.value(TYPE)).isEqualTo("LEFT_OUT");
        assertThat(shapes.leftOut(TYPE)).isTrue();
        assertThat(shapes.choice(TYPE).from()).isEqualTo("the collection's sample");
        assertThat(shapes.choice(TYPE).requestName()).isEqualTo("order-sign Happy Path");
        assertThat(RequestShapes.DEFAULTS.value(TYPE)).as("the defaults are never changed in place").isEqualTo("SENT");
    }

    @Test
    void aValueTheEnvironmentStatesHasNoRequestName() {
        RequestShapes shapes = new RequestShapes(Map.of(TYPE, new RequestShapes.Choice("LEFT_OUT", "this environment", "ignored")));
        assertThat(shapes.choice(TYPE).from()).isEqualTo("this environment");
        assertThat(shapes.choice(TYPE).requestName()).isNull();
        assertThat(RequestShapes.Choice.ofEnvironment("SENT").from()).isEqualTo("this environment");
    }

    @Test
    void aValueOutsideTheOptionsOrAnUnnamedSourceIsRefused() {
        assertThatThrownBy(() -> new RequestShapes(Map.of(TYPE, RequestShapes.Choice.ofEnvironment("OMITTED"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestShapes.Choice("SENT", "a guess", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestShapes.Choice(" ", "default", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullMapReadsAsEveryDefault() {
        assertThat(new RequestShapes(null).value(TYPE)).isEqualTo("SENT");
        assertThat(new RequestShapes(null).choices()).isEmpty();
    }
}
