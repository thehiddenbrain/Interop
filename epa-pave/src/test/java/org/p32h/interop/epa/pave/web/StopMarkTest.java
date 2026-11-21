package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;

/**
 * At the end of its time for the stop, the server itself closes a connection whose answer is not
 * written, with the same type a client that left raises. The line then must not say the client
 * closed it.
 */
@ExtendWith(OutputCaptureExtension.class)
class StopMarkTest {

    private static MockHttpServletRequest request(MockServletContext context) {
        MockHttpServletRequest request = new MockHttpServletRequest(context, "POST", "/api/members/S123456789");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/members/{memberId}");
        return request;
    }

    private static ClientAbortException gone() {
        return new ClientAbortException(new IOException("Broken pipe S123456789"));
    }

    @Test
    void onceTheToolBeginsToStopAConnectionThatClosedDoesNotSayTheClientClosedIt(CapturedOutput output) {
        MockServletContext servletContext = new MockServletContext();
        GenericWebApplicationContext context = new GenericWebApplicationContext(servletContext);

        new StopMark().onApplicationEvent(new ContextClosedEvent(context));
        ClientGone.log(request(servletContext), gone());

        String all = output.getAll();
        assertThat(all).contains(" INFO ").contains("The connection closed while this tool was stopping, before the answer was written: "
                + "POST /api/members/{memberId}, org.apache.catalina.connector.ClientAbortException <- java.io.IOException" + System.lineSeparator());
        assertThat(all).doesNotContain("The client closed").doesNotContain("S123456789").doesNotContain("Broken pipe")
                .doesNotContain("Unhandled").doesNotContain("\tat ");
    }

    @Test
    void beforeTheStopAConnectionThatClosedIsAClientThatLeft(CapturedOutput output) {
        ClientGone.log(request(new MockServletContext()), gone());

        assertThat(output.getAll()).contains("The client closed the connection before the answer was written: POST /api/members/{memberId}, ")
                .doesNotContain("while this tool was stopping");
    }

    /** The mark of one application is never seen by another in the same process. */
    @Test
    void theStopOfOneApplicationLeavesWhatAnotherSaysAsItWas(CapturedOutput output) {
        MockServletContext stopping = new MockServletContext();
        new StopMark().onApplicationEvent(new ContextClosedEvent(new GenericWebApplicationContext(stopping)));

        ClientGone.log(request(new MockServletContext()), gone());

        assertThat(output.getAll()).contains("The client closed the connection before the answer was written")
                .doesNotContain("while this tool was stopping");
    }

    @Test
    void aFailureWhileTheStopIsMarkedIsOneEntryUnderAReferenceAndNeverItsMessage(CapturedOutput output) {
        MockServletContext broken = new MockServletContext() {
            @Override
            public void setAttribute(String name, Object value) {
                throw new IllegalStateException("S123456789");
            }
        };

        assertThatCode(() -> new StopMark().onApplicationEvent(new ContextClosedEvent(new GenericWebApplicationContext(broken))))
                .doesNotThrowAnyException();

        assertThat(output.getAll()).containsPattern("Marking the stop failed inside this tool, reference [0-9a-f-]{36}: java.lang.IllegalStateException")
                .doesNotContain("S123456789");
        assertThat(output.getAll().split("Marking the stop failed", -1)).hasSize(2);
    }
}
