package com.thehiddenbrain.interop.memberprofile.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** ISO-8601 strings for Instant/LocalDate in API responses and plain {@code /} in output (Jackson 3 defaults). */
    @Bean
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES);
    }
}
