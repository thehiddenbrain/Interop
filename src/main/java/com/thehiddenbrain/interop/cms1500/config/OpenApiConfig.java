package com.thehiddenbrain.interop.cms1500.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(info = @Info(
        title = "CMS-1500 Claim Bundle Service",
        version = "v1",
        description = "Fills the CMS-1500 (02/12) claim form from a JSON claim and bundles it with the claim's "
                + "attachments from the shared drive into <claimNumber>.pdf. The same operation is available over "
                + "SOAP at /ws (WSDL: /ws/cms1500.wsdl)."))
@Configuration
public class OpenApiConfig {
}
