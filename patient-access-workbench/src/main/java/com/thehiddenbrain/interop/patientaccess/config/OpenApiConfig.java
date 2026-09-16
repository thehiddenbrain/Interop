package com.thehiddenbrain.interop.patientaccess.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(info = @Info(
        title = "Patient Access API Workbench",
        version = "v1",
        description = "Manages vendor FHIR environments (UAT, Prod), obtains OAuth/SMART tokens, searches members, "
                + "browses claims, prior authorizations and clinical data, and runs CMS-9115-F / CMS-0057-F "
                + "conformance checks against a payer's Patient Access API."))
@Configuration
public class OpenApiConfig {
}
