package com.thehiddenbrain.interop.patientaccess.fhir;

import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One {@link HttpClient} per environment (timeouts, optional trust-all TLS for non-PROD sandboxes). */
@Component
public class HttpClientFactory {

    private final WorkbenchProperties properties;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private final HttpClient plain;

    public HttpClientFactory(WorkbenchProperties properties) {
        this.properties = properties;
        this.plain = HttpClient.newBuilder()
                .connectTimeout(properties.http().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Client used for calls not tied to an environment (none today; kept for completeness). */
    public HttpClient plain() {
        return plain;
    }

    public HttpClient forEnvironment(Environment environment) {
        boolean trustAll = environment.fhir().trustAllCertificates() && environment.tier() != EnvironmentTier.PROD;
        Duration connect = environment.fhir().connectTimeoutMs() != null
                ? Duration.ofMillis(environment.fhir().connectTimeoutMs()) : properties.http().connectTimeout();
        String key = environment.id() + "|" + trustAll + "|" + connect.toMillis();
        return clients.computeIfAbsent(key, k -> {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(connect)
                    .followRedirects(HttpClient.Redirect.NEVER);
            if (trustAll) {
                builder.sslContext(trustAllContext());
            }
            return builder.build();
        });
    }

    public Duration readTimeout(Environment environment) {
        return environment.fhir().readTimeoutMs() != null
                ? Duration.ofMillis(environment.fhir().readTimeoutMs()) : properties.http().readTimeout();
    }

    public void evict(String environmentId) {
        clients.keySet().removeIf(k -> k.startsWith(environmentId + "|"));
    }

    private static SSLContext trustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build trust-all SSL context", e);
        }
    }
}
