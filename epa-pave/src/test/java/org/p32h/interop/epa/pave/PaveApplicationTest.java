package org.p32h.interop.epa.pave;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.DeserializationFeature;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.p32h.interop.epa.pave.testsupport.GradleTestTask;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
    // Only the Gradle build writes META-INF/build-info.properties. Taken through a provider so that
    // the context, and every test here but the version test, also runs in an IDE that has no such file.
    @Autowired ObjectProvider<BuildProperties> buildProperties;
    @Autowired JsonMapper mapper;

    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void nothingFromTheTestSourcesIsPartOfTheApplication() {
        // The test classes are on the path when this context is built, under the package the
        // application scans. A stand-in picked up from there would make this test pass on wiring
        // the real application does not have.
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            String className = type == null ? "" : type.getName();
            assertThat(className).as(name).doesNotContain(".devharness.").doesNotContain(".testsupport.");
        }
    }

    @Test
    void mockIsTheDefaultProfile() {
        assertThat(environment.getActiveProfiles()).isEmpty();
        assertThat(environment.matchesProfiles("mock")).isTrue();
        assertThat(environment.matchesProfiles("live")).isFalse();
        assertThat(epa.mode()).isEqualTo("mock");
    }

    /** The value application.yml itself gives a property, whatever a system property says. */
    private String fromApplicationYml(String name) {
        for (org.springframework.core.env.PropertySource<?> source : ((org.springframework.core.env.ConfigurableEnvironment) environment)
                .getPropertySources()) {
            if (source instanceof OriginTrackedMapPropertySource && source.getName().contains("application.yml")
                    && source.containsProperty(name)) {
                return String.valueOf(source.getProperty(name));
            }
        }
        return null;
    }

    @Test
    void everyPropertyBindsFromApplicationYml() {
        assertThat(epa.name()).isEqualTo("PAVE");
        assertThat(epa.target()).isEqualTo("onyx");
        assertThat(environment.getProperty("server.port")).isEqualTo("8057");
        assertThat(environment.getProperty("server.servlet.session.cookie.name")).isEqualTo("PAVESESSION");
        // The two folders are given to every test by config/TestFolders, so that a build writes
        // nothing into the project directory. What application.yml itself says is read from it.
        assertThat(fromApplicationYml("config-store.directory")).isEqualTo("./config-store");
        assertThat(configStore.directory()).isEqualTo(System.getProperty("config-store.directory"));
        assertThat(configStore.allowProdWrites()).isFalse();
        assertThat(tracking.enabled()).isTrue();
        assertThat(fromApplicationYml("tracking.directory")).isEqualTo("./run-history");
        assertThat(tracking.directory()).isEqualTo(System.getProperty("tracking.directory"));
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
    void everyKeyInTheConfigurationFilesIsOneAPropertiesRecordReads() {
        // The assertions above would also pass on the defaults, so they cannot tell a misspelt key
        // from a correct one. This binds each prefix again and fails on any key under it that no
        // record component takes. Only the application's own files are looked at, so a variable in
        // the operating system environment of whoever runs the build cannot fail it.
        Map<String, Class<?>> records = Map.of("epa", EpaProperties.class, "config-store", ConfigStoreProperties.class,
                "tracking", TrackingProperties.class, "codelist", CodelistProperties.class, "app", AppProperties.class,
                "onyx", OnyxProperties.class, "lookup", LookupProperties.class);
        NoUnboundElementsBindHandler strict = new NoUnboundElementsBindHandler(BindHandler.DEFAULT,
                source -> source.getUnderlyingSource() instanceof OriginTrackedMapPropertySource);
        records.forEach((prefix, type) -> assertThatCode(() -> Binder.get(environment).bind(prefix, Bindable.of(type), strict))
                .as("keys under " + prefix).doesNotThrowAnyException());
    }

    @Test
    void requestBodiesAreReadStrictlyByTheMapperTheApplicationUses() {
        // The framework switches this off by default. A body with a misnamed property would then
        // bind to nothing, and the run it describes would mean something else.
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(mapper.isEnabled(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)).isTrue();
        assertThat(TestMvc.mapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        // A key given twice in one object is refused, never read as its last value.
        assertThat(mapper.isEnabled(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)).isTrue();
        assertThat(TestMvc.mapper().isEnabled(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)).isTrue();
    }

    @Test
    void theMockProfileRecordsMemberIdsBecauseTheyAreSynthetic() {
        assertThat(tracking.recordMemberId()).isTrue();
    }

    @Test
    void theVersionOnScreenIsTheVersionThatWasBuilt() {
        GradleTestTask.assumeRunning("META-INF/build-info.properties, written only by the Gradle build");
        BuildProperties build = buildProperties.getIfAvailable();
        assertThat(build).as("the build information from META-INF/build-info.properties, written by the bootBuildInfo task "
                + "that springBoot.buildInfo in build.gradle adds").isNotNull();
        // epa.version is typed by hand in application.yml; the build version is in build.gradle.
        assertThat(epa.version()).isEqualTo(build.getVersion());
        assertThat(build.getArtifact()).isEqualTo("epa-pave");
        assertThat(build.getGroup()).isEqualTo("org.p32h.interop.epa");
    }
}
