package org.p32h.interop.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI interopOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Interop Resolution Service")
                .version("v1")
                .description("Onyx's interop lookups. Member resolution, POST /v1/interop/resolve: resolves an EMR-supplied member ID "
                        + "against the plan's member records, reports whether coverage is active on the date of service, and returns the ID as "
                        + "resolved and in every UM vendor's format."));
    }
}
