package com.thehiddenbrain.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;

import com.thehiddenbrain.interop.epa.pave.config.AppProperties;
import com.thehiddenbrain.interop.epa.pave.config.CodelistProperties;
import com.thehiddenbrain.interop.epa.pave.config.ConfigStoreProperties;
import com.thehiddenbrain.interop.epa.pave.config.EpaProperties;
import com.thehiddenbrain.interop.epa.pave.config.LookupProperties;
import com.thehiddenbrain.interop.epa.pave.config.OnyxProperties;
import com.thehiddenbrain.interop.epa.pave.config.TrackingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Starts the application exactly as a tester would on a fresh checkout: no profile, no arguments,
 * no edited file. That has to produce the mock profile with every property bound.
 */
@SpringBootTest
class PaveApplicationTest {

    @Autowired Environment environment;
    @Autowired EpaProperties epa;
    @Autowired ConfigStoreProperties configStore;
    @Autowired TrackingProperties tracking;
    @Autowired CodelistProperties codelist;
    @Autowired AppProperties app;
    @Autowired OnyxProperties onyx;
    @Autowired LookupProperties lookup;
    @Autowired BuildProperties build;

    @Test
    void mockIsTheDefaultProfile() {
        assertThat(environment.getActiveProfiles()).isEmpty();
        assertThat(environment.matchesProfiles("mock")).isTrue();
        assertThat(environment.matchesProfiles("live")).isFalse();
        assertThat(epa.mode()).isEqualTo("mock");
    }

    @Test
    void everyPropertyBindsFromApplicationYml() {
        assertThat(epa.name()).isEqualTo("PAVE");
        assertThat(epa.target()).isEqualTo("onyx");
        assertThat(environment.getProperty("server.port")).isEqualTo("8057");
        assertThat(environment.getProperty("server.servlet.session.cookie.name")).isEqualTo("PAVESESSION");
        assertThat(configStore.directory()).isEqualTo("./config-store");
        assertThat(configStore.allowProdWrites()).isFalse();
        assertThat(tracking.enabled()).isTrue();
        assertThat(tracking.directory()).isEqualTo("./run-history");
        assertThat(tracking.recordNpi()).isTrue();
        assertThat(codelist.format()).isEqualTo("auto");
        assertThat(codelist.columns().code()).isNotEmpty();
        assertThat(app.autocompleteLimit()).isEqualTo(25);
        assertThat(onyx.readTimeoutMs()).isEqualTo(15000);
        assertThat(onyx.pas().attachmentMode()).isEqualTo("fhir");
        assertThat(onyx.auth().enabled()).isFalse();
        assertThat(lookup.member().readTimeoutMs()).isEqualTo(5000);
        assertThat(lookup.provider().enabled()).isTrue();
    }

    @Test
    void theMockProfileRecordsMemberIdsBecauseTheyAreSynthetic() {
        assertThat(tracking.recordMemberId()).isTrue();
    }

    @Test
    void theVersionOnScreenIsTheVersionThatWasBuilt() {
        // epa.version is typed by hand in application.yml; the build version is in build.gradle.
        assertThat(epa.version()).isEqualTo(build.getVersion());
        assertThat(build.getArtifact()).isEqualTo("epa-pave");
        assertThat(build.getGroup()).isEqualTo("com.thehiddenbrain.interop.epa");
    }
}
