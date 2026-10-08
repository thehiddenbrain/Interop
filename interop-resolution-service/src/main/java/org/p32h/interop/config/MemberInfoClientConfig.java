package org.p32h.interop.config;

import tools.jackson.databind.ObjectMapper;
import org.p32h.interop.memberinfo.MemberInfoClient;
import org.p32h.interop.memberinfo.MemberInfoProperties;
import org.p32h.interop.memberinfo.RestMemberInfoClient;
import org.p32h.interop.memberinfo.StubMemberInfoClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

/** One member information client: the in-process stub when {@code member-info.stub.enabled} is true (DEV and test only), otherwise HTTP. */
@Configuration
public class MemberInfoClientConfig {

    @Bean
    public MemberInfoClient memberInfoClient(MemberInfoProperties properties, Environment environment, ResourceLoader resourceLoader,
            ObjectMapper objectMapper, RestClient.Builder builder) {
        if (properties.stub().enabled()) {
            return new StubMemberInfoClient(environment, resourceLoader, objectMapper, properties);
        }
        RestClient.Builder withTimeouts = builder.clone().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                HttpClientSettings.defaults()
                        .withConnectTimeout(properties.connectTimeout())
                        .withReadTimeout(properties.readTimeout())));
        return new RestMemberInfoClient(withTimeouts, properties, objectMapper);
    }
}
