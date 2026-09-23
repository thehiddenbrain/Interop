package com.thehiddenbrain.interop.memberprofile.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /** The MemberDomain client: Boot's auto-configured builder with this service's timeouts. */
    @Bean
    RestClient memberDomainRestClient(RestClient.Builder builder, MemberProfileProperties properties) {
        MemberProfileProperties.MemberDomain md = properties.memberDomain();
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(md.connectTimeout())
                .withReadTimeout(md.readTimeout());
        return builder
                .baseUrl(md.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
