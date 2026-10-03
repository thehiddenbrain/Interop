package com.thehiddenbrain.interop.memberid.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.memberid.mmi.MmiClient;
import com.thehiddenbrain.interop.memberid.mmi.MmiProperties;
import com.thehiddenbrain.interop.memberid.mmi.RestMmiClient;
import com.thehiddenbrain.interop.memberid.mmi.StubMmiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

@Configuration
public class MmiClientConfig {

    @Bean
    @ConditionalOnProperty(name = "mmi.stub.enabled", havingValue = "true")
    public MmiClient stubMmiClient(Environment environment, ResourceLoader resourceLoader, ObjectMapper objectMapper,
            MmiProperties properties) {
        return new StubMmiClient(environment, resourceLoader, objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "mmi.stub.enabled", havingValue = "false", matchIfMissing = true)
    public MmiClient restMmiClient(RestClient.Builder builder, MmiProperties properties) {
        return new RestMmiClient(builder, properties);
    }
}
