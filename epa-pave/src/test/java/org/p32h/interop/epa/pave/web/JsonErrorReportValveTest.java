package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The server's own error answer can meet a client that already left. A real client cannot be made
 * to leave at the one moment the server writes its error report, so the valve is given a response
 * whose last write fails the way the server's own does when the connection is gone.
 */
@ExtendWith(OutputCaptureExtension.class)
class JsonErrorReportValveTest {

    /** A response of the embedded server whose final write fails with what the caller hands it. */
    private static final class FailingResponse extends Response {
        private final IOException failure;

        FailingResponse(IOException failure) {
            super(new org.apache.coyote.Response());
            this.failure = failure;
            // What the server does on an error of its own before it asks for a report: the report is only
            // written for a response in that state.
            setStatus(400);
            setError();
        }

        @Override
        public void finishResponse() throws IOException {
            throw failure;
        }
    }

    private static Request request(String method) {
        return new Request(null, new org.apache.coyote.Request()) {
            @Override
            public String getMethod() {
                return method;
            }
        };
    }

    @Test
    void aClientThatLeftBeforeTheServersErrorAnswerWasWrittenIsOneInfoLineAndNoReference(CapturedOutput output) {
        Response response = new FailingResponse(new ClientAbortException(new IOException("Broken pipe S123456789")));
        Request request = request("GET");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/members/{memberId}");

        new JsonErrorReportValve().report(request, response, null);

        String all = output.getAll();
        assertThat(all).contains(" INFO ").contains("The client closed the connection before the answer was written: GET /api/members/{memberId}, "
                + "org.apache.catalina.connector.ClientAbortException <- java.io.IOException" + System.lineSeparator());
        assertThat(all.split("The client closed the connection", -1)).hasSize(2);
        assertThat(all).doesNotContain("Unhandled").doesNotContain("reference").doesNotContain("S123456789").doesNotContain("Broken pipe")
                .doesNotContain("\tat ");
    }

    @Test
    void aRequestThatMatchedNoRouteIsNamedAsSuchAndAMethodTheRequestMadeUpIsNotRepeated(CapturedOutput output) {
        Response response = new FailingResponse(new ClientAbortException());

        new JsonErrorReportValve().report(request("S123456789"), response, null);

        assertThat(output.getAll()).contains("The client closed the connection before the answer was written: another method "
                + "(a request that matched no route of this tool), org.apache.catalina.connector.ClientAbortException")
                .doesNotContain("S123456789");
    }

    /** A write that failed for another reason is not filed as a client that left. */
    @Test
    void anotherFailureToWriteTheServersErrorAnswerIsNotCalledAClientThatLeft(CapturedOutput output) {
        Response response = new FailingResponse(new IOException("S123456789"));

        new JsonErrorReportValve().report(request("GET"), response, null);

        assertThat(output.getAll()).doesNotContain("The client closed the connection").doesNotContain("S123456789");
    }
}
