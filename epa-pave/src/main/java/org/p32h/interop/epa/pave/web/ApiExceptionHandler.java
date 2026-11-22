package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.SizeException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.service.config.ConfigStoreException;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.BusyException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.FaultAdvice;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * The single place an exception becomes a response. Every error leaves in the one shared shape,
 * {@link ErrorView}, and no stack trace or HTML error page reaches the browser.
 *
 * <p>Status discipline: 422 when what was submitted cannot be used, 403 when the production
 * guardrail refused, 409 when the tool is not configured to send, 404 when the thing asked for
 * does not exist. A request that is badly formed as a request, such as a query that is not
 * properly percent-encoded, is a 400, and one larger than the tool accepts is a 413: the request
 * is at fault there, not the tool, so neither is answered or logged as an internal error. A
 * vendor fault is not an exception at all; it is a completed call answered with 200 and described
 * in the body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** How the JSON parser names a key it found twice in one object, when told to refuse one. */
    private static final Pattern REPEATED_KEY = Pattern.compile("^Duplicate Object property \"(.*)\"");

    private static final Set<String> KNOWN_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "CONNECT");

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorView> validation(ValidationException e) {
        ErrorView view = ErrorView.validation(e.getMessage(), e.fieldErrors());
        if (e.action() != null) {
            view = new ErrorView(view.kind(), view.title(), e.action(), view.httpStatus(), view.fieldErrors(), view.issues(), view.reference());
        }
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, view);
    }

    @ExceptionHandler(BadInputException.class)
    public ResponseEntity<ErrorView> badInput(BadInputException e) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.of(ErrorKind.VALIDATION, e.getMessage(), e.action()));
    }

    @ExceptionHandler(UnresolvedVariablesException.class)
    public ResponseEntity<ErrorView> unresolved(UnresolvedVariablesException e) {
        String environment = e.environmentName() == null ? "no environment is active" : "the active environment is \"" + Names.shown(e.environmentName()) + "\"";
        String what = e.operation() == null ? "The request" : e.operation().label();
        // A part of the url written {id} or :id is filled from the identifier the call is for.
        // It is written here as it is in the url, so nobody goes looking for a placeholder {{id}}
        // to define in an environment, where it cannot be defined.
        java.util.function.Function<String, String> written = v -> e.urlTokens().contains(v) ? "{" + v + "}" : "{{" + v + "}}";
        boolean anyPlaceholder = e.variables().stream().anyMatch(v -> !e.urlTokens().contains(v));
        boolean anyToken = e.variables().stream().anyMatch(v -> e.urlTokens().contains(v));
        boolean one = e.variables().size() == 1;
        String kind = !anyToken ? (one ? "the placeholder " : "the placeholders ") : !anyPlaceholder ? (one ? "the url part " : "the url parts ") : "";
        String title = what + " cannot be sent: " + kind + String.join(", ", e.variables().stream().map(written).toList())
                + (e.variables().size() == 1 ? " has" : " have") + " no value, and " + environment + ". Nothing was sent.";
        List<FieldError> fields = e.variables().stream()
                .map(v -> new FieldError("variables." + v, e.reasons().containsKey(v)
                        ? "No value for " + written.apply(v) + ": " + e.reasons().get(v)
                        : "No value for " + written.apply(v) + ". Type one here, or add it to the environment on the Config tab."))
                .toList();
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, new ErrorView(ErrorKind.CONFIGURATION, title, anyPlaceholder
                ? "Type a value for each marked variable, or define it in the active environment on the Config tab."
                : "Give the identifier the url is waiting for, then send again.", null, fields, List.of(), null));
    }

    @ExceptionHandler(EndpointNotConfiguredException.class)
    public ResponseEntity<ErrorView> notConfigured(EndpointNotConfiguredException e) {
        return respond(HttpStatus.CONFLICT, ErrorView.of(ErrorKind.CONFIGURATION, e.getMessage() + " Nothing was sent.",
                "Open the Config tab, upload a collection that contains this request, or set the fallback url in application.yml."));
    }

    @ExceptionHandler(ConfigurationChangedException.class)
    public ResponseEntity<ErrorView> configurationChanged(ConfigurationChangedException e) {
        // A refusal that knows what changed, such as writes locked again, says so: the general
        // words would send the tester to check the environment when it is the lock that moved.
        return respond(HttpStatus.CONFLICT, ErrorView.of(ErrorKind.CONFIGURATION,
                Texts.orElse(e.title(), "The configuration changed while this request was being prepared. Nothing was sent."),
                Texts.orElse(e.action(), "Check which environment is active, then send again.")));
    }

    @ExceptionHandler(ProdWriteBlockedException.class)
    public ResponseEntity<ErrorView> blocked(ProdWriteBlockedException e) {
        return respond(HttpStatus.FORBIDDEN, ErrorView.of(ErrorKind.BLOCKED, e.getMessage() + " Nothing was sent.", e.action()));
    }

    /** The heading of a store write that failed for a reason of this computer's files, which the tester can mend there and then. */
    static final String THIS_COMPUTER_HEADING = "A problem with this computer's files";

    @ExceptionHandler(ConfigStoreException.class)
    public ResponseEntity<ErrorView> storeNotWritten(ConfigStoreException e) {
        // The message names the file the store could not write and says nothing was changed. It holds no content.
        if (e.thisComputer()) {
            // The message already says what to do about the file (clear Read-only, close the other
            // program, free space), so a fixed action would only repeat it or contradict it. Not a
            // failure of this tool: no reference, nothing to report.
            return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorView.of(ErrorKind.CONFIGURATION, e.getMessage(), null)
                    .withHeading(THIS_COMPUTER_HEADING));
        }
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorView.of(ErrorKind.INTERNAL, e.getMessage(),
                "Check the configuration store directory and its free space, then try again."));
    }

    @ExceptionHandler(BusyException.class)
    public ResponseEntity<ErrorView> busy(BusyException e) {
        return respond(HttpStatus.CONFLICT, ErrorView.of(ErrorKind.VALIDATION, e.getMessage(), e.action()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorView> notFound(NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ErrorView.of(ErrorKind.NOT_FOUND, e.getMessage(),
                org.p32h.interop.epa.pave.model.Texts.orElse(e.action(), "Check the identifier and try again.")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorView> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        if (causedBy(e, RequestShapeFilter.BodyTooLargeException.class)) {
            return respond(HttpStatus.CONTENT_TOO_LARGE, ErrorView.of(ErrorKind.VALIDATION,
                    RequestShapeFilter.tooLargeTitle(maxRequestSize(request)), RequestShapeFilter.tooLargeAction()));
        }
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
            String repeated = t instanceof StreamReadException read ? repeatedKey(read) : null;
            if (repeated != null) {
                return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation(
                        "The request repeats the field \"" + repeated + "\" inside one object. Nothing was sent.",
                        List.of(new FieldError(repeated, "This field is given more than once. Keep one, so the request says one thing."))));
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

    /**
     * The place of a key repeated inside one object, from a parser told to refuse one, as property
     * names and positions only; null for any other failure to read. The name is the parser's own
     * report of the key, which the sender wrote and which holds no value.
     */
    private static String repeatedKey(StreamReadException error) {
        Matcher named = REPEATED_KEY.matcher(Texts.orElse(error.getOriginalMessage(), ""));
        if (!named.find()) {
            return null;
        }
        // The parser stands on the repeated key when it refuses it, so its path ends with the key.
        String path = error.processor() == null || error.processor().streamReadContext() == null ? ""
                : error.processor().streamReadContext().pathAsPointer().toString();
        StringBuilder out = new StringBuilder();
        for (String step : path.split("/")) {
            if (step.isEmpty()) {
                continue;
            }
            if (step.chars().allMatch(Character::isDigit)) {
                out.append('[').append(step).append(']');
            } else {
                out.append(out.length() == 0 ? "" : ".").append(step.replace("~1", "/").replace("~0", "~"));
            }
        }
        String key = named.group(1);
        String place = out.toString();
        return place.equals(key) || place.endsWith("." + key) ? place : place + (place.isEmpty() ? "" : ".") + key;
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
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation("A value given with the request cannot be used. Nothing was done.",
                List.of(new FieldError(name, "The value given here cannot be used. Correct it and try again."))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorView> missingParameter(MissingServletRequestParameterException e) {
        String name = e.getParameterName();
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation("A value the request needs is missing. Nothing was done.",
                List.of(new FieldError(name, "A value is required here."))));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorView> missingPart(MissingServletRequestPartException e) {
        String name = e.getRequestPartName();
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.validation("A required part of the upload is missing.",
                List.of(new FieldError(name, "The part " + name + " is required."))));
    }

    /**
     * An upload past one of the limits the server holds it to. Each limit is named with what would
     * help, since raising the file size does nothing for an upload with too many parts. The limit
     * is told by the type of the server's exception and the limit it carries, never by its message.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorView> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        SizeException size = cause(e, SizeException.class);
        String requestLimit = maxRequestSize(request);
        ErrorView body;
        if (size instanceof FileSizeLimitExceededException) {
            body = ErrorView.of(ErrorKind.VALIDATION, "The uploaded file is larger than this tool accepts, which is "
                    + property(request, "spring.servlet.multipart.max-file-size", "25MB") + " for one file.",
                    "Upload a smaller export, or raise spring.servlet.multipart.max-file-size in application.yml.");
        } else if (size instanceof SizeLimitExceededException && size.getPermittedSize() == DataSize.parse(requestLimit).toBytes()) {
            body = ErrorView.of(ErrorKind.VALIDATION, RequestShapeFilter.tooLargeTitle(requestLimit),
                    "Upload one file at a time, or raise " + RequestShapeFilter.MAX_REQUEST_SIZE + " in application.yml.");
        } else if (size != null) {
            // The server holds the header of each part to a limit of its own and reports it as a size of the request.
            body = ErrorView.of(ErrorKind.VALIDATION, "A part of the upload has a header larger than this tool accepts, which is "
                    + "usually a very long file name.", "Rename the file to a shorter name and upload it again. The limit is "
                    + "server.tomcat.max-part-header-size.");
        } else if (cause(e, FileCountLimitExceededException.class) != null) {
            body = ErrorView.of(ErrorKind.VALIDATION, "The upload has more parts than this tool accepts.",
                    "Send the file with only the fields this upload takes. The limit is server.tomcat.max-part-count.");
        } else {
            body = ErrorView.of(ErrorKind.VALIDATION, "The fields sent with the upload are larger than this tool accepts.",
                    "Send the file with only the fields this upload takes. The limit is server.tomcat.max-http-form-post-size.");
        }
        return respond(HttpStatus.CONTENT_TOO_LARGE, body);
    }

    private static <T extends Throwable> T cause(Throwable error, Class<T> type) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        return cause(error, type) != null;
    }

    private static String maxRequestSize(HttpServletRequest request) {
        return property(request, RequestShapeFilter.MAX_REQUEST_SIZE, "30MB");
    }

    /** A limit as the operator wrote it, read from the running application, so the sentence names the value in force. */
    private static String property(HttpServletRequest request, String name, String fallback) {
        WebApplicationContext context = request == null ? null
                : WebApplicationContextUtils.getWebApplicationContext(request.getServletContext());
        String value = context == null ? null : Texts.trimToNull(context.getEnvironment().getProperty(name));
        return value == null ? fallback : value;
    }

    /**
     * A query the server cannot read: a value that is not properly percent-encoded, a parameter
     * with no name, or more parameters than it takes. The request is at fault, not the tool, so
     * this is a 400 and not a 500, and nothing is logged.
     */
    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<ErrorView> unreadableQuery(InvalidParameterException e) {
        return respond(HttpStatus.BAD_REQUEST, ErrorView.of(ErrorKind.VALIDATION,
                "The query of the address cannot be read: a value is not properly percent-encoded, a parameter has no name, "
                        + "or there are more parameters than this tool takes. Nothing was done.",
                "Percent-encode a percent sign that is part of a value, as %25, and send the request again."));
    }

    /** The client went away while the answer was being written. There is nobody left to answer: one line at INFO. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientWentAway(AsyncRequestNotUsableException e, HttpServletRequest request) {
        try {
            ClientGone.log(request, e);
        } catch (LinkageError broken) {
            // A class of the line that cannot be linked after a rebuild: nobody is there to answer.
        }
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorView> multipart(MultipartException e) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorView.of(ErrorKind.VALIDATION, "The upload could not be read.",
                "Send the file as multipart form data."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorView> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorView.of(ErrorKind.VALIDATION,
                // A method is repeated only when it is one of the methods HTTP defines. A request line
                // can hold any word there, and an error answer repeats nothing a request made up.
                "This endpoint does not accept " + (KNOWN_METHODS.contains(e.getMethod()) ? e.getMethod() : "that method") + ".",
                e.getSupportedHttpMethods() == null || e.getSupportedHttpMethods().isEmpty()
                        ? "Use one of the methods the endpoint table lists for this path."
                        : "Use " + String.join(" or ", e.getSupportedHttpMethods().stream().map(Object::toString).sorted().toList()) + "."));
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
     * Anything else is a defect in this tool. The browser gets a reference and the cause on one
     * line, the same line the log entry begins with, so that whoever meets the failure can report
     * it without the server log. The log adds the stack frames, which never reach the browser.
     * Neither gets an exception message beyond what {@link Faults#cause} allows: a message can
     * quote a request or response body, and those hold member data.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> unexpected(Exception e, HttpServletRequest request) {
        if (clientGone(e, request)) {
            // Writing the answer failed because the client had closed the connection, and the framework
            // wrapped that in a failure to write the message, which is matched here before its cause.
            // Not a defect: one line at INFO and no answer, since nobody is there to read one.
            return null;
        }
        String reference = UUID.randomUUID().toString();
        log.error("Unhandled exception, reference {}: {}", reference, describe(e));
        // A heap too small, or classes rebuilt on disk under PAVE, is said as such: a report would
        // send someone looking for a defect that is not there (R-24, R-36).
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorView.of(ErrorKind.INTERNAL,
                "This tool hit an internal error and could not complete the request.", FaultAdvice.action(e))
                .withReference(reference).withCause(Faults.cause(e)));
    }

    /**
     * True when the client went away, after its one line is written. A class of the question that
     * cannot be linked, as when the IDE rebuilt the classes under PAVE, makes the answer false, so
     * that the failure is logged under a reference and answered instead of thrown on to the server,
     * which would log it with its message and answer in a shape of its own (R-34).
     */
    static boolean clientGone(Throwable e, HttpServletRequest request) {
        boolean gone;
        try {
            gone = ClientGone.is(e);
        } catch (LinkageError broken) {
            return false;
        }
        if (gone) {
            try {
                ClientGone.log(request, e);
            } catch (LinkageError broken) {
                // The line is not worth a failure of its own, and nobody is there to answer.
            }
        }
        return gone;
    }

    /** Exception types and the top frames of each, with no messages. */
    static String describe(Throwable error) {
        return Faults.describe(error);
    }

    private static ResponseEntity<ErrorView> respond(HttpStatus status, ErrorView body) {
        // Every refusal is worded for the call that was made, in one place: a 422 of the form, and as
        // much a 403 of an unlock or a 409 of a preview, which would otherwise speak of a send.
        ErrorView worded = status.is4xxClientError() ? CallWording.word(body, CallWording.current()) : body;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(worded);
    }
}
