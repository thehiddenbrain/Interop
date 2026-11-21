package org.p32h.interop.epa.pave.client.support;

import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Reached;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.postman.UrlNormal;
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
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.p32h.interop.epa.pave.service.error.Faults;

/**
 * Sends one prepared call and describes what happened, for every live client.
 *
 * <p>All live clients share this so that a timeout, a refused connection, an HTTP error and an
 * unreadable body are classified the same way everywhere. It never throws for anything the far
 * side did. It throws only when the call cannot be attempted at all, which is a configuration
 * problem and is reported before anything is sent.
 *
 * <p>The url can hold a member ID and the bodies hold member data, and an exception message from
 * the HTTP client quotes the url, so no exception message is ever copied into the result or the
 * log: failure details are fixed phrases chosen by exception type. The one thing logged is a
 * failure this tool could not name in plain words, with the host it was sent to and the exception
 * types, so that whoever looks after the installation can find out what happened.
 */
@Component
public class ExchangeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExchangeExecutor.class);

    /** @param interrupted true when the status and headers arrived and the body did not finish */
    private record Raw(int status, HttpHeaders headers, byte[] body, boolean interrupted) {
    }

    /**
     * @param reached whether the request can have reached the service before the call failed
     * @param unnamed true when the detail is the general sentence for a failure of a kind this
     *                tool does not recognise, so its exception types are worth a line in the log
     */
    private record Failure(OnyxFailureType type, String detail, Reached reached, boolean unnamed) {

        Failure(OnyxFailureType type, String detail, Reached reached) {
            this(type, detail, reached, false);
        }
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
        // Parsed by the one function the guardrail reads destinations with, so the address judged
        // and the address the client connects to cannot differ.
        URI uri = UrlNormal.sendable(call.url().value());
        if (uri == null) {
            UrlNormal.Destination read = UrlNormal.destination(call.url().value());
            throw new EndpointNotConfiguredException(call.operation(), read == null || read.scheme() == null
                    ? "The url for " + call.operation().label() + " has no scheme or host the HTTP client can read. Its template is "
                    + call.url().template()
                    : UrlNormal.authorityReadable(call.url().value())
                    ? "The url for " + call.operation().label() + " as filled is not a valid url: its scheme, host and port read well, "
                    + "and the text after them holds a character that cannot stand in a url as it is, such as a space or a percent sign "
                    + "that starts no escape. Its template is " + call.url().template()
                    : "The address of the url for " + call.operation().label() + " could not be read as the HTTP client reads it: "
                    + "its host or its port is not written in a form the client accepts. Its template is " + call.url().template());
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
            // A lookup client logs its outcome and its HTTP status and nothing else (specification
            // section 15), so this line is not written for a lookup, whose url is built on a member ID.
            if (failure.unnamed() && call.operation().protocol() != org.p32h.interop.epa.pave.model.Protocol.LOOKUP) {
                // The host and the exception types and frames only. Never the path, the query, a
                // header or a message, which can quote the url and with it a member ID.
                log.warn("A call to the host {} failed in a way the result does not name: {}", uri.getHost(), Faults.describe(e));
            }
            return Exchanges.failed(wireRequest, elapsedSince(started), failure.type(), failure.detail(), failure.reached());
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

    /**
     * True when the types say that a connection was open and ended without an answer: the far side
     * closed it (an end of stream) or reset it. A connection that never opened is not one, and
     * neither is a failure of a type this method does not name. Read from the types alone, with
     * the same walk along the causes as {@link #classify(Throwable)}.
     */
    static boolean closedWithoutAnswer(Throwable error) {
        if (classify(error).reached() == Reached.NO) {
            return false;
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // Exactly SocketException: its subtypes include a refused connection and a failed bind, which open nothing.
            if (t instanceof java.io.EOFException || t.getClass() == java.net.SocketException.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * What kind of failure an exception is, and whether the request can have reached the service.
     *
     * <p>"Did not reach it" is said only for a failure that happens before any of the request is
     * written: the name did not resolve, there is no route, the connection was refused or timed
     * out while opening, or the TLS handshake failed. Everything else, a failure this method does
     * not recognise included, leaves it open. The two errors do not cost the same: calling a
     * request that did arrive "not reached" invites a second prior authorization request, and
     * calling one that did not arrive "may have been reached" costs one status check.
     */
    private static Failure classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // Only the tool's stop interrupts a thread that waits for a service: the web server's
            // once its grace for requests in flight is over, and the suite's once its own is. The
            // request may have left, and no answer had come, which is not a failure of the service.
            if (t instanceof InterruptedException || t instanceof java.nio.channels.ClosedByInterruptException) {
                return new Failure(OnyxFailureType.TIMEOUT, STOPPED_WAITING, Reached.CANNOT_SAY);
            }
            // The connect timeout is a subtype of the request timeout, so it is tested first: a
            // connection that never opened means the service was not reached at all.
            if (t instanceof HttpConnectTimeoutException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "The connection attempt timed out before the service was reached.",
                        Reached.NO);
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return new Failure(OnyxFailureType.TIMEOUT, "No response arrived inside the read timeout.", Reached.CANNOT_SAY);
            }
            if (t instanceof ConnectException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "The connection was refused or could not be opened.", Reached.NO);
            }
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "The name of the service's host could not be found.", Reached.NO);
            }
            if (t instanceof NoRouteToHostException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "There is no network route to the host.", Reached.NO);
            }
            // The handshake comes before the request, so when it fails nothing of the request was sent.
            if (t instanceof SSLHandshakeException || t instanceof SSLPeerUnverifiedException) {
                return new Failure(OnyxFailureType.UNAVAILABLE,
                        "The certificate of the service was not accepted, or the secure connection could not be set up: the TLS "
                        + "handshake failed. On a network with an inspection proxy, check the trust store.", Reached.NO);
            }
            Failure tunnel = refusedTunnel(t);
            if (tunnel != null) {
                return tunnel;
            }
            // Any other TLS failure can come after the handshake, with the request already on its way.
            if (t instanceof SSLException) {
                return new Failure(OnyxFailureType.UNAVAILABLE, "The secure connection could not be set up, or failed after it "
                        + "was opened. On a network with an inspection proxy, check the trust store.", Reached.CANNOT_SAY, true);
            }
        }
        // A business tester cannot act on the name of a Java class, so it goes to the log, not the screen.
        return new Failure(OnyxFailureType.UNAVAILABLE, "The connection to the service failed before an answer arrived. This tool "
                + "cannot tell at which point, so the request may have reached the service. A proxy between this machine and the "
                + "service is a common cause.", Reached.CANNOT_SAY, true);
    }

    /** The detail of a call whose answer had not come when this tool was stopped. */
    static final String STOPPED_WAITING = "No answer had arrived when this tool was stopped, so whether the service received the "
            + "request is not known.";

    /** What the JDK's HTTP client says, and nothing else, when a proxy answers its CONNECT with anything but 200. */
    private static final java.util.regex.Pattern TUNNEL_FAILED = java.util.regex.Pattern.compile("Tunnel failed, got: (\\d{3})");

    /**
     * A proxy that refused to open the tunnel to the service. The JDK has no type of its own for
     * this, only a plain IOException with a fixed message, so the match is on that type and on the
     * start of that message. A refused CONNECT comes before any byte of the request, so the
     * service was not reached. Anything else is left to the caller, whose fallback never says
     * "not reached".
     */
    private static Failure refusedTunnel(Throwable t) {
        if (t.getClass() != java.io.IOException.class || t.getMessage() == null || !t.getMessage().startsWith("Tunnel failed")) {
            return null;
        }
        java.util.regex.Matcher status = TUNNEL_FAILED.matcher(t.getMessage());
        return new Failure(OnyxFailureType.UNAVAILABLE, "The proxy between this machine and the service refused the connection"
                + (status.lookingAt() ? " (it answered " + status.group(1) + ")" : "") + ". Check the proxy settings, or ask for "
                + "the service's host to be allowed through the proxy.", Reached.NO);
    }
}
