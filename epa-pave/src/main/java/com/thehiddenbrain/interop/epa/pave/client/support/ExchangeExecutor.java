package com.thehiddenbrain.interop.epa.pave.client.support;

import com.thehiddenbrain.interop.epa.pave.model.JsonSafe;
import com.thehiddenbrain.interop.epa.pave.model.OnyxFailureType;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedHeader;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.Secrets;
import com.thehiddenbrain.interop.epa.pave.model.Texts;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.model.WireHeader;
import com.thehiddenbrain.interop.epa.pave.model.WireRequest;
import com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall;
import com.thehiddenbrain.interop.epa.pave.service.error.EndpointNotConfiguredException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends one prepared call and describes what happened, for every live client.
 *
 * <p>All live clients share this so that a timeout, a refused connection, an HTTP error and an
 * unreadable body are classified the same way everywhere. It never throws for anything the far
 * side did. It throws only when the call cannot be attempted at all, which is a configuration
 * problem and is reported before anything is sent.
 *
 * <p>Nothing here logs. The url can hold a member ID and the bodies hold member data, and an
 * exception message from the HTTP client quotes the url, so no exception message is ever copied
 * into the result either: failure details are fixed phrases chosen by exception type.
 */
@Component
public class ExchangeExecutor {

    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "DELETE", "OPTIONS");

    private record Raw(int status, HttpHeaders headers, byte[] body) {
    }

    private record Failure(OnyxFailureType type, String detail) {
    }

    public VendorExchange execute(RestClient client, PreparedCall call, ResolvedText body, ExchangeOptions options) {
        if (call == null || call.url() == null || !call.url().resolved()) {
            throw new EndpointNotConfiguredException(call == null ? null : call.operation(),
                    "There is no complete url to send this request to.");
        }
        URI uri;
        try {
            uri = URI.create(call.url().value().strip());
        } catch (IllegalArgumentException e) {
            throw new EndpointNotConfiguredException(call.operation(),
                    "The url for " + call.operation().label() + " resolved to something that is not a valid url: " + call.url().display());
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new EndpointNotConfiguredException(call.operation(),
                    "The url for " + call.operation().label() + " has no scheme or host: " + call.url().display());
        }

        String method = Texts.orElse(call.method(), call.operation().defaultMethod()).strip().toUpperCase(Locale.ROOT);
        boolean hasBody = body != null && body.value() != null && !BODYLESS_METHODS.contains(method);
        Map<String, ResolvedHeader> headers = mergeHeaders(call, options, hasBody);
        WireRequest wireRequest = new WireRequest(method, call.url().display(), call.url().template(), display(headers),
                hasBody ? body.display() : null, true, null);

        long started = System.nanoTime();
        try {
            RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method)).uri(uri)
                    .headers(out -> headers.values().forEach(h -> out.set(h.name(), h.value())));
            if (hasBody) {
                spec.body(body.value().getBytes(StandardCharsets.UTF_8));
            }
            Raw raw = spec.exchange((request, response) ->
                    new Raw(response.getStatusCode().value(), response.getHeaders(), response.getBody().readAllBytes()));
            return describe(wireRequest, raw, elapsedSince(started), options);
        } catch (RuntimeException e) {
            Failure failure = classify(e);
            return new VendorExchange(wireRequest, null, List.of(), null, null, null, elapsedSince(started), failure.type(),
                    failure.detail(), Set.of());
        }
    }

    private static VendorExchange describe(WireRequest request, Raw raw, long elapsedMillis, ExchangeOptions options) {
        String text = raw.body() == null || raw.body().length == 0 ? null : new String(raw.body(), StandardCharsets.UTF_8);
        List<WireHeader> responseHeaders = new ArrayList<>();
        raw.headers().forEach((name, values) -> values.forEach(value ->
                responseHeaders.add(new WireHeader(name, Secrets.credentialHeader(name) ? Secrets.REDACTED : value))));

        JsonSafe.Parsed parsed = text == null ? null : JsonSafe.parse(text);
        boolean success = raw.status() >= 200 && raw.status() < 300;
        OnyxFailureType failure;
        String detail = null;
        String parseError = parsed != null && !parsed.ok() ? parsed.error() : null;
        if (raw.status() >= 500) {
            failure = OnyxFailureType.SERVER_ERROR;
            detail = "The service answered HTTP " + raw.status() + ".";
        } else if (!success) {
            // 4xx, and also 1xx and 3xx: in each case the request as sent was not handled, and the
            // fix is on the sending side. A redirect usually means the configured url is wrong.
            failure = OnyxFailureType.BAD_REQUEST;
            detail = "The service answered HTTP " + raw.status() + ".";
        } else if (text == null) {
            if (options != null && options.bodyRequired()) {
                failure = OnyxFailureType.MALFORMED_RESPONSE;
                parseError = "The body is empty.";
                detail = "The service answered HTTP " + raw.status() + " with an empty body.";
            } else {
                failure = OnyxFailureType.NONE;
            }
        } else if (!parsed.ok()) {
            failure = OnyxFailureType.MALFORMED_RESPONSE;
            detail = "The service answered HTTP " + raw.status() + " with a body that is not JSON.";
        } else {
            failure = OnyxFailureType.NONE;
        }
        return new VendorExchange(request, raw.status(), responseHeaders, text, parsed != null && parsed.ok() ? parsed.node() : null,
                parseError, elapsedMillis, failure, detail, Set.of());
    }

    private static Map<String, ResolvedHeader> mergeHeaders(PreparedCall call, ExchangeOptions options, boolean hasBody) {
        Map<String, ResolvedHeader> merged = new LinkedHashMap<>();
        if (options != null) {
            for (ResolvedHeader header : options.defaultHeaders()) {
                if (hasBody || !header.name().equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) {
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
            merged.put("authorization", new ResolvedHeader(HttpHeaders.AUTHORIZATION, Secrets.REDACTED_BEARER,
                    "Bearer " + options.bearerToken()));
        }
        return merged;
    }

    /**
     * The headers as the wire view shows them. A header that is a credential by its own name is
     * redacted whatever its value was built from, which covers a credential typed literally into a
     * collection as well as one that came from a secret variable.
     */
    private static List<WireHeader> display(Map<String, ResolvedHeader> headers) {
        List<WireHeader> out = new ArrayList<>();
        for (ResolvedHeader header : headers.values()) {
            out.add(new WireHeader(header.name(), Secrets.credentialHeader(header.name()) ? redactCredential(header.value()) : header.display()));
        }
        return out;
    }

    private static String redactCredential(String value) {
        if (value == null) {
            return Secrets.REDACTED;
        }
        int space = value.strip().indexOf(' ');
        // Keeping the scheme word tells a tester whether Basic or Bearer was sent without showing the credential.
        return space > 0 ? value.strip().substring(0, space) + " " + Secrets.REDACTED : Secrets.REDACTED;
    }

    private static long elapsedSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** Decides what kind of transport failure an exception is, from its types alone. */
    static OnyxFailureType classifyType(Throwable error) {
        return classify(error).type();
    }

    private static Failure classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // The connect timeout is a subtype of the request timeout, so it is tested first: a
            // connection that never opened means the service was not reached at all.
            if (t instanceof HttpConnectTimeoutException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "The connection attempt timed out before the service was reached.");
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return new Failure(OnyxFailureType.TIMEOUT, "No response arrived inside the read timeout.");
            }
            if (t instanceof ConnectException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "The connection was refused or could not be opened.");
            }
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "The host name could not be resolved.");
            }
            if (t instanceof NoRouteToHostException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "There is no network route to the host.");
            }
            if (t instanceof SSLException) {
                return new Failure(OnyxFailureType.UNAVAILABLE,
                        "The TLS handshake failed. On a network with an inspection proxy, check the trust store.");
            }
        }
        return new Failure(OnyxFailureType.UNAVAILABLE, "The request could not be completed (" + error.getClass().getSimpleName() + ").");
    }
}
