package com.thehiddenbrain.interop.memberid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Member ID Resolution Service.
 *
 * <p>One endpoint ({@code POST /api/v1/member-ids/resolve}), one downstream (MMI), no state.
 * Onyx sends the member ID as the provider's EMR supplied it, the date of service and the UM vendor;
 * the service returns the ID as stored in MMI, the ID in the vendor's format and whether coverage is
 * active on the date of service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MemberIdResolutionApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberIdResolutionApplication.class, args);
    }
}
