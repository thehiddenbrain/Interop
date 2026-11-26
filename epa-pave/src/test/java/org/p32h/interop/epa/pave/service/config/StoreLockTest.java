package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.postman.PostmanFiles;

/**
 * R-32's lock: a second copy of PAVE on one configuration store folder is refused at its start, and
 * a lock file left by a copy that was killed refuses nothing. The other copy is a real second JVM,
 * because a lock of the system is held per process and a second store in this JVM shares it.
 */
class StoreLockTest {

    @TempDir
    Path directory;

    private Process child;

    @AfterEach
    void stopTheOtherCopy() throws InterruptedException {
        if (child != null && child.isAlive()) {
            child.destroyForcibly();
            child.waitFor(30, TimeUnit.SECONDS);
        }
    }

    private String refusal() {
        return "Another copy of PAVE is using the configuration store " + directory.toAbsolutePath().normalize() + ". Stop it, or give "
                + "this copy its own config-store.directory and tracking.directory.";
    }

    /** Starts another JVM that opens a store on the folder and answers with the marker line it reached. */
    private String otherCopy() throws Exception {
        child = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"),
                StoreLockHolder.class.getName(), directory.toString()).redirectErrorStream(true).start();
        BufferedReader lines = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        return CompletableFuture.supplyAsync(() -> {
            try {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    if (line.startsWith(StoreLockHolder.HELD) || line.startsWith(StoreLockHolder.REFUSED)) {
                        return line;
                    }
                }
                return "ended without a marker";
            } catch (IOException e) {
                return "unreadable";
            }
        }).get(90, TimeUnit.SECONDS);
    }

    @Test
    void aSecondCopyOnOneFolderIsRefusedWithR32sSentenceAndStartsOnceTheFirstHasStopped() throws Exception {
        assertThat(otherCopy()).isEqualTo(StoreLockHolder.HELD);

        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class).hasMessage(refusal());

        // The other copy stops the ordinary way: it closes its store and ends.
        child.getOutputStream().close();
        assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
        ConfigFixture started = ConfigFixture.mock(directory);
        started.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(started.store.snapshot().environments()).hasSize(1);
        started.store.close();
    }

    @Test
    void aLockFileLeftByACopyThatWasKilledRefusesNothing() throws Exception {
        assertThat(otherCopy()).isEqualTo(StoreLockHolder.HELD);
        child.destroyForcibly();
        assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.exists(directory.resolve(ConfigStore.LOCK_FILE))).isTrue();

        ConfigFixture started = ConfigFixture.mock(directory);
        started.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(started.store.snapshot().environments()).hasSize(1);
        started.store.close();
    }

    @Test
    void aLockFileNobodyHoldsRefusesNothing() throws IOException {
        Files.writeString(directory.resolve(ConfigStore.LOCK_FILE), "");

        ConfigFixture started = ConfigFixture.mock(directory);

        assertThat(started.store.snapshot().environments()).isEmpty();
        started.store.close();
    }

    @Test
    void storesOfOneJvmShareTheLockAndLetGoOfItWhenTheLastCloses() throws Exception {
        ConfigFixture first = ConfigFixture.mock(directory);
        ConfigFixture second = ConfigFixture.mock(directory);
        first.store.close();
        // Closing twice does not let go of the share the second store holds.
        first.store.close();

        assertThat(otherCopy()).isEqualTo(StoreLockHolder.REFUSED + refusal());
        assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();

        second.store.close();
        assertThat(otherCopy()).isEqualTo(StoreLockHolder.HELD);
    }

    /** The second copy: opens a store on the folder given, says whether it could, and holds it until its input ends. */
    static final class StoreLockHolder {

        static final String HELD = "PAVE-LOCK-HELD";
        static final String REFUSED = "PAVE-LOCK-REFUSED ";

        public static void main(String[] args) throws IOException {
            ConfigStore store;
            try {
                store = new ConfigStore(ConfigStoreProperties.of(Map.of("directory", args[0])), Clock.systemUTC());
            } catch (IllegalStateException e) {
                System.out.println(REFUSED + e.getMessage());
                System.out.flush();
                System.exit(3);
                return;
            }
            System.out.println(HELD);
            System.out.flush();
            while (System.in.read() >= 0) {
                // Held until the test closes this copy's input.
            }
            store.close();
        }
    }
}
