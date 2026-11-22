package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The error path survives classes rebuilt under a running PAVE (R-34). On the operator's
 * workstation the class that tells a client that went away was itself among the classes missing
 * after a rebuild, so asking it threw, the failure went on to the server, and nothing named the
 * cause. Here asking it throws the same way: the failure is still logged under a reference and
 * answered in the shared shape, and nothing goes on to the server.
 */
class EdgeFailureOrderTest {

    /** The class the question is asked of, missing as after a clean of the classes folder. */
    private static final String MISSING = "org/p32h/interop/epa/pave/web/ClientGone";

    /**
     * A failure whose chain throws a {@link NoClassDefFoundError} when, and only when, the class that
     * tells a client that went away walks it: what that class meets when a class it needs is gone.
     */
    static final class BrokenWhenAskedIfTheClientLeft extends RuntimeException {

        BrokenWhenAskedIfTheClientLeft() {
            super("no plan for S123456789");
        }

        @Override
        public synchronized Throwable getCause() {
            boolean asked = StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().equals(ClientGone.class.getName())));
            if (asked) {
                throw new NoClassDefFoundError(MISSING);
            }
            return null;
        }
    }

    @RestController
    static class FailingController {

        @GetMapping("/fail")
        public String fail() {
            throw new BrokenWhenAskedIfTheClientLeft();
        }
    }

    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Logger filterLog = (Logger) LoggerFactory.getLogger(ResponseHeadersFilter.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private ClassesOnDisk.Watch before;

    @BeforeEach
    void listen() {
        captured.start();
        handlerLog.addAppender(captured);
        filterLog.addAppender(captured);
        before = ClassesOnDisk.install(ClassesOnDisk.Watch.unknown());
    }

    @AfterEach
    void stopListening() {
        handlerLog.detachAppender(captured);
        filterLog.detachAppender(captured);
        captured.stop();
        ClassesOnDisk.install(before);
    }

    private static void answeredAsAFailureOfThisTool(MockHttpServletResponse response, String logged) throws Exception {
        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode body = JsonSafe.parse(response.getContentAsString()).node();
        assertThat(JsonSafe.text(body, "kind")).isEqualTo("INTERNAL");
        String reference = JsonSafe.text(body, "reference");
        assertThat(reference).isNotBlank();
        assertThat(JsonSafe.text(body, "cause")).isEqualTo(BrokenWhenAskedIfTheClientLeft.class.getName());
        assertThat(logged).contains("reference " + reference + ": " + BrokenWhenAskedIfTheClientLeft.class.getName());
        assertThat(logged + response.getContentAsString()).doesNotContain("S123456789").doesNotContain("no plan");
    }

    private String logged() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : captured.list) {
            all.append(event.getLevel()).append(' ').append(event.getFormattedMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void theHandlerStillLogsAndAnswersWhenTheQuestionCannotBeLinked() throws Exception {
        MockHttpServletResponse response = TestMvc.standalone(new FailingController()).perform(get("/fail")).andReturn().getResponse();
        answeredAsAFailureOfThisTool(response, logged());
        assertThat(logged()).startsWith("ERROR Unhandled exception, reference ");
    }

    @Test
    void theOutermostFilterStillLogsAndAnswersWhenTheQuestionCannotBeLinked() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatCode(() -> new ResponseHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/api/config/environments"), response,
                (req, res) -> {
                    throw new BrokenWhenAskedIfTheClientLeft();
                })).doesNotThrowAnyException();
        answeredAsAFailureOfThisTool(response, logged());
        assertThat(logged()).startsWith("ERROR Unhandled exception at the edge, reference ");
    }

    @Test
    void aClientThatWentAwayIsStillOneLineAndNoEntry() {
        assertThat(ApiExceptionHandler.clientGone(new org.apache.catalina.connector.ClientAbortException(), new MockHttpServletRequest())).isTrue();
        assertThat(ApiExceptionHandler.clientGone(new IllegalStateException(), new MockHttpServletRequest())).isFalse();
        assertThat(ApiExceptionHandler.clientGone(new BrokenWhenAskedIfTheClientLeft(), new MockHttpServletRequest())).isFalse();
        assertThat(logged()).isEmpty();
    }
}
