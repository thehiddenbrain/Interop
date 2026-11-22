package org.p32h.interop.epa.pave.client.support;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds a RestClient with explicit timeouts. */
public final class RestClients {

    /** How many pairs of timeouts one builder keeps a client for. Environments state a few at most; a bound keeps a stream of edits from growing it. */
    private static final int CLIENTS_PER_BUILDER = 16;

    /**
     * Clients by the builder they came from, then by their pair of timeouts. Keyed weakly on the
     * builder, since each bean is given a builder of its own and a test builds many, and a client
     * of one builder must never answer for another (a test binds a mock server to its builder).
     */
    private static final Map<RestClient.Builder, Map<Long, RestClient>> CACHED = new WeakHashMap<>();

    private RestClients() {
    }

    /**
     * A client on the JDK HTTP client. That client is used because it reports a connect timeout and
     * a read timeout as different exception types, which is what lets the executor tell "could not
     * be reached" from "did not answer in time" without reading exception messages.
     *
     * <p>Redirects are not followed: a redirect from a prior authorization endpoint is a sign the
     * configured url is wrong, and following it would hide that. HTTP/1.1 is pinned so behaviour
     * does not depend on whether the far side negotiates HTTP/2.
     */
    public static RestClient withTimeouts(RestClient.Builder builder, int connectTimeoutMs, int readTimeoutMs) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        // Off, so the client adds no Accept-Encoding header of its own and the bytes shown in the
        // wire view are the bytes that arrived, not a decompressed form of them.
        factory.enableCompression(false);
        return builder.clone().requestFactory(factory).build();
    }

    /**
     * A client with these timeouts, built once per builder and pair and then reused, so a call that
     * carries its environment's timeouts does not open a new connection pool each time (plan 5.2).
     * A value below one is taken as one millisecond, since the JDK client refuses zero and a
     * negative number, and the gateway never hands either.
     */
    public static RestClient cached(RestClient.Builder builder, int connectMs, int readMs) {
        int connect = Math.max(1, connectMs);
        int read = Math.max(1, readMs);
        long key = ((long) connect << 32) | (read & 0xffffffffL);
        synchronized (CACHED) {
            Map<Long, RestClient> clients = CACHED.computeIfAbsent(builder, b -> new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, RestClient> eldest) {
                    return size() > CLIENTS_PER_BUILDER;
                }
            });
            return clients.computeIfAbsent(key, k -> withTimeouts(builder, connect, read));
        }
    }
}
