package org.p32h.interop.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.InteropResolutionApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A wrong configuration must stop the application with a message that names the property. */
class ConfigValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InteropResolutionApplication.class)
            .withPropertyValues(
                    "mmi.client-id=INTEROP",
                    "mmi.base-url=http://mmi.test",
                    "member-info.base-url=http://member-info.test",
                    "member-id.vendors.EVICORE.format=COMPACT_11");

    @Test
    void validConfigurationStarts() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void unknownVendorFormatFails() {
        runner.withPropertyValues("member-id.vendors.MHK.format=ELEVEN")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("ELEVEN");
                });
    }

    @Test
    void stubOutsideDevOrTestProfileFails() {
        runner.withPropertyValues("spring.profiles.active=PQA", "mmi.stub.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("only allowed with the DEV or test profile");
                });
    }

    @Test
    void missingBaseUrlWithoutStubFails() {
        runner.withPropertyValues("mmi.base-url=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("mmi.base-url");
                });
    }

    @Test
    void missingMemberInfoBaseUrlWithoutStubFails() {
        runner.withPropertyValues("member-info.base-url=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("member-info.base-url");
                });
    }

    @Test
    void memberInfoStubOutsideDevOrTestProfileFails() {
        runner.withPropertyValues("spring.profiles.active=PQA", "member-info.stub.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("member-info.stub.enabled=true is only allowed with the DEV or test profile");
                });
    }

    @Test
    void blankPayerIdFails() {
        runner.withPropertyValues("payer.id=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("payer");
                });
    }

    @Test
    void vendorPayerWithoutANameFails() {
        runner.withPropertyValues("member-id.vendors.EVICORE.payer.THP.id=TUFTS")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("payer");
                });
    }

    @Test
    void aDatabaseVariableThatIsNotDefinedFails() {
        runner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:config", "spring.datasource.username=sa",
                        "spring.datasource.password=${ODS_PASSWORD_NOT_DEFINED}")
                .run(ctx -> {
                    assertThat(ctx).as("never started with the text ${...} for a password").hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).as("the property is named, the value is not shown")
                            .contains("spring.datasource.password is not defined").doesNotContain("ODS_PASSWORD_NOT_DEFINED");
                });
        runner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:config", "spring.datasource.username=${ODS_USERNAME_NOT_DEFINED}",
                        "spring.datasource.password=secret")
                .run(ctx -> assertThat(rootMessage(ctx.getStartupFailure())).contains("spring.datasource.username is not defined"));
    }

    @Test
    void anEmptyDatabaseUserNameOrPasswordFails() {
        ApplicationContextRunner oracle = runner.withPropertyValues("spring.datasource.url=jdbc:oracle:thin:@//ods-host:1521/odssvc",
                "spring.jpa.database-platform=org.hibernate.dialect.OracleDialect",
                "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false");
        oracle.withPropertyValues("spring.datasource.username=ods_reader", "spring.datasource.password=")
                .run(ctx -> assertThat(rootMessage(ctx.getStartupFailure())).as("never a logon without a password")
                        .contains("spring.datasource.password is empty"));
        oracle.withPropertyValues("spring.datasource.username=", "spring.datasource.password=secret")
                .run(ctx -> assertThat(rootMessage(ctx.getStartupFailure())).contains("spring.datasource.username is empty"));
        oracle.withPropertyValues("spring.datasource.username=ods_reader", "spring.datasource.password=secret")
                .run(ctx -> assertThat(ctx).as("the same database with both: nothing to refuse, and no connection is made to start").hasNotFailed());
    }

    @Test
    void aPasswordWithBracesInsideIsNotTakenForAMissingVariable() {
        runner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:braces", "spring.datasource.username=sa",
                        "spring.datasource.password=Xy${zz}Q1")
                .run(ctx -> {
                    assertThat(ctx).as("only a value that is nothing but ${...} is refused").hasNotFailed();
                    assertThat(ctx.getBean(DataSourceProperties.class).getPassword()).as("and this one arrives as given").isEqualTo("Xy${zz}Q1");
                });
    }

    @Test
    void stubInTestProfileStarts() {
        runner.withPropertyValues("spring.profiles.active=test", "mmi.stub.enabled=true", "member-info.stub.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            sb.append(t.getMessage()).append(" | ");
            t = t.getCause();
        }
        return sb.toString();
    }
}
