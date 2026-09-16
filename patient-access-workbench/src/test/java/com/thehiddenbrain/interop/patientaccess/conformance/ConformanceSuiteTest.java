package com.thehiddenbrain.interop.patientaccess.conformance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The check catalog as Spring assembles it: every group populated, ids unique, metadata complete. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "paw.demo.enabled=false")
class ConformanceSuiteTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("paw-suite-test");
        registry.add("paw.data-dir", dir::toString);
    }

    @Autowired
    ConformanceSuite suite;

    @Test
    void catalogIsCompleteAndConsistent() {
        assertThat(suite.all().size()).as("number of checks").isGreaterThanOrEqualTo(80);

        Set<String> ids = new HashSet<>();
        for (Check c : suite.all()) {
            assertThat(ids.add(c.id())).as("duplicate id " + c.id()).isTrue();
            assertThat(c.title()).as(c.id() + " title").isNotBlank();
            assertThat(c.description()).as(c.id() + " description").isNotBlank();
            assertThat(c.citation()).as(c.id() + " citation").isNotBlank();
            assertThat(c.severity()).as(c.id() + " severity").isNotNull();
            assertThat(ConformanceSuite.GROUPS).as(c.id() + " group " + c.group()).containsKey(c.group());
            assertThat(c.id()).as(c.id() + " id starts with its group").startsWith(c.group() + ".");
        }

        for (ConformanceSuite.GroupInfo g : suite.groups()) {
            assertThat(g.checks()).as("checks in group " + g.key()).isGreaterThanOrEqualTo(1);
        }
        assertThat(suite.forGroups(java.util.List.of("eob", "priorauth", "coverage", "paging"))).allMatch(Check::needsPatient);
        assertThat(suite.forGroups(java.util.List.of("discovery", "smart", "errors", "formulary"))).noneMatch(Check::needsPatient);
    }
}
