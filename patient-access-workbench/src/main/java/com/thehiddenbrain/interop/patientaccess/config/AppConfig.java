package com.thehiddenbrain.interop.patientaccess.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder.featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** ObjectMapper used for FHIR JSON bodies (kept separate from the API mapper: no null-dropping surprises). */
    @Bean(name = "fhirJsonMapper")
    public ObjectMapper fhirJsonMapper() {
        return new ObjectMapper();
    }
}
