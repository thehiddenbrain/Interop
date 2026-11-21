package org.p32h.interop.epa.pave.service.error;

import static org.assertj.core.api.Assertions.assertThat;

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
}
