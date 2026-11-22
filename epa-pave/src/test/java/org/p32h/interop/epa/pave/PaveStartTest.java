package org.p32h.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * How a switch between mock and live starts PAVE again: with which arguments, what happens when
 * the start on the wanted mode fails, and that two switches never overlap. The start itself is
 * stood in for here; {@code web/ModeSwitchInPlaceTest} switches the real application.
 */
class PaveStartTest {

    @AfterEach
    void putBack() {
        PaveStart.reset();
    }

    @Test
    void everyFormOfTheActiveProfilesOnTheCommandLineIsTakenOutAndTheModeIsAddedLast() {
        String[] original = {"--server.port=8961", "--spring.profiles.active=live", "--config-store.directory=/data/store",
                "--spring.profiles.active", "--SPRING.PROFILES.ACTIVE=live", "--spring.profiles.active[0]=live",
                "--spring_profiles_active=live", "--Spring.Profiles.Active=mock,live", "--spring.profiles.act-ive=live", "--spring.profiles.include=extra",
                "--tracking.directory=/data/history", "live", "-Dspring.profiles.active=live"};
        assertThat(PaveStart.argumentsFor(original, "mock")).containsExactly("--server.port=8961", "--config-store.directory=/data/store",
                "--spring.profiles.include=extra", "--tracking.directory=/data/history", "live", "-Dspring.profiles.active=live",
                "--spring.profiles.active=mock");
        // A word that is not an option, and an option that only starts the same, are left as they are.
        assertThat(PaveStart.choosesProfiles("spring.profiles.active=live")).isFalse();
        assertThat(PaveStart.choosesProfiles("--spring.profiles.active-not=live")).isFalse();
        assertThat(PaveStart.choosesProfiles("--spring.profiles.default=live")).isFalse();
        assertThat(PaveStart.choosesProfiles(null)).isFalse();
    }

    @Test
    void withNoArgumentsTheModeIsTheOnlyOne() {
        assertThat(PaveStart.argumentsFor(new String[0], "live")).containsExactly("--spring.profiles.active=live");
        assertThat(PaveStart.argumentsFor(null, "mock")).containsExactly("--spring.profiles.active=mock");
    }

    /** A profile chosen as a system property or in SPRING_PROFILES_ACTIVE gives way to the one a switch adds, because the command line ranks above both. */
    @Test
    void theModeAddedOnTheCommandLineWinsOverASystemPropertyAndOverTheEnvironmentVariable() {
        assertThat(activeProfiles(Map.of("spring.profiles.active", "live"), Map.of(), new String[0])).containsExactly("live");
        assertThat(activeProfiles(Map.of(), Map.of("SPRING_PROFILES_ACTIVE", "live"), new String[0])).containsExactly("live");

        assertThat(activeProfiles(Map.of("spring.profiles.active", "live"), Map.of(),
                PaveStart.argumentsFor(new String[0], "mock"))).containsExactly("mock");
        assertThat(activeProfiles(Map.of(), Map.of("SPRING_PROFILES_ACTIVE", "live"),
                PaveStart.argumentsFor(new String[0], "mock"))).containsExactly("mock");
        assertThat(activeProfiles(Map.of("spring.profiles.active", "mock"), Map.of("SPRING_PROFILES_ACTIVE", "mock"),
                PaveStart.argumentsFor(new String[] {"--spring.profiles.active=mock"}, "live"))).containsExactly("live");
    }

    @Configuration(proxyBeanMethods = false)
    static class Nothing {
    }

    private static String[] activeProfiles(Map<String, Object> systemProperties, Map<String, Object> variables, String[] arguments) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        SpringApplication application = new SpringApplication(Nothing.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setEnvironment(environment);
        try (ConfigurableApplicationContext context = application.run(arguments)) {
            return context.getEnvironment().getActiveProfiles();
        }
    }

    @Test
    void theApplicationIsBuiltWithItsMainClassNamedSoTheBannerAndTheStartedLineStayTheSameOnAnyThread() throws Exception {
        Class<?>[] found = new Class<?>[1];
        Thread other = new Thread(() -> found[0] = PaveStart.application().getMainApplicationClass());
        other.start();
        other.join();
        assertThat(found[0]).isEqualTo(PaveApplication.class);
    }

    @Test
    void aStartThatFailsOnTheWantedModeStartsAgainOnTheModeItHad() throws Exception {
        List<String> tried = new ArrayList<>();
        AtomicInteger ended = new AtomicInteger();
        PaveStart.useForTests(args -> {
            String mode = args[args.length - 1];
            tried.add(String.join(" ", args));
            if (mode.endsWith("=mock")) {
                throw new IllegalStateException("a damaged scenario file");
            }
            return new StaticApplicationContext();
        }, ended::incrementAndGet);
        PaveStart.rememberForTests(new String[] {"--server.port=8962", "--spring.profiles.active=live"});
        StaticApplicationContext running = new StaticApplicationContext();
        running.refresh();
        String before = PaveStart.startedAt().toString();

        assertThat(PaveStart.claim("mock")).isTrue();
        Thread thread = PaveStart.restart(running, "live", "mock");
        assertThat(thread.isDaemon()).isFalse();
        thread.join(30_000);

        assertThat(running.isActive()).as("the running context is closed first").isFalse();
        assertThat(tried).containsExactly("--server.port=8962 --spring.profiles.active=mock", "--server.port=8962 --spring.profiles.active=live");
        assertThat(ended).hasValue(0);
        assertThat(PaveStart.switchingTo()).as("the claim is given back").isNull();
        assertThat(PaveStart.startedAt().toString()).isNotEqualTo(before);
        assertThat(PaveStart.current()).isNotNull();
    }

    @Test
    void whenNeitherModeStartsTheJvmIsLetEnd() throws Exception {
        AtomicInteger ended = new AtomicInteger();
        PaveStart.useForTests(args -> {
            throw new IllegalStateException("nothing starts");
        }, ended::incrementAndGet);
        PaveStart.rememberForTests(new String[0]);
        assertThat(PaveStart.claim("live")).isTrue();
        PaveStart.restart(new StaticApplicationContext(), "mock", "live").join(30_000);
        assertThat(ended).hasValue(1);
        assertThat(PaveStart.switchingTo()).isNull();
    }

    @Test
    void oneSwitchAtATimeAndARefusedClaimIsGivenBack() {
        assertThat(PaveStart.claim("live")).isTrue();
        assertThat(PaveStart.claim("live")).isFalse();
        assertThat(PaveStart.claim("mock")).isFalse();
        assertThat(PaveStart.switchingTo()).isEqualTo("live");
        // Another mode's release does not let go of this claim.
        PaveStart.release("mock");
        assertThat(PaveStart.switchingTo()).isEqualTo("live");
        PaveStart.release("live");
        assertThat(PaveStart.claim("mock")).isTrue();
    }

    @Test
    void aSwitchIsOnlyOfferedToAPaveStartedThroughItsMain() {
        assertThat(PaveStart.canSwitch()).isFalse();
        PaveStart.rememberForTests(new String[0]);
        assertThat(PaveStart.canSwitch()).isTrue();
    }
}
