package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.testsupport.HandClock;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A failure of this tool names its cause where the person who meets it looks: on the first line
 * of the log entry, and in the answer to the browser. A fault on an operator's workstation went
 * unfound because the cause stood further down a log entry than the part that was pasted.
 *
 * <p>For each throwable: the status and the body, the first line of the log entry, the details
 * that may be given, and that neither the log nor the body holds the placeholder member ID that
 * stands for member data in a message, nor does the body hold a stack frame.
 */
class InternalErrorCauseTest {

    private static final String MEMBER = "S123456789";
    private static final String MISSING = "org/p32h/interop/epa/pave/service/config/PostmanEnvironmentReader";
    private static final String SERVLET = "jakarta.servlet.ServletException";

    @RestController
    static class FailingController {

        @GetMapping("/fail/{what}")
        public String fail(@PathVariable String what) throws Exception {
            switch (what) {
                case "missing-class" -> throw new NoClassDefFoundError(MISSING);
                case "missing-class-separators" -> throw new NoClassDefFoundError(MISSING + "\u2028second\u0085third\u2029fourth");
                case "not-compiled" -> throw new Error("Unresolved compilation problem: \n\tPostmanEnvironmentReader cannot be resolved to a type\n");
                case "stack" -> throw new StackOverflowError();
                case "heap" -> throw new OutOfMemoryError("Java heap space");
                case "no-method" -> throw new NoSuchMethodError("'java.lang.String org.p32h.interop.epa.pave.model.Texts.withoutLeading(java.lang.String)'");
                case "plain-error" -> throw new Error("Could not read member " + MEMBER);
                case "state" -> throw new IllegalStateException("No coverage for member " + MEMBER);
                case "initializer" -> throw new ExceptionInInitializerError(new RuntimeException("Bad sample for member " + MEMBER));
                // The runtime builds one from text when it names what an earlier initializer threw.
                case "initializer-text" -> throw new ExceptionInInitializerError(
                        "Exception java.lang.RuntimeException: Bad sample for member " + MEMBER + " [in thread \"main\"]");
                case "wrapped" -> {
                    // What DispatcherServlet.doDispatch does with an Error a handler throws: the
                    // wrapper's message quotes the Error's own text.
                    Error error = new Error("Lookup of " + MEMBER + " failed");
                    throw new ServletException("Handler dispatch failed: " + error, error);
                }
                default -> throw new IllegalArgumentException(what);
            }
        }
    }

    private final MockMvc mvc = TestMvc.standalone(new FailingController());
    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    private ClassesOnDisk.Watch before;

    @BeforeEach
    void listen() {
        captured.start();
        handlerLog.addAppender(captured);
        // Nothing changed on disk unless a test says so: the answer then depends on nothing outside the test.
        before = ClassesOnDisk.install(ClassesOnDisk.Watch.unknown());
    }

    @AfterEach
    void stopListening() {
        handlerLog.detachAppender(captured);
        captured.stop();
        ClassesOnDisk.install(before);
    }

    /** One failure as the tester and the log saw it. */
    private record Seen(int status, String body, JsonNode json, String logEntry) {

        String firstLogLine() {
            return logEntry.lines().findFirst().orElse("");
        }

        String reference() {
            return JsonSafe.text(json, "reference");
        }

        String cause() {
            return JsonSafe.text(json, "cause");
        }
    }

    private Seen fail(String what) throws Exception {
        captured.list.clear();
        MockHttpServletResponse response = mvc.perform(get("/fail/" + what)).andReturn().getResponse();
        String body = response.getContentAsString();
        List<ILoggingEvent> events = List.copyOf(captured.list);
        assertThat(events).as("one log entry for " + what).hasSize(1);
        assertThat(events.get(0).getThrowableProxy()).as("the exception itself is not handed to the logger, which would print its message")
                .isNull();
        return new Seen(response.getStatus(), body, JsonSafe.parse(body).node(), events.get(0).getFormattedMessage());
    }

    /** What holds for every failure of this tool, whatever threw. */
    private static void commonTo(Seen seen, String expectedCause) {
        commonTo(seen, expectedCause, "Copy the reference and the cause below with the button and report "
                + "them with what you were doing. The server log holds the rest under the same reference.");
    }

    private static void commonTo(Seen seen, String expectedCause, String expectedAction) {
        assertThat(seen.status()).isEqualTo(500);
        assertThat(JsonSafe.text(seen.json(), "kind")).isEqualTo("INTERNAL");
        assertThat(JsonSafe.text(seen.json(), "title")).isEqualTo("This tool hit an internal error and could not complete the request.");
        assertThat(JsonSafe.text(seen.json(), "action")).isEqualTo(expectedAction);
        assertThat(seen.reference()).isNotBlank();
        assertThat(seen.cause()).isEqualTo(expectedCause);
        // The first line is the one an operator pastes: reference and cause, whole.
        assertThat(seen.firstLogLine()).isEqualTo("Unhandled exception, reference " + seen.reference() + ": " + expectedCause);
        // The frames still follow in the log, and never reach the browser.
        assertThat(seen.logEntry()).contains(System.lineSeparator() + "    at ");
        assertThat(seen.body()).doesNotContain("\tat ").doesNotContain("    at ").doesNotContain(".java:");
        assertThat(seen.body()).doesNotContain(MEMBER);
        assertThat(seen.logEntry()).doesNotContain(MEMBER);
    }

