package org.point32health.memberprofile;

import org.point32health.memberprofile.config.MemberProfileProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Member Profile Service.
 * <p>
 * Called by the League member portal and mobile app once per login. For one member id it answers, in a
 * single response, who the member is, which restricted features apply to them (segmentation) and what
 * they may do with each family member's information (family permissions). Everything is evaluated on
 * demand: the member comes from the MemberDomain service, the rules from this service's PostgreSQL tables.
 */
@SpringBootApplication
@EnableConfigurationProperties(MemberProfileProperties.class)
public class MemberProfileApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberProfileApplication.class, args);
    }
}
