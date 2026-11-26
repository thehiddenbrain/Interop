package org.p32h.interop.epa.pave.config;

import java.time.Clock;
import org.p32h.interop.epa.pave.service.ledger.RunTrail;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the trail's settings and builds the trail on both profiles. Kept apart from
 * {@link PropertiesConfig} because the trail has its own owner; a mock run plays a wire request
 * just as a live one sends it, and both are kept.
 */
@Configuration
@EnableConfigurationProperties(TrailProperties.class)
public class TrailConfig {

    @Bean
    public RunTrail runTrail(TrailProperties trail, TrackingProperties tracking, Clock clock) {
        return new RunTrail(trail, tracking, clock);
    }
}
