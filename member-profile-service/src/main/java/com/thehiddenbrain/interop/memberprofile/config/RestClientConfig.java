package com.thehiddenbrain.interop.memberprofile.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    RestClient memberDomainRestClient(MemberProfileProperties properties) {
        MemberProfileProperties.MemberDomain md = properties.memberDomain();
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(md.connectTimeout())
                .withReadTimeout(md.readTimeout());
        return RestClient.builder()
                .baseUrl(md.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
