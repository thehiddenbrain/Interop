package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.model.Texts;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.DatabindException;
import tools.jackson.core.JacksonException;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.BusyException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single place an exception becomes a response. Every error leaves in the one shared shape,
 * {@link ErrorView}, and no stack trace or HTML error page reaches the browser.
 *
 * <p>Status discipline: 422 when what was submitted cannot be used, 403 when the production
 * guardrail refused, 409 when the tool is not configured to send, 404 when the thing asked for
 * does not exist. A vendor fault is not an exception at all; it is a completed call answered with
 * 200 and described in the body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorView> validation(ValidationException e) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation(e.getMessage(), e.fieldErrors()));
    }

    @ExceptionHandler(BadInputException.class)
    public ResponseEntity<ErrorView> badInput(BadInputException e) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.of(ErrorKind.VALIDATION, e.getMessage(), e.action()));
    }

    @ExceptionHandler(UnresolvedVariablesException.class)
    public ResponseEntity<ErrorView> unresolved(UnresolvedVariablesException e) {
        String environment = e.environmentName() == null ? "no environment is active" : "the active environment is \"" + e.environmentName() + "\"";
        String what = e.operation() == null ? "The request" : e.operation().label();
        String title = what + " cannot be sent: " + (e.variables().size() == 1 ? "the placeholder " : "the placeholders ")
                + String.join(", ", e.variables().stream().map(v -> "{{" + v + "}}").toList())
                + (e.variables().size() == 1 ? " has" : " have") + " no value, and " + environment + ". Nothing was sent.";
        List<FieldError> fields = e.variables().stream()
                .map(v -> new FieldError("variables." + v, e.reasons().containsKey(v)
                        ? "No value for {{" + v + "}}: " + e.reasons().get(v)
                        : "No value for {{" + v + "}}. Type one here, or add it to the environment on the Config tab."))
                .toList();
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, new ErrorView(ErrorKind.CONFIGURATION, title,
                "Type a value for each marked variable, or define it in the active environment on the Config tab.", null, fields, List.of(), null));
    }

    @ExceptionHandler(EndpointNotConfiguredException.class)
    public ResponseEntity<ErrorView> notConfigured(EndpointNotConfiguredException e) {
        return respond(HttpStatus.CONFLICT, ErrorView.of(ErrorKind.CONFIGURATION, e.getMessage() + " Nothing was sent.",
                "Open the Config tab, upload a collection that contains this request, or set the fallback url in application.yml."));
    }

    @ExceptionHandler(ConfigurationChangedException.class)
    public ResponseEntity<ErrorView> configurationChanged(ConfigurationChangedException e) {
        return respond(HttpStatus.CONFLICT, ErrorView.of(ErrorKind.CONFIGURATION,
                "The configuration changed while this request was being prepared. Nothing was sent.",
                "Check which environment is active, then send again."));
    }

    @ExceptionHandler(ProdWriteBlockedException.class)
    public ResponseEntity<ErrorView> blocked(ProdWriteBlockedException e) {
        return respond(HttpStatus.FORBIDDEN, ErrorView.of(ErrorKind.BLOCKED, e.getMessage() + " Nothing was sent.", e.action()));
    }

    @ExceptionHandler(BusyException.class)
    public ResponseEntity<ErrorView> busy(BusyException e) {
        return respond(HttpStatus.CONFLICT, ErrorView.of(ErrorKind.VALIDATION, e.getMessage(), e.action()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorView> notFound(NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ErrorView.of(ErrorKind.NOT_FOUND, e.getMessage(), "Check the identifier and try again."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorView> unreadable(HttpMessageNotReadableException e) {
        // The exception message can quote the request body, which may hold member data, so it is
        // never used. What is taken from the cause is the name of a property or its place in the
        // body, which the sender wrote themselves and which holds no value.
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof UnrecognizedPropertyException unknown) {
                String name = Texts.orElse(unknown.getPropertyName(), "(unnamed)");
                return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation(
                        "The request has a field this tool does not know. Nothing was sent.",
                        List.of(new FieldError(name, "This tool does not know the field \"" + name + "\". Check its spelling."))));
            }
            if (t instanceof DatabindException mismatch) {
                String where = place(mismatch);
                return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation(
                        "The request is JSON, but one of its fields does not hold the kind of value this endpoint takes. Nothing was sent.",
                        List.of(new FieldError(where, "The value given here is not of the kind this field takes."))));
            }
        }
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.of(ErrorKind.VALIDATION,
                "The request body is missing or is not valid JSON. Nothing was sent.",
                "Send a JSON body in the shape this endpoint expects."));
    }

    /** Where in the body a value could not be bound, as property names and positions only. */
    private static String place(DatabindException error) {
        StringBuilder out = new StringBuilder();
        for (JacksonException.Reference step : error.getPath()) {
            if (step.getPropertyName() != null) {
                out.append(out.length() == 0 ? "" : ".").append(step.getPropertyName());
            } else if (step.getIndex() >= 0) {
                out.append('[').append(step.getIndex()).append(']');
            }
        }
        return out.length() == 0 ? "(body)" : out.toString();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorView> typeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation("A request parameter has a value that cannot be used.",
                List.of(new FieldError(name, "The value given for " + name + " is not valid for this parameter."))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorView> missingParameter(MissingServletRequestParameterException e) {
        String name = e.getParameterName();
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation("A required request parameter is missing.",
                List.of(new FieldError(name, "The parameter " + name + " is required."))));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorView> missingPart(MissingServletRequestPartException e) {
        String name = e.getRequestPartName();
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation("A required part of the upload is missing.",
                List.of(new FieldError(name, "The part " + name + " is required."))));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorView> tooLarge(MaxUploadSizeExceededException e) {
        return respond(HttpStatus.CONTENT_TOO_LARGE, ErrorView.of(ErrorKind.VALIDATION, "The uploaded file is larger than this tool accepts.",
                "Raise spring.servlet.multipart.max-file-size in application.yml, or upload a smaller export."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorView> multipart(MultipartException e) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.of(ErrorKind.VALIDATION, "The upload could not be read.",
                "Send the file as multipart form data."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorView> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorView.of(ErrorKind.VALIDATION,
                "This endpoint does not accept " + e.getMethod() + ".", "Use one of the methods the endpoint table lists for this path."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorView> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorView.of(ErrorKind.VALIDATION,
                "This endpoint does not accept that content type.", "Send application/json, or multipart form data for an upload."));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorView> notAcceptable(HttpMediaTypeNotAcceptableException e) {
        return respond(HttpStatus.NOT_ACCEPTABLE, ErrorView.of(ErrorKind.VALIDATION,
                "This endpoint cannot answer in the requested format.", "Ask for application/json."));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorView> noSuchPath(Exception e) {
        return respond(HttpStatus.NOT_FOUND, ErrorView.of(ErrorKind.NOT_FOUND, "There is nothing at this address.",
                "Check the path against the endpoint table in the README."));
    }

    /**
     * Anything else is a defect in this tool. The browser gets a reference and no detail. The log
     * gets the same reference with the exception types and stack frames, but not the exception
     * messages: a message can quote a request or response body, and those hold member data.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> unexpected(Exception e) {
        String reference = UUID.randomUUID().toString();
        log.error("Unhandled exception, reference {}: {}", reference, describe(e));
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorView.of(ErrorKind.INTERNAL,
                "This tool hit an internal error and could not complete the request.",
                "Report the reference below with what you were doing. The server log holds the detail under the same reference.")
                .withReference(reference));
    }

    /** Exception types and the top frames of each, with no messages. */
    static String describe(Throwable error) {
        return Faults.describe(error);
    }

    private static ResponseEntity<ErrorView> respond(HttpStatus status, ErrorView body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
