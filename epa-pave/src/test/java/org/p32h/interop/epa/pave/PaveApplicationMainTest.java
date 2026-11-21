package org.p32h.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.service.error.UncaughtFailures;

/**
 * The entry point of the jar and of a start from the IDE puts the net under every thread before
 * Spring starts. A start that is refused at once is enough to see it: the net is in place whether
 * or not the start then succeeds. The handler found is put back, so the rest of the test run still
 * sees the runtime's own printing.
 */
class PaveApplicationMainTest {

    @TempDir
    Path folder;

    @Test
    void theEntryPointPutsTheNetUnderEveryThreadBeforeSpringStarts() {
        Thread.UncaughtExceptionHandler found = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.setDefaultUncaughtExceptionHandler(null);
            // A start the startup checks refuse ends while the context is built, before any port is
            // opened, and the two folders keep the attempt out of the working directory.
            assertThatThrownBy(() -> PaveApplication.main(new String[] {"--spring.main.web-application-type=none",
                    "--spring.main.banner-mode=off", "--epa.mode=live", "--config-store.directory=" + folder.resolve("store"),
                    "--tracking.directory=" + folder.resolve("history")}))
                    .hasStackTraceContaining("startupChecks");
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(UncaughtFailures.HANDLER);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(found);
        }
    }
}
