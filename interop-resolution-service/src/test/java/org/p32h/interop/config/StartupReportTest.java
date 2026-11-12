package org.p32h.interop.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.p32h.interop.InteropResolutionApplication;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The banner's coverage view line shows where the database is and nothing a JDBC URL may carry besides. */
class StartupReportTest {

    @Test
    void theBannerShowsTheDatabaseAndTheAccountNeverThePassword() {
        new ApplicationContextRunner().withUserConfiguration(InteropResolutionApplication.class)
                .withPropertyValues("mmi.client-id=INTEROP", "mmi.base-url=http://mmi.test", "member-info.base-url=http://member-info.test",
                        "member-id.vendors.EVICORE.format=COMPACT_11",
                        "spring.datasource.url=jdbc:h2:mem:banner;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
                        "spring.datasource.password=BannerPw1")
                .run(ctx -> {
                    ListAppender<ILoggingEvent> logs = new ListAppender<>();
                    logs.start();
                    Logger reportLog = (Logger) LoggerFactory.getLogger(StartupReport.class);
                    reportLog.addAppender(logs);
                    try {
                        ctx.getBean(StartupReport.class).run(new DefaultApplicationArguments());
                    } finally {
                        reportLog.detachAppender(logs);
                    }
                    assertThat(logs.list).hasSize(1);
                    String banner = logs.list.get(0).getFormattedMessage();
                    assertThat(banner.lines().filter(line -> line.startsWith("coverage view")))
                            .containsExactly("coverage view     : member_coverage_rel_v at jdbc:h2:mem:banner as sa");
                    assertThat(banner).doesNotContain("BannerPw1");
                });
    }

    @Test
    void aPlainUrlIsShownWithoutItsConnectionProperties() {
        assertThat(StartupReport.database("jdbc:oracle:thin:@//ods-host:1521/odssvc")).isEqualTo("jdbc:oracle:thin:@//ods-host:1521/odssvc");
        assertThat(StartupReport.database("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=ods-host)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=odssvc)))"))
                .as("the long form").startsWith("jdbc:oracle:thin:@(DESCRIPTION=").endsWith("(SERVICE_NAME=odssvc)))");
        assertThat(StartupReport.database("jdbc:oracle:thin:@tcps://ods-host:2484/odssvc?javax.net.ssl.keyStorePassword=KsSecret"))
                .isEqualTo("jdbc:oracle:thin:@tcps://ods-host:2484/odssvc");
        assertThat(StartupReport.database("jdbc:h2:mem:coverage;DB_CLOSE_DELAY=-1")).isEqualTo("jdbc:h2:mem:coverage");
    }

    @Test
    void aUrlThatCarriesAnAccountIsNotShownAtAll() {
        assertThat(StartupReport.database("jdbc:oracle:thin:scott/tiger@//ods-host:1521/odssvc")).isEqualTo("(not shown)");
        assertThat(StartupReport.database("jdbc:oracle:thin:scott/\"Pw@Tail9\"@//ods-host:1521/odssvc")).as("an @ inside the password").isEqualTo("(not shown)");
        assertThat(StartupReport.database("jdbc:oracle:thin:scott/Pw?Tail9@//ods-host:1521/odssvc")).as("a ? inside the password").isEqualTo("(not shown)");
        assertThat(StartupReport.database("jdbc:oracle:thin:@//ods-host:1521/odssvc?oracle.net.wallet_password=Pw@Tail9"))
                .as("an @ inside a connection property").isEqualTo("jdbc:oracle:thin:@//ods-host:1521/odssvc");
        assertThat(StartupReport.database("jdbc:postgresql://user:secret@host/db")).as("any other form").isEqualTo("(not shown)");
        assertThat(StartupReport.database(null)).isEqualTo("(not shown)");
    }
}
