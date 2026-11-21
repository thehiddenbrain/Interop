package org.p32h.interop.epa.pave.service.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The net under every thread: a throwable nothing caught leaves one entry under a reference, and
 * the runtime prints nothing of its own. Each test puts back the handler it found, so the rest of
 * the test run still sees the runtime's own printing and every other guard is proved on its own.
 */
@ExtendWith(OutputCaptureExtension.class)
class UncaughtFailuresTest {

    private static final Pattern ENTRY = Pattern.compile(
            "A thread of this tool ended on a failure that nothing caught, thread (\\S+), reference ([0-9a-f-]{36}): ");

    private Thread.UncaughtExceptionHandler found;

    @BeforeEach
    void keep() {
        found = Thread.getDefaultUncaughtExceptionHandler();
    }

    @AfterEach
    void putBack() {
        Thread.setDefaultUncaughtExceptionHandler(found);
    }

    private static void runAndWait(String name, Runnable task) throws InterruptedException {
        // A thread with no handler of its own, as a library or the runtime would start one.
        Thread thread = new Thread(task, name);
        thread.start();
        thread.join(20_000);
        assertThat(thread.isAlive()).isFalse();
    }

    @Test
    void anErrorNothingCaughtIsOneEntryUnderAReferenceAndNeverItsMessage(CapturedOutput output) throws InterruptedException {
        UncaughtFailures.install();
        runAndWait("probe-error", () -> {
            throw new ExceptionInInitializerError(new IllegalStateException("no plan for S123456789"));
        });

        String all = output.getAll();
        assertThat(all).doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("Caused by:")
                .doesNotContain("Exception in thread");
        Matcher entry = ENTRY.matcher(all);
        assertThat(entry.find()).as(all).isTrue();
        assertThat(entry.group(1)).isEqualTo("probe-error");
        assertThat(all).contains(entry.group(2) + ": java.lang.ExceptionInInitializerError <- java.lang.IllegalStateException");
        assertThat(entry.find()).as("one entry only").isFalse();
    }

    @Test
    void aRuntimeExceptionNothingCaughtIsOneEntryUnderAReferenceAndNeverItsMessage(CapturedOutput output) throws InterruptedException {
        UncaughtFailures.install();
        runAndWait("probe-runtime", () -> {
            throw new RuntimeException("no plan for S123456789");
        });

        String all = output.getAll();
        assertThat(all).doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("Caused by:")
                .doesNotContain("Exception in thread");
        Matcher entry = ENTRY.matcher(all);
        assertThat(entry.find()).as(all).isTrue();
        assertThat(entry.group(1)).isEqualTo("probe-runtime");
        assertThat(all).contains(entry.group(2) + ": java.lang.RuntimeException" + System.lineSeparator());
        assertThat(entry.find()).as("one entry only").isFalse();
    }

    @Test
    void theNetIsTheProcessDefaultOnceInstalled() {
        UncaughtFailures.install();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(UncaughtFailures.HANDLER);
    }

    /** A throwable that breaks whoever describes it: no frames to read, or a cause that throws when asked for. */
    private static Throwable[] unreadable() {
        return new Throwable[] {new IllegalStateException("no plan for S123456789") {
            @Override
            public StackTraceElement[] getStackTrace() {
                return null;
            }
        }, new IllegalStateException("no plan for S123456789") {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("no cause for S123456789");
            }
        }};
    }

    /**
     * The net's own failure, called directly: if describing the throwable fails, what fails must
     * not reach the caller.
     */
    @Test
    void aThrowableThatCannotBeDescribedEscapesNothingFromTheHandler(CapturedOutput output) {
        for (Throwable failure : unreadable()) {
            assertThatCode(() -> UncaughtFailures.HANDLER.uncaughtException(Thread.currentThread(), failure)).doesNotThrowAnyException();
        }
        assertThat(output.getAll()).doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("no cause");
    }

    /**
     * The process the probe runs in: the net installed as the jar installs it, two threads ending on
     * a throwable that cannot be described, then one ending on a plain one, so the output shows the
     * net was there and working.
     */
    static final class UnreadableProbe {
        public static void main(String[] args) throws InterruptedException {
            UncaughtFailures.install();
            for (Throwable failure : unreadable()) {
                runAndWait("probe-unreadable", () -> {
                    throw (RuntimeException) failure;
                });
            }
            runAndWait("probe-after", () -> {
                throw new IllegalStateException("no plan for S123456789");
            });
        }
    }

    /**
     * The same on a thread, in a process of its own: a handler that throws makes the virtual machine
     * print a line of its own ("Exception: ... thrown from the UncaughtExceptionHandler in thread"),
     * to the process's standard error beneath the reach of an output capture. So the probe runs as
     * a child process and all it prints is read.
     */
    @Test
    void aThrowableThatCannotBeDescribedOnAThreadLeavesNothingPrinted() throws Exception {
        Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp",
                System.getProperty("java.class.path"), UnreadableProbe.class.getName()).redirectErrorStream(true).start();
        String all = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).as(all).isZero();

        assertThat(all).doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("no cause")
                .doesNotContain("Caused by:").doesNotContain("Exception in thread").doesNotContain("UncaughtExceptionHandler")
                .doesNotContain("probe-unreadable");
        Matcher entry = ENTRY.matcher(all);
        assertThat(entry.find()).as(all).isTrue();
        assertThat(entry.group(1)).isEqualTo("probe-after");
        assertThat(entry.find()).as("one entry only").isFalse();
    }
}
