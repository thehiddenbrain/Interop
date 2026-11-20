package org.p32h.interop.epa.pave.testsupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.p32h.interop.epa.pave.model.Texts;

/**
 * For the few tests that check the Gradle build's own wiring: values the test task of build.gradle
 * hands over, or a file only the Gradle build writes. An IDE that runs them with its own JUnit
 * launcher has neither, and a failure there would say nothing about the code, so there they are
 * skipped with a sentence that says why. Under the Gradle test task they run, and a missing input
 * fails them as before: that failure is the gate.
 */
public final class GradleTestTask {

    // Set by Gradle's own test worker in every test JVM it starts, so no line of build.gradle can
    // drop it by mistake. Seen as org.gradle.test.worker=1 under the wrapper (Gradle 9.5). An IDE's
    // JUnit launcher does not set it.
    static final String WORKER_PROPERTY = "org.gradle.test.worker";

    private GradleTestTask() {
    }

    public static boolean running() {
        return Texts.present(System.getProperty(WORKER_PROPERTY));
    }

    /** Skips the calling test outside the Gradle test task, naming what only Gradle supplies. */
    public static void assumeRunning(String whatOnlyGradleSupplies) {
        assumeTrue(running(), () -> "This test checks the Gradle build's own wiring (" + whatOnlyGradleSupplies
                + ") and runs only under the Gradle test task, for example gradlew build. This JVM was not started"
                + " by that task (no system property " + WORKER_PROPERTY + "), so the test is skipped here, not passed.");
    }
}
