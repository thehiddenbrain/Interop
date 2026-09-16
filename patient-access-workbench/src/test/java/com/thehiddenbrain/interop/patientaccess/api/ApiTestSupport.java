package com.thehiddenbrain.interop.patientaccess.api;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shared Spring context for the MockMvc tests: demo server off, data on a temp folder created once per
 * JVM. One folder for all subclasses keeps the context cached across them (a per-class {@code @TempDir}
 * would be deleted after the first class while the cached context keeps writing to it).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"paw.demo.enabled=false"})
public abstract class ApiTestSupport {

    static final Path DATA_DIR = createDataDir();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("paw.data-dir", DATA_DIR::toString);
    }

    private static Path createDataDir() {
        try {
            Path dir = Files.createTempDirectory("paw-api-test-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // best effort cleanup of the JVM temp folder
                }
            }));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A minimal valid environment body; {@code name} must be unique per test. */
    protected static String environmentJson(String name) {
        return "{\"name\":\"" + name + "\",\"tier\":\"UAT\",\"fhirBaseUrl\":\"https://fhir.example.org/r4\","
                + "\"auth\":{\"mode\":\"CLIENT_CREDENTIALS\",\"discoverEndpoints\":false,\"tokenEndpoint\":\"https://as.example.org/token\","
                + "\"clientId\":\"client-1\",\"clientSecret\":\"client-secret-value-ABCD\"},"
                + "\"headers\":[{\"name\":\"X-Api-Key\",\"value\":\"api-key-value-1234\",\"secret\":true}]}";
    }
}
