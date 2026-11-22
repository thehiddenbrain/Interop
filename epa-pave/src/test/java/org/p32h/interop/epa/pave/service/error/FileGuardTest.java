package org.p32h.interop.epa.pave.service.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.service.config.ConfigStoreException;
import org.p32h.interop.epa.pave.testsupport.HandClock;
import org.slf4j.LoggerFactory;

/**
 * Whatever goes wrong while one file is read ends as a line about that file (R-07): a refusal keeps
 * its own words; any other failure, an Error included, gives the fixed line, a reference it is
 * logged under and the cause by types, and nothing escapes. S123456789 stands for member data in a
 * message, which neither the line nor the log may carry.
 */
class FileGuardTest {

    private static final String MEMBER = "S123456789";
    private static final String NAME = "env-" + MEMBER + ".json";

    private final Logger guardLog = (Logger) LoggerFactory.getLogger(FileGuard.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private ClassesOnDisk.Watch before;

    @BeforeEach
    void listen() {
        captured.start();
        guardLog.addAppender(captured);
        before = ClassesOnDisk.install(ClassesOnDisk.Watch.unknown());
    }

    @AfterEach
    void stopListening() {
        guardLog.detachAppender(captured);
        captured.stop();
        ClassesOnDisk.install(before);
    }

    private String logged() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : captured.list) {
            assertThat(event.getThrowableProxy()).as("the exception is never handed to the logger, which would print its message").isNull();
            all.append(event.getFormattedMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void aFileThatIsReadGivesItsValue() {
        FileGuard.Read<String> read = FileGuard.read(NAME, () -> "parsed");
        assertThat(read.ok()).isTrue();
        assertThat(read.value()).isEqualTo("parsed");
        assertThat(read.line()).isNull();
        assertThat(read.reference()).isNull();
        assertThat(read.cause()).isNull();
        assertThat(captured.list).isEmpty();
    }

    @Test
    void aRefusalIsItsOwnLineWithNoReferenceAndNothingLogged() {
        FileGuard.Read<String> read = FileGuard.read(NAME, () -> {
            throw new BadInputException("This is an OpenAPI file, not a Postman export.", "Export the collection from Postman.");
        });
        assertThat(read.ok()).isFalse();
        assertThat(read.value()).isNull();
        assertThat(read.line()).isEqualTo("This is an OpenAPI file, not a Postman export. Export the collection from Postman.");
        assertThat(read.reference()).isNull();
        assertThat(read.cause()).isNull();

        FileGuard.Read<String> plain = FileGuard.read(NAME, () -> {
            throw new BadInputException("The items of this collection are text, not requests.", null);
        });
        assertThat(plain.line()).isEqualTo("The items of this collection are text, not requests.");

        FileGuard.Read<String> store = FileGuard.read(NAME, () -> {
            throw ConfigStoreException.ofThisComputer("The disk that holds the store is full. Free some space, then try again. "
                    + "Nothing was changed.", new java.io.IOException(MEMBER));
        });
        assertThat(store.line()).isEqualTo("The disk that holds the store is full. Free some space, then try again. Nothing was changed.");
        assertThat(store.reference()).isNull();
        assertThat(captured.list).isEmpty();
    }

    /** Each failure that is not a refusal, with a message that names the placeholder member. */
    private static List<Supplier<String>> failures() {
        return List.of(
                () -> {
                    throw new IllegalStateException("No coverage for member " + MEMBER);
                },
                () -> {
                    throw new StackOverflowError();
                },
                () -> {
                    throw new OutOfMemoryError("Java heap space");
                },
                () -> {
                    throw new NoSuchMethodError("'java.lang.String org.p32h.interop.epa.pave.model.Texts.withoutLeading(java.lang.String)'");
                },
                () -> {
                    // A store failure PAVE could not tell is a failure of this tool, not a refusal.
                    throw new ConfigStoreException("The file could not be written.", new java.io.IOException(MEMBER));
                },
                () -> {
                    throw new Error("Could not read member " + MEMBER);
                });
    }

    @Test
    void anyOtherFailureGivesTheLineAReferenceAndACauseOfTypesOnlyAndNothingEscapes() {
        String[] causes = {
            "java.lang.IllegalStateException",
            "java.lang.StackOverflowError",
            "java.lang.OutOfMemoryError; java.lang.OutOfMemoryError says: Java heap space",
            "java.lang.NoSuchMethodError; java.lang.NoSuchMethodError says: "
                    + "'java.lang.String org.p32h.interop.epa.pave.model.Texts.withoutLeading(java.lang.String)'",
            "org.p32h.interop.epa.pave.service.config.ConfigStoreException <- java.io.IOException",
            "java.lang.Error"};
        List<Supplier<String>> failures = failures();
        for (int i = 0; i < failures.size(); i++) {
            captured.list.clear();
            FileGuard.Read<String> read = FileGuard.read(NAME, failures.get(i));
            assertThat(read.ok()).isFalse();
            assertThat(read.value()).isNull();
            assertThat(read.cause()).isEqualTo(causes[i]);
            assertThat(read.reference()).matches("[0-9a-f-]{36}");
            if (causes[i].startsWith("java.lang.OutOfMemoryError")) {
                // The heap is what helps, and the setting is named (R-36).
                assertThat(read.line()).isEqualTo("PAVE could not read this file. Type the values instead. Start PAVE with -Xmx512m or more.");
            } else {
                assertThat(read.line()).isEqualTo("PAVE could not read this file. Type the values instead.");
            }
            String log = logged();
            assertThat(captured.list).hasSize(1);
            assertThat(log).startsWith("A file could not be read, reference " + read.reference() + ": " + causes[i]);
            assertThat(log).contains("    at ");
            assertThat(log + read.line() + read.cause()).doesNotContain(MEMBER);
            assertThat(read.toString()).isEqualTo("Read[failed]").doesNotContain(MEMBER);
        }
    }

    @Test
    void aClassThatCannotBeLinkedAfterARebuildSaysToStartPaveAgain(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        Path type = classes.resolve("A.class");
        Files.createDirectories(classes);
        Files.writeString(type, "x");
        Files.setLastModifiedTime(type, FileTime.from(Instant.parse("2026-11-22T05:00:00Z")));
        HandClock clock = new HandClock(Instant.parse("2026-11-22T06:00:00Z"));
        ClassesOnDisk.install(ClassesOnDisk.Watch.ofFolder(classes, null, clock));

        Supplier<String> missing = () -> {
            throw new NoClassDefFoundError("org/p32h/interop/epa/pave/postman/PostmanParser");
        };
        // Not changed on disk: a missing class is a defect of the build, and the reference finds it.
        assertThat(FileGuard.read(NAME, missing).line()).isEqualTo("PAVE could not read this file. Type the values instead.");

        Files.setLastModifiedTime(type, FileTime.from(Instant.parse("2026-11-22T06:00:05Z")));
        clock.advance(Duration.ofSeconds(11));
        FileGuard.Read<String> read = FileGuard.read(NAME, missing);
        assertThat(read.line()).isEqualTo("PAVE could not read this file. Type the values instead. PAVE's classes changed on disk while it was "
                + "running, as the IDE does when it rebuilds the project. Stop PAVE and start it again.");
        assertThat(read.reference()).isNotNull();
        assertThat(read.cause()).isEqualTo("java.lang.NoClassDefFoundError; java.lang.NoClassDefFoundError says: "
                + "org/p32h/interop/epa/pave/postman/PostmanParser");
    }

    @Test
    void aRefusalHoldsTheGuardrailsWordsAsTheyAre() {
        FileGuard.Read<String> read = FileGuard.read(NAME, () -> {
            throw new ProdWriteBlockedException(null, null, "Writes to production are locked.", "Unlock writes first.");
        });
        assertThat(read.line()).isEqualTo("Writes to production are locked. Unlock writes first.");
        assertThat(read.reference()).isNull();
    }
}
