package com.thehiddenbrain.interop.memberprofile.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI memberProfileOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Member Profile Service")
                .version("v1")
                .description("Member identity, segmentation flags and family permissions for the League member portal, "
                        + "evaluated on demand from the MemberDomain service and the rule tables."));
    }
}
