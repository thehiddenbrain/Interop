package org.point32health.memberprofile.config;

import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AppConfig {

    /** Single source of "now", in the plan's business zone, so ages from dates of birth roll over on the right day. */
    @Bean
    public Clock clock(MemberProfileProperties properties) {
        try {
            return Clock.system(ZoneId.of(properties.timeZone()));
        } catch (DateTimeException e) {
            throw new MemberProfileException(ErrorCode.INTERNAL_ERROR, "member-profile.time-zone '" + properties.timeZone() + "' is not a valid zone", e);
        }
    }

    /** ISO-8601 strings for dates in API responses and plain {@code /} in output (Jackson 3 defaults differ). */
    @Bean
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES);
    }

    /**
     * On JDK 17 the fan-out runs on a bounded pool (see {@code spring.task.execution.pool.*}); when it is
     * saturated the submitting request thread runs the task itself instead of failing the login. On JDK 21+
     * the executor is virtual threads and this customizer is not used.
     */
    @Bean
    public ThreadPoolTaskExecutorCustomizer callerRunsWhenSaturated() {
        return executor -> executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
