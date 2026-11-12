package org.p32h.interop.config;

import org.p32h.interop.memberinfo.MemberInfoClient;
import org.p32h.interop.memberinfo.MemberInfoProperties;
import org.p32h.interop.mmi.MmiClient;
import org.p32h.interop.mmi.MmiProperties;
import org.p32h.interop.vendor.Vendor;
import org.p32h.interop.vendor.VendorFormatter;
import org.p32h.interop.vendor.VendorRegistry;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prints the effective configuration in the first lines of every start: profile, MMI and member information targets and
 * client types (REST or STUB), the database behind the coverage view and the vendor format table rendered against a
 * sample id, so a wrong environment or a wrong vendor format is visible before the first request.
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
    private final PayerProperties payer;
    private final MemberInfoProperties memberInfo;
    private final MemberInfoClient memberInfoClient;
    private final String coverageDatabase;
    private final String coverageUser;

    public StartupReport(Environment environment, MmiProperties mmi, MmiClient mmiClient, VendorRegistry vendors,
            VendorFormatter formatter, MemberIdProperties memberId, PayerProperties payer, MemberInfoProperties memberInfo,
            MemberInfoClient memberInfoClient, DataSourceProperties dataSource) {
        this.environment = environment;
        this.mmi = mmi;
        this.mmiClient = mmiClient;
        this.vendors = vendors;
        this.formatter = formatter;
        this.memberId = memberId;
        this.payer = payer;
        this.memberInfo = memberInfo;
        this.memberInfoClient = memberInfoClient;
        // An ODS_* variable that is not defined leaves its ${...} text in the value, and that text would go to the
        // database as the user name or the password, again and again; an empty one would send a logon without an
        // account or a password the same way. Stop the start here instead. An empty value is right for H2 only, the
        // stand-in database of DEV and the tests; a missing URL is reported by the datasource itself.
        String url = dataSource.getUrl();
        boolean emptyAllowed = url == null || url.isBlank() || url.startsWith("jdbc:h2:");
        requireDefined("spring.datasource.username", dataSource.getUsername(), emptyAllowed);
        requireDefined("spring.datasource.password", dataSource.getPassword(), emptyAllowed);
        this.coverageDatabase = database(url);
        this.coverageUser = dataSource.getUsername();
    }

    /** The message names the property and never shows the value. */
    private static void requireDefined(String property, String value, boolean emptyAllowed) {
        boolean empty = value == null || value.isBlank();
        if (empty ? !emptyAllowed : value.matches("\\$\\{[^}]*}")) {
            throw new IllegalStateException(property + (empty ? " is empty" : " is not defined")
                    + ": every profile but DEV needs ODS_JDBC_URL, ODS_USERNAME and ODS_PASSWORD");
        }
    }

    /**
     * The database of a JDBC URL, for the banner. Connection properties are cut off, and what is left is shown only in a
     * form that cannot carry an account or a password: nothing between {@code thin:} and the {@code @}.
     */
    static String database(String url) {
        String plain = url == null ? "" : url.replaceFirst("[;?].*", "");
        return plain.matches("jdbc:(oracle:thin:@|h2:mem:)[\\w.:/()=, -]+") ? plain : "(not shown)";
    }

    @Override
    public void run(ApplicationArguments args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== Interop Resolution Service =====\n");
        String[] active = environment.getActiveProfiles();
        sb.append("profiles          : ").append(Arrays.toString(active.length == 0 ? environment.getDefaultProfiles() : active))
          .append(active.length == 0 ? " (default)" : "").append('\n');
        sb.append("mmi client        : ").append(mmiClient.kind()).append('\n');
        sb.append("mmi url           : ").append(mmi.baseUrl()).append(mmi.path()).append('\n');
        sb.append("mmi clientId      : ").append(mmi.clientId()).append('\n');
        sb.append("mmi timeouts      : connect=").append(mmi.connectTimeout()).append(" read=").append(mmi.readTimeout()).append('\n');
        sb.append("mmi payload log   : ").append(mmi.logPayloads() ? "ON (request and response bodies, contains PHI)" : "off").append('\n');
        sb.append("member-info client: ").append(memberInfoClient.kind()).append('\n');
        sb.append("member-info url   : ").append(memberInfo.baseUrl()).append(memberInfo.path()).append('\n');
        sb.append("member-info time  : connect=").append(memberInfo.connectTimeout()).append(" read=").append(memberInfo.readTimeout()).append('\n');
        sb.append("member-info log   : ").append(memberInfo.logPayloads() ? "ON (request and response bodies)" : "off").append('\n');
        sb.append("coverage view     : member_coverage_rel_v at ").append(coverageDatabase).append(" as ").append(coverageUser).append('\n');
        sb.append("payer (default)   : ").append(payer.id()).append(" / ").append(payer.name()).append('\n');
        sb.append("dos limit         : not older than ").append(memberId.dateOfService().maxPastYears())
          .append("y; any future date is judged on the coverage on record (").append(memberId.dateOfService().zone()).append(")\n");
        sb.append("recent coverage   : not covered on the date of service and no coverage in the last ")
          .append(memberId.recentCoverageMonths()).append(" months -> NOT_FOUND, not INACTIVE\n");
        sb.append("vendor formats (sample stored id 'S12345678   01' -> what the vendor receives):\n");
        vendors.all().forEach(v -> sb.append(String.format("  %-10s %-14s %-18s payer %s%n", v.code(), v.format(),
                formatter.format("S12345678   01", v.format()).describe(), payerSummary(v))));
        sb.append("========================================");
        log.info(sb.toString());
    }

    private static String payerSummary(Vendor v) {
        if (v.payerByCompany().isEmpty()) {
            return "default";
        }
        StringBuilder sb = new StringBuilder();
        v.payerByCompany().forEach((company, p) -> sb.append(company).append('=').append(p.id()).append(" / ").append(p.name()).append("; "));
        return sb.append("else default").toString();
    }
}
