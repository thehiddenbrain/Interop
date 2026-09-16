package com.thehiddenbrain.interop.patientaccess.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs conformance runs and parallel per-patient fetches. */
    @Bean(name = "workbenchExecutor")
    public TaskExecutor workbenchExecutor(WorkbenchProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("paw-");
        executor.setCorePoolSize(Math.max(2, properties.conformance().concurrency()));
        executor.setMaxPoolSize(Math.max(4, properties.conformance().concurrency() * 2));
        executor.setQueueCapacity(200);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * ISO-8601 strings for Instant/LocalDate in API responses (Jackson 3 defaults to timestamps) and plain
     * {@code /} in output: the API and the demo FHIR server carry many URLs and Jackson 3 escapes slashes by default.
     */
    @Bean
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES);
    }

}
