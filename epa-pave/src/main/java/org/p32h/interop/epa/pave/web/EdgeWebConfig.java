package org.p32h.interop.epa.pave.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/** Puts {@link CrossSiteNavigationInterceptor} in front of every handler the framework chooses. */
@Configuration(proxyBeanMethods = false)
public class EdgeWebConfig implements WebMvcConfigurer {

    private final JsonMapper mapper;

    public EdgeWebConfig(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CrossSiteNavigationInterceptor(mapper));
    }
}