    @Test
    void aMissingClassIsNamedOnTheFirstLineAndOnScreen() throws Exception {
        Seen seen = fail("missing-class");
        // Spring wraps an Error a handler throws, as on the operator's workstation.
        commonTo(seen, SERVLET + " <- java.lang.NoClassDefFoundError; java.lang.NoClassDefFoundError says: " + MISSING);
        assertThat(seen.logEntry()).contains("org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:");
    }

    @Test
    void aDetailWithUnicodeLineBreaksStaysOnTheFirstLineOfTheLogEntry() throws Exception {
        Seen seen = fail("missing-class-separators");
        String expected = SERVLET + " <- java.lang.NoClassDefFoundError; java.lang.NoClassDefFoundError says: " + MISSING + " second third fourth";
        commonTo(seen, expected);
        // A log viewer breaks lines at these too, so the first line is read the way \R reads it.
        assertThat(seen.logEntry().split("\\R")[0]).isEqualTo("Unhandled exception, reference " + seen.reference() + ": " + expected);
    }

    @Test
    void aClassTheIdeCouldNotCompileIsNamedWithTheCompilersWordsOnOneLine() throws Exception {
        Seen seen = fail("not-compiled");
        commonTo(seen, SERVLET + " <- java.lang.Error; java.lang.Error says: Unresolved compilation problem: "
                + "PostmanEnvironmentReader cannot be resolved to a type");
    }

    @Test
    void aStackOverflowIsNamedByItsType() throws Exception {
        commonTo(fail("stack"), SERVLET + " <- java.lang.StackOverflowError");
    }

    @Test
    void anyOtherErrorIsNamedByItsTypeAndNeverByItsMessage() throws Exception {
        Seen seen = fail("plain-error");
        commonTo(seen, SERVLET + " <- java.lang.Error");
        assertThat(seen.body() + seen.logEntry()).doesNotContain("Could not read member");
    }

    @Test
    void anExceptionIsNamedByItsTypeAndNeverByItsMessage() throws Exception {
        Seen seen = fail("state");
        // An exception is not wrapped by Spring, so the chain is the exception alone.
        commonTo(seen, "java.lang.IllegalStateException");
        assertThat(seen.body() + seen.logEntry()).doesNotContain("No coverage");
    }

    @Test
    void anInitializerFailureGivesNoDetailBecauseItsTextQuotesWhatTheInitializerThrew() throws Exception {
        Seen seen = fail("initializer");
        commonTo(seen, SERVLET + " <- java.lang.ExceptionInInitializerError <- java.lang.RuntimeException");
        assertThat(seen.body() + seen.logEntry()).doesNotContain("Bad sample");
    }

    @Test
    void anInitializerFailureBuiltFromTextGivesNoDetailEither() throws Exception {
        Seen seen = fail("initializer-text");
        commonTo(seen, SERVLET + " <- java.lang.ExceptionInInitializerError");
        assertThat(seen.body() + seen.logEntry()).doesNotContain("Bad sample");
    }

    @Test
    void anErrorWrappedAsSpringWrapsItKeepsTheWrappersMessageOutToo() throws Exception {
        Seen seen = fail("wrapped");
        commonTo(seen, SERVLET + " <- java.lang.Error");
        assertThat(seen.body() + seen.logEntry()).doesNotContain("Handler dispatch failed").doesNotContain("Lookup of");
    }

    @Test
    void theCauseOnScreenIsTheFirstLineOfTheLogDescription() {
        Throwable error = new ServletException("Handler dispatch failed", new NoClassDefFoundError(MISSING));
        assertThat(Faults.describe(error).lines().findFirst().orElse("")).isEqualTo(Faults.cause(error));
    }

    @Test
    void aClassThatCannotBeLinkedAfterTheClassesChangedOnDiskSaysToStartPaveAgain(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        Path type = classes.resolve("A.class");
        Files.createDirectories(classes);
        Files.writeString(type, "x");
        Files.setLastModifiedTime(type, FileTime.from(Instant.parse("2026-11-22T05:00:00Z")));
        HandClock clock = new HandClock(Instant.parse("2026-11-22T06:00:00Z"));
        ClassesOnDisk.install(ClassesOnDisk.Watch.ofFolder(classes, null, clock));
        String cause = SERVLET + " <- java.lang.NoSuchMethodError; java.lang.NoSuchMethodError says: "
                + "'java.lang.String org.p32h.interop.epa.pave.model.Texts.withoutLeading(java.lang.String)'";

        // Unchanged on disk: a class that cannot be linked is a defect of the build, to report.
        commonTo(fail("no-method"), cause);

        Files.setLastModifiedTime(type, FileTime.from(Instant.parse("2026-11-22T06:00:05Z")));
        clock.advance(Duration.ofSeconds(11));
        Seen seen = fail("no-method");
        // The cause is the same line, with no message added: only the action changes.
        commonTo(seen, cause, "PAVE's classes changed on disk while it was running, as the IDE does when it rebuilds the project. "
                + "Stop PAVE and start it again.");
        // Any other failure after the change still asks for a report: only a link failure is a rebuild.
        commonTo(fail("state"), "java.lang.IllegalStateException");
    }

    @Test
    void aHeapThatRanOutSaysHowToStartPaveWithMore() throws Exception {
        commonTo(fail("heap"), SERVLET + " <- java.lang.OutOfMemoryError; java.lang.OutOfMemoryError says: Java heap space",
                "Start PAVE with -Xmx512m or more.");
    }
}
