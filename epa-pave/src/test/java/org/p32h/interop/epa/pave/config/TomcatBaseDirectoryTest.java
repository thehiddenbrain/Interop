package org.p32h.interop.epa.pave.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.PaveApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * F7 of the member data audit: after a start and a stop, nothing of the embedded server is left
 * under the temporary directory. The application is started for real on a free port and stopped.
 */
class TomcatBaseDirectoryTest {

    @TempDir
    Path directory;

    @Test
    void theServersWorkingFolderIsUsedWhileRunningAndGoneOnceTheToolStops() {
        Path base;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PaveApplication.class).run("--server.port=0",
                "--config-store.directory=" + directory.resolve("config-store"), "--tracking.directory=" + directory.resolve("run-history"))) {
            base = context.getBean(TomcatBaseDirectory.class).directory();
            assertThat(base).isNotNull();
            assertThat(Files.isDirectory(base)).isTrue();
            assertThat(base.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath())).isTrue();
        }
        assertThat(Files.exists(base)).as("the server's working folder after the tool stopped").isFalse();
    }
}
