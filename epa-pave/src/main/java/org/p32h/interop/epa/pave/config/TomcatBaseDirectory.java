package org.p32h.interop.epa.pave.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Gives the embedded server a base directory of its own under the temporary directory and removes
 * it, with everything in it, when the tool stops.
 *
 * <p>Left to itself the server makes a folder for every start and leaves it behind, because the
 * folder is not empty when the JVM tries to remove it. A tester should find nothing of the tool's
 * outside the folder it was started in once it has stopped, and a third folder beside the
 * configuration store and the run history would be one more thing to know about. A process that is
 * killed outright cannot clean up; that leaves one empty folder, as before.
 */
@Component
public class TomcatBaseDirectory implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TomcatBaseDirectory.class);

    private volatile Path directory;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        try {
            directory = Files.createTempDirectory("pave-server-");
        } catch (IOException e) {
            throw new UncheckedIOException("The embedded server's working folder could not be made under the temporary directory", e);
        }
        factory.setBaseDirectory(directory.toFile());
    }

    /** The base directory given to the server, or null before the server is made. */
    public Path directory() {
        return directory;
    }

    /**
     * Never throws: Spring logs what a destroy method throws with its message, and this runs while
     * the tool stops, when a class missing from a jar replaced under a running tool is first found.
     */
    @Override
    public void destroy() {
        Path made = directory;
        try {
            if (made == null || !Files.exists(made)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(made)) {
                for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(each);
                }
            }
        } catch (IOException | RuntimeException | Error e) {
            // The path, a reference and the exception types and frames only. The folder holds no
            // member data, only the server's scratch space, but a message is never given.
            log.warn("The embedded server's working folder {} could not be removed, reference {}: {}", made, UUID.randomUUID(),
                    Faults.describe(e));
        }
    }
}
