package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ShownTest {

    @Test
    void aJsonNodeKeepsItsPresence() {
        JsonNode node = JsonSafe.parse("{\"n\":null,\"e\":\"\",\"v\":\"x\",\"num\":3,\"obj\":{\"a\":1},\"arr\":[]}").node();
        assertThat(Shown.of(JsonSafe.child(node, "gone"))).isEqualTo(Shown.absent());
        assertThat(Shown.of(JsonSafe.child(node, "n"))).isEqualTo(Shown.explicitNull());
        assertThat(Shown.of(JsonSafe.child(node, "e"))).isEqualTo(Shown.empty());
        assertThat(Shown.of(JsonSafe.child(node, "arr"))).isEqualTo(Shown.empty());
        assertThat(Shown.of(JsonSafe.child(node, "v"))).isEqualTo(new Shown(Presence.VALUE, "x"));
        assertThat(Shown.of(JsonSafe.child(node, "num")).value()).isEqualTo("3");
        assertThat(Shown.of(JsonSafe.child(node, "obj")).value()).isEqualTo("{\"a\":1}");
    }

    @Test
    void theThreeKindsOfNothingAreDistinctValues() {
        assertThat(Shown.absent()).isNotEqualTo(Shown.explicitNull());
        assertThat(Shown.explicitNull()).isNotEqualTo(Shown.empty());
        assertThat(Shown.absent()).isNotEqualTo(Shown.empty());
        assertThat(Shown.absent().hasValue()).isFalse();
        assertThat(Shown.of("x").hasValue()).isTrue();
    }

    @Test
    void aJavaStringMapsNullToAbsentAndEmptyToEmpty() {
        assertThat(Shown.of((String) null)).isEqualTo(Shown.absent());
        assertThat(Shown.of("")).isEqualTo(Shown.empty());
        assertThat(Shown.of("a").orNull()).isEqualTo("a");
    }

    @Test
    void aValueWithoutAValueIsRefused() {
        assertThatThrownBy(() -> new Shown(Presence.VALUE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Shown(null, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Shown(Presence.NULL, "ignored").value()).isNull();
    }

    @Test
    void serialisesPresenceAndOmitsAMissingValue() {
        assertThat(JsonSafe.mapper().writeValueAsString(Shown.absent())).isEqualTo("{\"presence\":\"ABSENT\"}");
        assertThat(JsonSafe.mapper().writeValueAsString(Shown.of("x"))).isEqualTo("{\"presence\":\"VALUE\",\"value\":\"x\"}");
    }
}
