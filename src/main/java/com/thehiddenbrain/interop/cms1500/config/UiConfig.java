package com.thehiddenbrain.interop.cms1500.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the static test UI at /ui/ when {@code cms1500.ui.enabled} is true (the prod profile turns it off). */
@Configuration
@ConditionalOnProperty(prefix = "cms1500.ui", name = "enabled", havingValue = "true", matchIfMissing = true)
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
