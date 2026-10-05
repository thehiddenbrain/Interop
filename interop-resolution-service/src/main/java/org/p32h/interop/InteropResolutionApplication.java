package org.p32h.interop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Interop Resolution Service.
 *
 * <p>Two operations, one downstream (MMI), no state. {@code POST /api/v1/member-ids/resolve}: Onyx sends the
 * member ID as the provider's EMR supplied it, the date of service and the UM vendor; the service returns the
 * ID as stored in MMI, the ID in the vendor's format and whether coverage is active on the date of service.
 * {@code POST /api/v1/member-ids/vendor-map}: the same without a vendor; the ID comes back in every configured
 * vendor's format.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InteropResolutionApplication {

    public static void main(String[] args) {
        SpringApplication.run(InteropResolutionApplication.class, args);
    }
}
