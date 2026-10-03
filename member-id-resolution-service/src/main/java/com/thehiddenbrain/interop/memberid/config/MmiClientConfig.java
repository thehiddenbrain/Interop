package com.thehiddenbrain.interop.memberid.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.memberid.mmi.MmiClient;
import com.thehiddenbrain.interop.memberid.mmi.MmiProperties;
import com.thehiddenbrain.interop.memberid.mmi.RestMmiClient;
import com.thehiddenbrain.interop.memberid.mmi.StubMmiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

/** One MMI client: the in-process stub when {@code mmi.stub.enabled} is true (local and test only), otherwise HTTP. */
@Configuration
public class MmiClientConfig {

    @Bean
    public MmiClient mmiClient(MmiProperties properties, Environment environment, ResourceLoader resourceLoader,
            ObjectMapper objectMapper, RestClient.Builder builder) {
        if (properties.stub().enabled()) {
            return new StubMmiClient(environment, resourceLoader, objectMapper, properties);
        }
        return new RestMmiClient(builder, properties);
    }
}
