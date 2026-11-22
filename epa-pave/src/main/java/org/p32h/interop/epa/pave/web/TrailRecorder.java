package org.p32h.interop.epa.pave.web;

import java.lang.reflect.Type;
import java.util.List;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.service.ledger.RunTrail;
import org.p32h.interop.epa.pave.service.run.RunRequest;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a run to the trail as the browser is answered (R-21).
 *
 * <p>The posted case is held in a request attribute when the body is read, and the entry is
 * written when a run result is about to be answered, so the trail holds exactly what the browser
 * received: the result whose wire view the services already masked, beside the case as posted.
 * The case loses what can hold a secret or a whole document: the values typed for one run, a body
 * of the tester's own, what an earlier step handed on, the collection request named, the values
 * typed into an address, and the content of every attachment.
 *
 * <p>A run is kept when its result carries a wire request, sent or played; a write the production
 * guardrail refused sent nothing and is not kept. Suite and replay runs answer with their own
 * reports, never with one run result, so they never reach here. Whatever goes wrong in keeping a
 * run never touches the answer: the body is handed on unchanged and the failure is logged by its
 * type only.
 */
@ControllerAdvice
public class TrailRecorder implements RequestBodyAdvice, ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(TrailRecorder.class);

    /** The request attribute that holds the posted case until the result is answered. */
    static final String CASE = TrailRecorder.class.getName() + ".case";

    /**
     * Fields of a posted case that never go into the trail. Each can hold a secret, or the whole of
     * a request or a document, which the wire view already shows masked or cut.
     */
    static final List<String> LEFT_OUT = List.of("variables", "rawBody", "carry", "request", "urlValues", "unrecognised");

    /** Fields of an attachment that carry its content. */
    static final List<String> CONTENT = List.of("data", "text");

    private final RunTrail trail;
    private final JsonMapper json;

    public TrailRecorder(RunTrail trail, JsonMapper json) {
        this.trail = trail;
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType instanceof Class<?> type && RunRequest.class.isAssignableFrom(type);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) {
        return input;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        // The message handed here is a wrapper around the servlet request, never the request itself, so
        // the request is taken from the thread that is handling it.
        if (trail.enabled() && body instanceof RunRequest
                && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(CASE, body);
        }
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage input, MethodParameter parameter, Type targetType,
                                  Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof RunEnvelope<?> envelope && trail.enabled()) {
            Object posted = request instanceof ServletServerHttpRequest servlet ? servlet.getServletRequest().getAttribute(CASE) : null;
            record(envelope, posted);
        }
        return body;
    }

    /** Keeps one run, never throwing: the answer goes out whatever happens here. */
    void record(RunEnvelope<?> envelope, Object posted) {
        try {
            if (!keeps(envelope)) {
                return;
            }
            trail.keep(envelope.runId(), entry(envelope, posted));
        } catch (Exception | LinkageError | StackOverflowError | OutOfMemoryError e) {
            // Types only: the message of a write or a serialiser can quote a path or the entry.
            log.warn("A run could not be kept in the trail ({}). The answer was not changed.", e.getClass().getName());
        }
    }

    /** A run that sent or played a request. A refused write sent nothing. */
    static boolean keeps(RunEnvelope<?> envelope) {
        return envelope.wire() != null && envelope.wire().request() != null && !RunSupport.isBlocked(envelope);
    }

    ObjectNode entry(RunEnvelope<?> envelope, Object posted) {
        JsonNode answered = json.valueToTree(envelope);
        ObjectNode entry = json.createObjectNode();
        entry.put("runId", envelope.runId());
        copy(answered, entry, "chainId");
        copy(answered, entry, "protocol");
        copy(answered, entry, "operation");
        JsonNode environment = answered.get("environment");
        if (environment != null && environment.isObject()) {
            ObjectNode named = entry.putObject("environment");
            copy(environment, named, "name");
            copy(environment, named, "kind");
        }
        copy(answered, entry, "profile");
        if (posted instanceof RunRequest) {
            entry.set("case", caseOf(json.valueToTree(posted)));
        }
        entry.set("envelope", answered);
        return entry;
    }

    /** The case as posted, less every field {@link #LEFT_OUT} names and the content of each attachment. */
    static JsonNode caseOf(JsonNode posted) {
        if (!(posted instanceof ObjectNode object)) {
            return posted;
        }
        for (String field : LEFT_OUT) {
            object.remove(field);
        }
        JsonNode attachments = object.get("attachments");
        if (attachments != null && attachments.isArray()) {
            for (JsonNode attachment : attachments) {
                if (attachment instanceof ObjectNode each) {
                    for (String field : CONTENT) {
                        each.remove(field);
                    }
                }
            }
        }
        return object;
    }

    private static void copy(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) {
            to.set(field, value);
        }
    }
}
