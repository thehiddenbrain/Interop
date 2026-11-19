package org.p32h.interop.epa.pave.client.support;

import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
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
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** @param interrupted true when the status and headers arrived and the body did not finish */
    private record Raw(int status, HttpHeaders headers, byte[] body, boolean interrupted) {
    }

    private record Failure(OnyxFailureType type, String detail) {
    }

    private final ObjectProvider<OperationGateway> gateway;

    /** For tests that exercise the HTTP layer alone. No configuration epoch is checked. */
    public ExchangeExecutor() {
        this.gateway = null;
    }

    @Autowired
    public ExchangeExecutor(ObjectProvider<OperationGateway> gateway) {
        this.gateway = gateway;
    }

    public VendorExchange execute(RestClient client, PreparedCall call, ResolvedText body, ExchangeOptions options) {
        if (call == null || call.url() == null || !call.url().resolved()) {
            throw new EndpointNotConfiguredException(call == null ? null : call.operation(),
                    "There is no complete url to send this request to.");
        }
        Exchanges.requireSendable(call, body, gateway == null ? null : gateway.getIfAvailable());
        URI uri;
        try {
            uri = URI.create(call.url().value().strip());
        } catch (IllegalArgumentException e) {
            throw new EndpointNotConfiguredException(call.operation(),
                    "The url for " + call.operation().label() + " resolved to something that is not a valid url. Its template is "
                            + call.url().template());
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new EndpointNotConfiguredException(call.operation(),
                    "The url for " + call.operation().label() + " has no scheme or host. Its template is " + call.url().template());
        }

        String method = Exchanges.method(call);
        boolean hasBody = Exchanges.carriesBody(call, body);
        Map<String, ResolvedHeader> headers = Exchanges.headers(call, body, options);
        WireRequest wireRequest = Exchanges.wireRequest(call, body, options, true, null);

        // Built before the clock starts. Whatever is wrong with the method or a header was refused
        // above, by name, so nothing in the timed section can be the request's own fault.
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method)).uri(uri)
                .headers(out -> headers.values().forEach(h -> out.set(h.name(), h.value())));
        if (hasBody) {
            spec.body(body.value().getBytes(StandardCharsets.UTF_8));
        }

        long started = System.nanoTime();
        Raw raw;
        try {
            raw = spec.exchange((request, response) -> {
                // The status and headers are taken first. If the body then fails to arrive they are
                // still facts about the call, and the bytes that did arrive are kept with them.
                int status = response.getStatusCode().value();
                HttpHeaders responseHeaders = response.getHeaders();
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                try (InputStream in = response.getBody()) {
                    byte[] chunk = new byte[8192];
                    for (int read = in.read(chunk); read >= 0; read = in.read(chunk)) {
                        received.write(chunk, 0, read);
                    }
                    return new Raw(status, responseHeaders, received.toByteArray(), false);
                } catch (IOException | RuntimeException e) {
                    return new Raw(status, responseHeaders, received.toByteArray(), true);
                }
            });
        } catch (RuntimeException e) {
            // Only the send is inside this try. A defect in describing the response must surface as
            // a defect, not as "the service could not be reached".
            Failure failure = classify(e);
            return Exchanges.failed(wireRequest, elapsedSince(started), failure.type(), failure.detail());
        }
        long elapsed = elapsedSince(started);
        List<WireHeader> responseHeaders = new ArrayList<>();
        raw.headers().forEach((name, values) -> values.forEach(value -> responseHeaders.add(new WireHeader(name, value))));
        String contentType = raw.headers().getFirst(HttpHeaders.CONTENT_TYPE);
        if (raw.interrupted()) {
            return Exchanges.interrupted(wireRequest, raw.status(), responseHeaders, raw.body(), contentType, elapsed);
        }
        return Exchanges.fromBytes(wireRequest, raw.status(), responseHeaders, raw.body(), contentType, elapsed,
                options != null && options.bodyRequired());
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
