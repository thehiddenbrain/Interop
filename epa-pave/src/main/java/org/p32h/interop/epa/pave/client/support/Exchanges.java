package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.service.error.BadInputException;
import java.nio.charset.StandardCharsets;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Reached;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the description of a call the same way for the live clients and the mock clients.
 *
 * <p>The rules for what counts as a bad request, a server error or a malformed response live here
 * once. If the mock classified a response by its own rules, a scenario could show one thing on
 * mock and another on live for the same status and body.
 */
public final class Exchanges {

    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "DELETE", "OPTIONS");

    /**
     * Headers the HTTP client writes itself or will not let a caller set. A vendor collection often
     * saves one, most often a Content-Length that is wrong the moment the body is edited. Sent as
     * written they would break the request or be silently replaced, so they are left out and the
     * wire view says so.
     */
    private static final Set<String> TRANSPORT_HEADERS = Set.of("content-length", "host", "connection", "transfer-encoding",
            "expect", "upgrade", "keep-alive", "te", "trailer", "proxy-connection");

    /** The parse error of a response whose body did not arrive in full. */
    public static final String INCOMPLETE_BODY = "The body is incomplete.";

    /** The characters an HTTP method or header name may be made of. */
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private Exchanges() {
    }

    /**
     * The two checks every call passes immediately before it is sent or answered, on both profiles.
     *
     * <p>A body that still holds a placeholder is refused. Without this check it would go out as no
     * body at all, and what the tester saw would be a vendor rejection with no visible cause.
     *
     * <p>A header that still holds a placeholder is refused for the same reason. Left out, the
     * request would fail at the far side with nothing to show why, or go out with another
     * credential in its place.
     *
     * <p>A method or a header that is not valid HTTP is refused, naming it. The HTTP client would
     * refuse it too, but only as an exception that reads like a network failure.
     *
     * <p>A call prepared under an earlier configuration is refused. It carries the url and the
     * guardrail decision of an environment that is no longer the active one.
     *
     * @param gateway the gateway whose epoch is current, or null where there is none to ask
     */
    public static void requireSendable(PreparedCall call, ResolvedText body, OperationGateway gateway) {
        if (body != null && !body.resolved()) {
            throw new UnresolvedVariablesException(call.operation(), body.unresolved(), call.environment().name(), body.template());
        }
        for (ResolvedHeader header : call.headers()) {
            if (Texts.present(header.name()) && !header.resolved()) {
                throw new UnresolvedVariablesException(call.operation(), header.unresolved(), call.environment().name(),
                        "the header " + header.name());
            }
        }
        if (!TOKEN.matcher(method(call)).matches()) {
            throw new BadInputException("The HTTP method of this request is not a valid method.",
                    "Correct the method of the collection request" + from(call) + ". Nothing was sent.");
        }
        for (ResolvedHeader header : call.headers()) {
            if (Texts.blank(header.name())) {
                continue;
            }
            // The header is named. Its value is never quoted, since it may be a credential.
            if (!TOKEN.matcher(header.name()).matches()) {
                throw new BadInputException("A header of this request has a name that is not valid in HTTP.",
                        "Correct the header names of the collection request" + from(call) + ". Nothing was sent.");
            }
            if (header.value().chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
                throw new BadInputException("The value of the header " + header.name() + " contains a line break, which HTTP does not allow.",
                        "Correct that header on the collection request" + from(call) + ". Nothing was sent.");
            }
        }
        if (gateway != null && gateway.epoch() != call.epoch()) {
            throw new ConfigurationChangedException();
        }
    }

    private static String from(PreparedCall call) {
        return Texts.present(call.requestName()) ? " \"" + call.requestName() + "\"" : "";
    }

    /** The HTTP method a call will use, upper case. */
    public static String method(PreparedCall call) {
        return Texts.orElse(call.method(), call.operation().defaultMethod()).strip().toUpperCase(Locale.ROOT);
    }

    /** True when the call carries a request body: a body was supplied and the method takes one. */
    public static boolean carriesBody(PreparedCall call, ResolvedText body) {
        return body != null && body.value() != null && !BODYLESS_METHODS.contains(method(call));
    }

    /**
     * The headers a call is sent with, keyed by lower-case name: the operation's defaults, replaced
     * by any header of the same name on the collection request, plus a bearer token when one is
     * supplied and the collection request does not already carry an Authorization header. The
     * headers the HTTP client writes itself are left out: see {@link #withheld(PreparedCall)}.
     */
    public static Map<String, ResolvedHeader> headers(PreparedCall call, ResolvedText body, ExchangeOptions options) {
        boolean hasBody = carriesBody(call, body);
        Map<String, ResolvedHeader> merged = new LinkedHashMap<>();
        if (options != null) {
            for (ResolvedHeader header : options.defaultHeaders()) {
                if (hasBody || !header.name().equalsIgnoreCase("Content-Type")) {
                    merged.put(header.name().toLowerCase(Locale.ROOT), header);
                }
            }
        }
        for (ResolvedHeader header : call.headers()) {
            if (Texts.present(header.name()) && !TRANSPORT_HEADERS.contains(header.name().strip().toLowerCase(Locale.ROOT))) {
                // An unresolved header stays in the map with no value, so the mock's wire view can
                // show it as having none. Nothing with an unresolved header is ever sent.
                merged.put(header.name().toLowerCase(Locale.ROOT), header);
            }
        }
        if (options != null && Texts.present(options.bearerToken()) && !merged.containsKey("authorization")) {
            merged.put("authorization", new ResolvedHeader("Authorization", Secrets.REDACTED_BEARER, "Bearer " + options.bearerToken()));
        }
        return merged;
    }

    /** The names of the collection request's headers that are not sent, because the HTTP client writes them itself. */
    public static List<String> withheld(PreparedCall call) {
        List<String> names = new ArrayList<>();
        for (ResolvedHeader header : call.headers()) {
            if (Texts.present(header.name()) && TRANSPORT_HEADERS.contains(header.name().strip().toLowerCase(Locale.ROOT))) {
                names.add(header.name().strip());
            }
        }
        return names;
    }

    /**
     * The request as the wire view shows it.
     *
     * @param sent false when nothing left this machine
     * @param note why nothing was sent, or null
     */
    public static WireRequest wireRequest(PreparedCall call, ResolvedText body, ExchangeOptions options, boolean sent, String note) {
        Map<String, ResolvedHeader> headers = headers(call, body, options);
        boolean carriesBody = carriesBody(call, body);
        // Worked out here, where the request is described, so that every description of a call
        // knows which values the response to it must not be shown with. The mock builds its
        // requests through this method too, so it masks exactly what live would.
        SentSecrets secrets = CallSecrets.of(call, carriesBody ? body : null, headers, bearerTokenIn(headers, options));
        List<WireHeader> shown = new ArrayList<>();
        for (ResolvedHeader header : headers.values()) {
            // A header that is a credential by its own name is redacted whatever its value was built
            // from, which covers a credential typed literally into a collection as well as one that
            // came from a secret variable. WireHeader applies the same rule again by itself.
            String value = !header.resolved() ? "(no value)"
                    : Secrets.credentialHeader(header.name()) ? Secrets.redactCredential(header.name(), header.value()) : header.display();
            shown.add(new WireHeader(header.name(), value));
        }
        List<String> withheld = withheld(call);
        String about = withheld.isEmpty() ? note
                : (note == null ? "" : note + " ") + "Not sent as written on the collection request: " + String.join(", ", withheld)
                + ". The HTTP client writes " + (withheld.size() == 1 ? "this header" : "these headers") + " itself.";
        return new WireRequest(method(call), call.url().display(), call.url().template(), shown,
                carriesBody ? body.display() : null, sent, about, secrets);
    }

    /**
     * The access token when it is among the headers a call is sent with, otherwise null. A token
     * that was supplied and left off, because the collection request carries an Authorization
     * header of its own, was not sent and is not a secret of this request.
     */
    private static String bearerTokenIn(Map<String, ResolvedHeader> headers, ExchangeOptions options) {
        String token = options == null ? null : options.bearerToken();
        ResolvedHeader authorization = headers.get("authorization");
        return Texts.present(token) && authorization != null && ("Bearer " + token).equals(authorization.value()) ? token : null;
    }

    /**
     * Describes a response that arrived.
     *
     * @param body         the body as received, or null when there was none
     * @param bodyRequired true when a success status with an empty body is a malformed response
     */
    public static VendorExchange fromResponse(WireRequest request, int status, List<WireHeader> responseHeaders, String body,
                                              long elapsedMillis, boolean bodyRequired) {
        return describe(request, status, responseHeaders, body, null, List.of(), elapsedMillis, bodyRequired);
    }

    /**
     * Describes a response from the bytes that arrived. The text is decoded with the charset the
     * Content-Type names, otherwise as UTF-8. Bytes that are not valid in that charset are not
     * replaced in silence: the body is still shown, and a note says how it was decoded, because a
     * member's name that changed on the way to the screen must not look like what the service sent.
     *
     * @param contentType the Content-Type header of the response, or null
     */
    public static VendorExchange fromBytes(WireRequest request, int status, List<WireHeader> responseHeaders, byte[] raw,
                                           String contentType, long elapsedMillis, boolean bodyRequired) {
        Decoded decoded = decode(raw, contentType);
        return describe(request, status, responseHeaders, decoded.text(), raw == null ? 0L : (long) raw.length, decoded.notes(),
                elapsedMillis, bodyRequired);
    }

    /**
     * Describes a response that started and did not finish: the status and headers arrived, and the
     * connection ended or the read timeout passed while the body was still coming. The status and
     * whatever arrived are kept, because "the service answered 200 and then stopped" is a different
     * finding from "the service could not be reached".
     */
    public static VendorExchange interrupted(WireRequest request, int status, List<WireHeader> responseHeaders, byte[] soFar,
                                             String contentType, long elapsedMillis) {
        Decoded decoded = decode(soFar, contentType);
        long count = soFar == null ? 0L : soFar.length;
        boolean success = status >= 200 && status < 300;
        OnyxFailureType failure = status >= 500 ? OnyxFailureType.SERVER_ERROR
                : success ? OnyxFailureType.MALFORMED_RESPONSE : OnyxFailureType.BAD_REQUEST;
        String detail = "The response started with HTTP " + status + " and " + count + " bytes of its body arrived before the "
                + "connection ended or the read timeout passed.";
        List<String> notes = new ArrayList<>(decoded.notes());
        notes.add("The body is incomplete: " + detail);
        return new VendorExchange(request, status, responseHeaders, Texts.present(decoded.text()) ? decoded.text() : null, count, null,
                INCOMPLETE_BODY, elapsedMillis, failure, detail, Set.of(), null, null, notes);
    }

    private record Decoded(String text, List<String> notes) {
    }

    private static Decoded decode(byte[] raw, String contentType) {
        if (raw == null || raw.length == 0) {
            return new Decoded(null, List.of());
        }
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        boolean named = false;
        if (contentType != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._:-]+)").matcher(contentType);
            if (m.find()) {
                try {
                    charset = java.nio.charset.Charset.forName(m.group(1));
                    named = true;
                } catch (IllegalArgumentException e) {
                    // A charset this JVM does not know: fall back to UTF-8 and say so below if it does not fit.
                    charset = StandardCharsets.UTF_8;
                }
            }
        }
        try {
            String text = charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            return new Decoded(text, List.of());
        } catch (java.nio.charset.CharacterCodingException e) {
            String text = new String(raw, charset);
            long replaced = text.chars().filter(c -> c == 0xFFFD).count();
            return new Decoded(text, List.of("The body is not valid " + charset.name() + (named ? ", the charset its Content-Type names" : "")
                    + ". " + replaced + " character" + (replaced == 1 ? "" : "s") + " that could not be read "
                    + (replaced == 1 ? "is" : "are") + " shown as a replacement mark, so the text below is not exactly what was sent."));
        }
    }

    private static VendorExchange describe(WireRequest request, int status, List<WireHeader> responseHeaders, String body,
                                           Long responseBytes, List<String> decodeNotes, long elapsedMillis, boolean bodyRequired) {
        String text = body == null || body.isEmpty() ? null : body;
        JsonSafe.Parsed parsed = text == null ? null : JsonSafe.parse(text);
        boolean success = status >= 200 && status < 300;
        OnyxFailureType failure;
        String detail = null;
        String parseError = parsed != null && !parsed.ok() ? parsed.error() : null;
        if (status >= 500) {
            failure = OnyxFailureType.SERVER_ERROR;
            detail = "The service answered HTTP " + status + ".";
        } else if (!success) {
            // 4xx, and also 1xx and 3xx: in each case the request as sent was not handled, and the
            // fix is on the sending side. A redirect usually means the configured url is wrong.
            failure = OnyxFailureType.BAD_REQUEST;
            detail = "The service answered HTTP " + status + ".";
        } else if (text == null) {
            if (bodyRequired) {
                failure = OnyxFailureType.MALFORMED_RESPONSE;
                parseError = "The body is empty.";
                detail = "The service answered HTTP " + status + " with an empty body.";
            } else {
                failure = OnyxFailureType.NONE;
            }
        } else if (!parsed.ok()) {
            failure = OnyxFailureType.MALFORMED_RESPONSE;
            detail = "The service answered HTTP " + status + " with a body that is not JSON.";
        } else {
            failure = OnyxFailureType.NONE;
        }
        List<String> notes = new ArrayList<>(decodeNotes);
        if (parsed != null && parsed.ok() && parsed.repeatedKeys()) {
            // A designed view puts every value of each repeated key in place of this note; a view
            // that does not has read the last value alone, which is what this says.
            notes.add("The body repeats a key inside one object. This tool reads only the last value of a repeated key, as JSON "
                    + "readers do. The body as received, with every value, is in the wire view.");
        }
        return new VendorExchange(request, status, responseHeaders, text, responseBytes, parsed != null && parsed.ok() ? parsed.node() : null,
                parseError, elapsedMillis, failure, detail, Set.of(), null, null, notes);
    }

    /**
     * Describes a call that got no response inside the read timeout. The connection was open and
     * the request had gone out, so the service may have received it: whether it did cannot be said.
     */
    public static VendorExchange timeout(WireRequest request, long elapsedMillis) {
        return failed(request, elapsedMillis, OnyxFailureType.TIMEOUT, "No response arrived inside the read timeout.", Reached.CANNOT_SAY);
    }

    /**
     * Describes a call that never reached the service: the connection could not be opened, or
     * nothing was sent at all. For a connection that failed after it was opened use {@link #cutOff},
     * because only this one tells a tester that a write can be sent again.
     */
    public static VendorExchange unavailable(WireRequest request, long elapsedMillis, String detail) {
        return failed(request, elapsedMillis, OnyxFailureType.UNAVAILABLE, detail, Reached.NO);
    }

    /**
     * Describes a call whose connection failed after it was opened, with no response. The request
     * may have gone out before it failed, so whether the service received it cannot be said.
     */
    public static VendorExchange cutOff(WireRequest request, long elapsedMillis, String detail) {
        return failed(request, elapsedMillis, OnyxFailureType.UNAVAILABLE, detail, Reached.CANNOT_SAY);
    }

    static VendorExchange failed(WireRequest request, long elapsedMillis, OnyxFailureType type, String detail, Reached reached) {
        return new VendorExchange(request, null, List.of(), null, null, null, null, elapsedMillis, type, detail, Set.of(), null, null,
                List.of(), reached);
    }
}
