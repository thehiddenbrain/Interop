package org.p32h.interop.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The banner's coverage view line shows where the database is and nothing a JDBC URL may carry besides. */
class StartupReportTest {

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
