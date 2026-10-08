package org.p32h.interop.config;

import tools.jackson.databind.ObjectMapper;
import org.p32h.interop.memberinfo.MemberInfoClient;
import org.p32h.interop.memberinfo.MemberInfoProperties;
import org.p32h.interop.memberinfo.StubMemberInfoClient;
import org.p32h.interop.memberinfo.WebClientMemberInfoClient;
import org.p32h.interop.service.LineOfBusinessDeriver;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One member information client: the in-process stub when {@code member-info.stub.enabled} is true (DEV and test only),
 * otherwise WebClient with the {@code member-info} timeouts. Also the line-of-business deriver the resolution uses.
 */
@Configuration
public class MemberInfoClientConfig {

    @Bean
    public MemberInfoClient memberInfoClient(MemberInfoProperties properties, Environment environment, ResourceLoader resourceLoader,
            ObjectMapper objectMapper, WebClient.Builder builder) {
        if (properties.stub().enabled()) {
            return new StubMemberInfoClient(environment, resourceLoader, objectMapper, properties);
        }
        WebClient.Builder withTimeouts = builder.clone().clientConnector(ClientHttpConnectorBuilder.detect().build(
                HttpClientSettings.defaults()
                        .withConnectTimeout(properties.connectTimeout())
                        .withReadTimeout(properties.readTimeout())));
        return new WebClientMemberInfoClient(withTimeouts, properties, objectMapper);
    }

    @Bean
    public LineOfBusinessDeriver lineOfBusinessDeriver() {
        return new LineOfBusinessDeriver();
    }
}
