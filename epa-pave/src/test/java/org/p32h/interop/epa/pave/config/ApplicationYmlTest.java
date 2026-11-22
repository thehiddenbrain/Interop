package org.p32h.interop.epa.pave.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The values application.yml ships with, read from the file itself. Each one was decided for the
 * operator's workstation, so a change to one of them is a change of behaviour that a test has to see.
 */
class ApplicationYmlTest {

    private static Properties shipped() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).as("application.yml on the class path").isNotNull();
        return properties;
    }

    @Test
    void savedTestListsAreOn() {
        assertThat(shipped().getProperty("test-lists.enabled")).isEqualTo("true");
    }

    @Test
    void theTrailKeepsTheLastFiveHundredRuns() {
        Properties shipped = shipped();
        assertThat(shipped.getProperty("trail.enabled")).isEqualTo("true");
        assertThat(shipped.getProperty("trail.max-entries")).isEqualTo("500");
    }

    @Test
    void theVendorIsGivenTwoMinutesAndASlowCallIsMarkedAfterFifteenSeconds() {
        Properties shipped = shipped();
        // The vendor's inquiry took longer than 20 s while Postman waited for it.
        assertThat(shipped.getProperty("onyx.read-timeout-ms")).isEqualTo("120000");
        assertThat(shipped.getProperty("tracking.slow-threshold-ms")).isEqualTo("15000");
        // Unchanged: a refused or unreachable host still answers within seconds.
        assertThat(shipped.getProperty("onyx.connect-timeout-ms")).isEqualTo("5000");
        assertThat(shipped.getProperty("lookup.member.connect-timeout-ms")).isEqualTo("3000");
        assertThat(shipped.getProperty("lookup.member.read-timeout-ms")).isEqualTo("5000");
    }

    @Test
    void theListsSwitchBindsAndIsOnWhenNotStated() {
        assertThat(TestListProperties.defaults().enabled()).isTrue();
        assertThat(TestListProperties.of(java.util.Map.of("enabled", "false")).enabled()).isFalse();
    }
}
