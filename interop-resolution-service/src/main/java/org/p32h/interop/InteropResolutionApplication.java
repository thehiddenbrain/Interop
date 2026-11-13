package org.p32h.interop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Interop Resolution Service.
 *
 * <p>One operation today, no state, three reads: MMI, which identifies the member; the member information service,
 * whose coverage record on the date of service gives the line of business; and the coverage view, whose rows say
 * whether coverage is active on the date of service and give the coverage dates and the coverageId.
 * {@code POST /v1/interop/resolve}: Onyx sends the member ID as the provider's EMR supplied it and the date of
 * service; the service returns the ID as stored in MMI, the ID in every configured UM vendor's format, the line of
 * business, and whether coverage is active on the date of service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InteropResolutionApplication {

    public static void main(String[] args) {
        SpringApplication.run(InteropResolutionApplication.class, args);
    }
}
