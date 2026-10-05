package org.point32health.interop.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One injectable clock so "today" is the plan's business zone in production and a fixed date in tests. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(MemberIdProperties properties) {
        return Clock.system(ZoneId.of(properties.dateOfService().zone()));
    }
}
