package com.thehiddenbrain.interop.cms1500.config;

import com.fasterxml.jackson.module.jakarta.xmlbind.JakartaXmlBindAnnotationModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets Jackson read the JAXB annotations on the generated contract classes, so the JSON
 * property and enum names are exactly the element and enumeration names in the XSD.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JakartaXmlBindAnnotationModule jakartaXmlBindAnnotationModule() {
        return new JakartaXmlBindAnnotationModule();
    }
}
