package org.point32health.memberid.config;

import tools.jackson.databind.ObjectMapper;
import org.point32health.memberid.mmi.MmiClient;
import org.point32health.memberid.mmi.MmiProperties;
import org.point32health.memberid.mmi.RestMmiClient;
import org.point32health.memberid.mmi.StubMmiClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

/** One MMI client: the in-process stub when {@code mmi.stub.enabled} is true (dev and test only), otherwise HTTP. */
@Configuration
public class MmiClientConfig {

    @Bean
    public MmiClient mmiClient(MmiProperties properties, Environment environment, ResourceLoader resourceLoader,
            ObjectMapper objectMapper, RestClient.Builder builder) {
        if (properties.stub().enabled()) {
            return new StubMmiClient(environment, resourceLoader, objectMapper, properties);
        }
        RestClient.Builder withTimeouts = builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                HttpClientSettings.defaults()
                        .withConnectTimeout(properties.connectTimeout())
                        .withReadTimeout(properties.readTimeout())));
        return new RestMmiClient(withTimeouts, properties);
    }
}
