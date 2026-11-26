package org.p32h.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application starts on the live profile without any bean that only the mock profile uses.
 * A mock bean left on both profiles is a mock answer one wiring mistake away from a live run, and
 * a bean that needs one would stop the live start (the chain review's F15).
 */
@SpringBootTest
@ActiveProfiles("live")
class LiveProfileContextTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("config-store.directory", () -> directory.resolve("config-store").toString());
        registry.add("tracking.directory", () -> directory.resolve("run-history").toString());
    }

    @Autowired ApplicationContext context;
    @Autowired EpaProperties epa;

    @Test
    void theLiveContextStartsWithNoBeanOnlyTheMockProfileUses() {
        assertThat(epa.live()).isTrue();
        assertThat(context.getBeansOfType(MockExchanges.class)).isEmpty();
        List<String> mockBeans = Arrays.stream(context.getBeanDefinitionNames()).filter(name -> {
            Class<?> type = context.getType(name);
            String simple = type == null ? "" : type.getSimpleName();
            // The mock clients, their shared exchange builder and the lookup fixtures.
            return simple.startsWith("Mock") || simple.equals("LookupFixtures");
        }).toList();
        assertThat(mockBeans).isEmpty();
        // And each external system has its live implementation in their place.
        assertThat(Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(java.util.Objects::nonNull)
                .map(Class::getSimpleName).map(name -> name.contains("$$") ? name.substring(0, name.indexOf("$$")) : name))
                .contains("HttpOnyxCrdClient", "HttpOnyxDtrClient", "HttpOnyxPasClient", "HttpSubscriptionClient",
                "HttpMemberLookupClient", "HttpProviderLookupClient", "HttpRawRequestClient");
    }
}
