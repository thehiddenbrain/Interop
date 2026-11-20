package org.p32h.interop.epa.pave.web;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.p32h.interop.epa.pave.model.ErrorKind;

/**
 * Answers the errors the embedded server raises before a request reaches this application, in the
 * same error shape and with the same headers as everything else.
 *
 * <p>A request the server itself refuses, such as one with characters a url may not hold, never
 * reaches the filters or the exception handler. Left alone, the server answers it with an HTML
 * page that names its version and can carry a stack trace, and with none of the headers that keep
 * an answer out of the browser's cache. The browser code expects JSON on every error, and no stack
 * trace may reach a browser, so this writes the shared shape instead, with the five headers
 * {@link ResponseHeadersFilter} gives every other response.
 *
 * <p>The shape has no {@code httpStatus}: in the shared shape that field is the status a vendor
 * answered with, and an error of the server's own has none. Its status is the response's.
 *
 * <p>The server builds this class by name, which is why it has no dependencies. The sentences are
 * fixed and nothing of the request is repeated.
 */
public class JsonErrorReportValve extends ErrorReportValve {

    public JsonErrorReportValve() {
        // Belt and braces: even if the reporting below were bypassed, no report or version is shown.
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        // Only an error with nothing written yet. Anything the application already answered is left alone.
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        boolean serverFault = status >= 500;
        String body = EdgeAnswers.json(serverFault ? ErrorKind.INTERNAL : ErrorKind.VALIDATION,
                serverFault ? "The server could not handle this request." : "The server did not accept this request as it was sent.",
                serverFault ? "Try again. If it repeats, report what you were doing." : "Check the address and the request, then send it again.",
                null);
        try {
            ResponseHeadersFilter.apply(response);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body);
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException e) {
            // The connection is gone or the response is already committed. There is nobody left to tell.
        }
    }
}
