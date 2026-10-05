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
                .description("Onyx's interop lookups. Member ID resolution: resolves an EMR-supplied member ID against the plan's member records, returns it as stored and in the "
                        + "UM vendor's format (/resolve) or in every vendor's format (/vendor-map), and reports whether "
                        + "coverage is active on the date of service."));
    }
}
