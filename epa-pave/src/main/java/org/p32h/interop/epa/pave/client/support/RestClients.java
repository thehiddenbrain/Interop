package org.p32h.interop.epa.pave.client.support;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds a RestClient with explicit timeouts. */
public final class RestClients {

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
}
