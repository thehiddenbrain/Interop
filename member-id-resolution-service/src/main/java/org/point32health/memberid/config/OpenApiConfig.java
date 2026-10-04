package org.point32health.memberid.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI memberIdOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Member ID Resolution Service")
                .version("v1")
                .description("Resolves an EMR-supplied member ID through MMI, returns it as stored and in the "
                        + "UM vendor's format (/resolve) or in every vendor's format (/vendor-map), and reports whether "
                        + "coverage is active on the date of service."));
    }
}
