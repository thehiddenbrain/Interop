package com.thehiddenbrain.interop.patientaccess.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the browser UI at /ui/ when {@code paw.ui.enabled} is true. */
@Configuration
@ConditionalOnProperty(prefix = "paw.ui", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UiConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
                .addResourceLocations("classpath:/ui/")
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/ui/");
        registry.addRedirectViewController("/ui", "/ui/");
        registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
    }
}
