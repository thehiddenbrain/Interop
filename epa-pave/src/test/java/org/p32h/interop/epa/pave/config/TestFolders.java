package org.p32h.interop.epa.pave.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Gives every test that starts the application folders of its own for the configuration store and
 * the run history, so that a build writes nothing into the project directory (F-BUILD-WRITES-TREE
 * of the honesty audit).
 *
 * <p>The defaults in {@code application.yml} are relative to where the tool is started, and the
 * tests run in the project directory, so a test that starts the application without folders of its
 * own would leave {@code config-store/} and {@code run-history/} there. Registered for every test
 * class through {@code META-INF/services} and {@code junit-platform.properties}, this sets the two
 * folders once, as system properties, to a temporary folder that is removed when the tests end. A
 * test that names folders of its own still gets them: a test property source and a command line
 * argument both take precedence over a system property.
 */
public final class TestFolders implements BeforeAllCallback {

    /** The system properties set, the same names the application binds. */
    static final String CONFIG_STORE = "config-store.directory";
    static final String TRACKING = "tracking.directory";

    private static Path root;

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).getOrComputeIfAbsent(TestFolders.class, key -> open(),
                Removal.class);
    }

    private static synchronized Removal open() {
        if (root == null) {
            try {
                root = Files.createTempDirectory("pave-test-folders-");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.setProperty(CONFIG_STORE, root.resolve("config-store").toString());
            System.setProperty(TRACKING, root.resolve("run-history").toString());
        }
        return new Removal(root);
    }

    /** Removes the folder when every test has run. */
    record Removal(Path folder) implements ExtensionContext.Store.CloseableResource {

        @Override
        public void close() {
            System.clearProperty(CONFIG_STORE);
            System.clearProperty(TRACKING);
            try (Stream<Path> paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException | UncheckedIOException e) {
                // A temporary folder the system removes in its own time; nothing of it is in the project.
            }
        }
    }
}
