package org.p32h.interop.epa.pave.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers every configuration properties record and the clock the rest of the tool reads time from. */
@Configuration
@EnableConfigurationProperties({EpaProperties.class, ConfigStoreProperties.class, TrackingProperties.class,
        CodelistProperties.class, AppProperties.class, OnyxProperties.class, LookupProperties.class,
        TestListProperties.class})
public class PropertiesConfig {

    /** One clock for the whole tool, so tests can fix the time and the ledger's day boundary is testable. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
