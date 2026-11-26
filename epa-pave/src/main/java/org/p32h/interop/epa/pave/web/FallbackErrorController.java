package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers errors that never reached a controller, such as a request the servlet container itself
 * rejected. Without this the container would answer with its own HTML error page, and no HTML
 * error page is allowed to reach the browser.
 */
@RestController
public class FallbackErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorView> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        // With no error recorded on the request, nothing failed: somebody asked for this address
        // directly, and there is nothing here for them. That is a 404, not a fault of the tool.
        HttpStatus status = code == null ? HttpStatus.NOT_FOUND
                : code instanceof Integer value && HttpStatus.resolve(value) != null ? HttpStatus.resolve(value)
                : HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorView body;
        if (status == HttpStatus.NOT_FOUND) {
            body = ErrorView.of(ErrorKind.NOT_FOUND, "There is nothing at this address.",
                    "Check the path against the endpoint table in the README.");
        } else if (status.is4xxClientError()) {
            body = ErrorView.of(ErrorKind.VALIDATION, "The request could not be accepted (HTTP " + status.value() + ").",
                    "Check the method, the path and the body of the request.");
        } else {
            body = ErrorView.of(ErrorKind.INTERNAL, "This tool hit an internal error and could not complete the request.",
                    "Try again. If it repeats, check the server log.");
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
