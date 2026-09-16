package com.thehiddenbrain.interop.patientaccess.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Optional HTTP basic authentication for the whole workbench ({@code paw.security.basic.*}). Health
 * probes stay open so container orchestration can reach them. The password is compared in constant time.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BasicAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BasicAuthFilter.class);

    private final boolean enabled;
    private final byte[] expected;

    public BasicAuthFilter(WorkbenchProperties properties) {
        WorkbenchProperties.Security.Basic basic = properties.security().basic();
        this.enabled = basic.enabled();
        if (enabled) {
            if (basic.password() == null || basic.password().isBlank()) {
                throw new IllegalStateException("paw.security.basic.enabled is true but paw.security.basic.password (PAW_BASIC_AUTH_PASSWORD) is empty");
            }
            this.expected = (basic.username() + ":" + basic.password()).getBytes(StandardCharsets.UTF_8);
            log.info("basic authentication enabled for user '{}'", basic.username());
        } else {
            this.expected = new byte[0];
        }
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled || path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            byte[] presented;
            try {
                presented = Base64.getDecoder().decode(header.substring(6).trim());
            } catch (IllegalArgumentException e) {
                presented = new byte[0];
            }
            if (MessageDigest.isEqual(expected, presented)) {
                chain.doFilter(request, response);
                return;
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Basic realm=\"patient-access-workbench\", charset=\"UTF-8\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":\"ERROR\",\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}");
    }
}
