package org.point32health.memberid.config;

import org.point32health.memberid.mmi.MmiClient;
import org.point32health.memberid.mmi.MmiProperties;
import org.point32health.memberid.vendor.VendorFormatter;
import org.point32health.memberid.vendor.VendorRegistry;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prints the effective configuration in the first lines of every start: profile, MMI target, MMI client
 * type (REST or STUB) and the vendor format table rendered against a sample id, so a wrong environment or a
 * wrong vendor format is visible before the first request.
 */
@Component
public class StartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    private final Environment environment;
    private final MmiProperties mmi;
    private final MmiClient mmiClient;
    private final VendorRegistry vendors;
    private final VendorFormatter formatter;
    private final MemberIdProperties memberId;

    public StartupReport(Environment environment, MmiProperties mmi, MmiClient mmiClient, VendorRegistry vendors,
            VendorFormatter formatter, MemberIdProperties memberId) {
        this.environment = environment;
        this.mmi = mmi;
        this.mmiClient = mmiClient;
        this.vendors = vendors;
        this.formatter = formatter;
        this.memberId = memberId;
    }

    @Override
    public void run(ApplicationArguments args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== Member ID Resolution Service =====\n");
        String[] active = environment.getActiveProfiles();
        sb.append("profiles          : ").append(Arrays.toString(active.length == 0 ? environment.getDefaultProfiles() : active))
          .append(active.length == 0 ? " (default)" : "").append('\n');
        sb.append("mmi client        : ").append(mmiClient.kind()).append('\n');
        sb.append("mmi url           : ").append(mmi.baseUrl()).append(mmi.path()).append('\n');
        sb.append("mmi clientId      : ").append(mmi.clientId()).append('\n');
        sb.append("mmi timeouts      : connect=").append(mmi.connectTimeout()).append(" read=").append(mmi.readTimeout()).append('\n');
        sb.append("mmi payload log   : ").append(mmi.logPayloads() ? "ON (request and response bodies, contains PHI)" : "off").append('\n');
        sb.append("dos window        : -").append(memberId.dateOfService().maxPastYears()).append("y / +")
          .append(memberId.dateOfService().maxFutureDays()).append("d (").append(memberId.dateOfService().zone()).append(")\n");
        sb.append("vendor formats (sample stored id 'S12345678   01' -> what the vendor receives):\n");
        vendors.all().forEach(v -> sb.append(String.format("  %-10s %-14s %s%n", v.code(), v.format(),
                formatter.format("S12345678   01", v.format()).describe())));
        sb.append("========================================");
        log.info(sb.toString());
    }
}
