package org.point32health.memberprofile.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.point32health.memberprofile.common.ApiError;
import org.point32health.memberprofile.common.ErrorCode;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Service-to-service authentication for {@code /api/**}: the caller (the League portal's backend, the
 * mobile API gateway) presents the shared key in the configured header. The key is compared in constant
 * time. Health probes stay open. This is deliberately simple; if the platform issues JWTs for the portal's
 * service identity, replace this filter with Spring Security's resource server and keep the same paths.
 */
@Configuration
public class ApiKeyAuthFilter {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> apiKeyFilter(MemberProfileProperties properties, ObjectMapper mapper) {
        MemberProfileProperties.Security.ApiKey apiKey = properties.security().apiKey();
        if (apiKey.enabled() && apiKey.value().isBlank()) {
            throw new IllegalStateException("member-profile.security.api-key.enabled is true but no key is configured "
                    + "(set MEMBER_PROFILE_API_KEY)");
        }
        byte[] expected = apiKey.value().getBytes(StandardCharsets.UTF_8);

        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !apiKey.enabled() || !request.getRequestURI().startsWith("/api/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String presented = request.getHeader(apiKey.header());
                if (presented != null && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
                    chain.doFilter(request, response);
                    return;
                }
                ErrorCode code = ErrorCode.UNAUTHORIZED;
                response.setStatus(code.status().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + apiKey.header() + "\"");
                mapper.writeValue(response.getOutputStream(), ApiError.of(code, code.message()));
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
