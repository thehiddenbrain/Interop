package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;

/**
 * The shapes of an environment are also asked with no call in hand: the Submit step reads the PAS
 * guide that way when the Workbench opens. No request is there to look at then, and every shape
 * must still be answered: a missing check for that case once ended every such page in an internal
 * error.
 */
class ShapesWithoutACallTest {

    @Test
    void everyShapeIsAnsweredWhenNoCallIsGiven() {
        RequestShapes shapes = ConfigStoreGateway.shapes(EnvironmentSettings.none(), null);

        for (RequestShape shape : RequestShape.values()) {
            assertThat(shapes.choice(shape).value())
                    .as("the value of %s with no environment value and no call", shape)
                    .isEqualTo(RequestShapes.DEFAULTS.choice(shape).value());
        }
    }
}
