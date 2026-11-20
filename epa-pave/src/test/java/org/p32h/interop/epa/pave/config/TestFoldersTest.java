package org.p32h.interop.epa.pave.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * F-BUILD-WRITES-TREE of the honesty audit: a test that starts the application with no folders of
 * its own writes into a temporary folder, never into the project directory the build runs in.
 */
@SpringBootTest
class TestFoldersTest {

    @Autowired ConfigStoreProperties configStore;
    @Autowired TrackingProperties tracking;

    @Test
    void theApplicationStartedByATestWritesOutsideTheProjectDirectory() {
        Path project = Path.of("").toAbsolutePath().normalize();
        for (String directory : new String[] {configStore.directory(), tracking.directory()}) {
            Path used = Path.of(directory).toAbsolutePath().normalize();
            assertThat(used.startsWith(project)).as(directory).isFalse();
            assertThat(used.getParent().getFileName().toString()).startsWith("pave-test-folders-");
        }
        // The store has been created where it was pointed, so it is that folder that would hold what a test leaves.
        assertThat(Files.isDirectory(Path.of(configStore.directory()))).isTrue();
    }
}
