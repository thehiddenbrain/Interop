package org.point32health.interop.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.point32health.interop.InteropResolutionApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A wrong configuration must stop the application with a message that names the property. */
class ConfigValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InteropResolutionApplication.class)
            .withPropertyValues(
                    "mmi.client-id=INTEROP",
                    "mmi.base-url=http://mmi.test",
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
    void duplicateAliasFails() {
        runner.withPropertyValues("member-id.vendors.MHK.format=SPACED_14", "member-id.vendors.MHK.aliases[0]=EVICORE")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("EVICORE");
                });
    }

    @Test
    void stubOutsideDevOrTestProfileFails() {
        runner.withPropertyValues("spring.profiles.active=pqa", "mmi.stub.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("only allowed with the dev or test profile");
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
    void stubInTestProfileStarts() {
        runner.withPropertyValues("spring.profiles.active=test", "mmi.stub.enabled=true")
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
