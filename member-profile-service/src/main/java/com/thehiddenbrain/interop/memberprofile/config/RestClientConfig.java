package com.thehiddenbrain.interop.memberprofile.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    RestClient memberDomainRestClient(MemberProfileProperties properties) {
        MemberProfileProperties.MemberDomain md = properties.memberDomain();
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(md.connectTimeout())
                .withReadTimeout(md.readTimeout());
        return RestClient.builder()
                .baseUrl(md.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
