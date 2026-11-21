package org.p32h.interop.epa.pave.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the application reads a JSON request body.
 *
 * <p>A property the receiving type does not have is an error, not something to drop. The frontend
 * and the endpoints are written at different times by different hands, and a name that does not
 * match would otherwise bind to nothing without a word: a body that says {@code expectation} where
 * the endpoint reads {@code expected} would be accepted and the run recorded as unverified. The run
 * requests collect unknown properties themselves and name every one of them in their own
 * validation; this setting covers every other body. The ledger record keeps its own tolerance for
 * unknown properties, because a line written by a later version must still be readable.
 *
 * <p>A number is never read as an enum by its position in the declaration.
 *
 * <p>A key repeated inside one object is an error, as it is in a scenario file and a suite file.
 * Read leniently, the last value would win in silence, and a scripted request could run another
 * case than the one its author reads in it. A body of the tester's own travels inside a request
 * as text and is not read here, so it is passed on as written.
 */
@Configuration
public class JacksonConfig {

    /**
     * The settings, in one place. The application's mapper is given them below, and the controller
     * tests build their mapper with the same call, so a test sees what the running application does.
     */
    public static JsonMapper.Builder strict(JsonMapper.Builder builder) {
        return builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    /** Runs after the framework's own customizer, which switches the first of these off. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    JsonMapperBuilderCustomizer strictRequestBodies() {
        return JacksonConfig::strict;
    }
}
