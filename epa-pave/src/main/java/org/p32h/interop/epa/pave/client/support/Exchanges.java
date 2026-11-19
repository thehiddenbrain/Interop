package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Secrets;
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

    private Exchanges() {
    }

    /**
     * The two checks every call passes immediately before it is sent or answered, on both profiles.
     *
     * <p>A body that still holds a placeholder is refused. Without this check it would go out as no
     * body at all, and what the tester saw would be a vendor rejection with no visible cause.
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
        if (gateway != null && gateway.epoch() != call.epoch()) {
            throw new ConfigurationChangedException();
        }
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
     * supplied and the collection request does not already carry an Authorization header.
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
            if (Texts.present(header.name()) && header.value() != null) {
                merged.put(header.name().toLowerCase(Locale.ROOT), header);
            }
        }
        if (options != null && Texts.present(options.bearerToken()) && !merged.containsKey("authorization")) {
            merged.put("authorization", new ResolvedHeader("Authorization", Secrets.REDACTED_BEARER, "Bearer " + options.bearerToken()));
        }
        return merged;
    }

    /**
     * The request as the wire view shows it.
     *
     * @param sent false when nothing left this machine
     * @param note why nothing was sent, or null
     */
    public static WireRequest wireRequest(PreparedCall call, ResolvedText body, ExchangeOptions options, boolean sent, String note) {
        List<WireHeader> shown = new ArrayList<>();
        for (ResolvedHeader header : headers(call, body, options).values()) {
            // A header that is a credential by its own name is redacted whatever its value was built
            // from, which covers a credential typed literally into a collection as well as one that
            // came from a secret variable.
            shown.add(new WireHeader(header.name(), Secrets.credentialHeader(header.name()) ? redactCredential(header.value()) : header.display()));
        }
        return new WireRequest(method(call), call.url().display(), call.url().template(), shown,
                carriesBody(call, body) ? body.display() : null, sent, note);
    }

    /**
     * Describes a response that arrived.
     *
     * @param body         the body as received, or null when there was none
     * @param bodyRequired true when a success status with an empty body is a malformed response
     */
    public static VendorExchange fromResponse(WireRequest request, int status, List<WireHeader> responseHeaders, String body,
                                              long elapsedMillis, boolean bodyRequired) {
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
        return new VendorExchange(request, status, responseHeaders, text, parsed != null && parsed.ok() ? parsed.node() : null,
                parseError, elapsedMillis, failure, detail, Set.of(), null);
    }

    /** Describes a call that got no response inside the read timeout. */
    public static VendorExchange timeout(WireRequest request, long elapsedMillis) {
        return failed(request, elapsedMillis, OnyxFailureType.TIMEOUT, "No response arrived inside the read timeout.");
    }

    /** Describes a call that never reached the service. */
    public static VendorExchange unavailable(WireRequest request, long elapsedMillis, String detail) {
        return failed(request, elapsedMillis, OnyxFailureType.UNAVAILABLE, detail);
    }

    static VendorExchange failed(WireRequest request, long elapsedMillis, OnyxFailureType type, String detail) {
        return new VendorExchange(request, null, List.of(), null, null, null, elapsedMillis, type, detail, Set.of(), null);
    }

    private static String redactCredential(String value) {
        if (value == null) {
            return Secrets.REDACTED;
        }
        int space = value.strip().indexOf(' ');
        // Keeping the scheme word tells a tester whether Basic or Bearer was sent without showing the credential.
        return space > 0 ? value.strip().substring(0, space) + " " + Secrets.REDACTED : Secrets.REDACTED;
    }
}
