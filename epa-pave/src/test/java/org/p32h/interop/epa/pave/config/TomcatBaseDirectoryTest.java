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

    /** A folder whose every use throws what it is given, the way a class missing at shutdown would surface. */
    private static Path failingPath(Throwable failure) {
        return (Path) java.lang.reflect.Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) {
                return "/placeholder/pave-server-1";
            }
            throw failure;
        });
    }

    /**
     * Stopped by Spring, as the tool stops: what a destroy method throws, Spring logs with its
     * message ("Invocation of destroy method failed ...: java.lang.RuntimeException: ...").
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aFailureWhileTheToolStopsIsOneEntryUnderAReferenceAndNeverItsMessage(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        java.lang.reflect.Field folder = TomcatBaseDirectory.class.getDeclaredField("directory");
        folder.setAccessible(true);
        for (Throwable failure : new Throwable[] {new ExceptionInInitializerError(new IllegalStateException("no plan for S123456789")),
            new RuntimeException("no plan for S123456789")}) {
            TomcatBaseDirectory bean = new TomcatBaseDirectory();
            folder.set(bean, failingPath(failure));
            try (org.springframework.context.support.GenericApplicationContext context = new org.springframework.context.support.GenericApplicationContext()) {
                context.registerBean("tomcatBaseDirectory", TomcatBaseDirectory.class, () -> bean);
                context.refresh();
            }
            assertThat(output.getAll()).contains("The embedded server's working folder /placeholder/pave-server-1 could not be removed, reference ")
                    .contains(": " + org.p32h.interop.epa.pave.service.error.Faults.cause(failure) + System.lineSeparator());
        }
        String all = output.getAll();
        assertThat(all.split("could not be removed, reference ", -1)).hasSize(3);
        assertThat(all).doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("Caused by:")
                .doesNotContain("Invocation of destroy method failed");
    }
}
