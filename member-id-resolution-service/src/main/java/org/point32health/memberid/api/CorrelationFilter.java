package org.point32health.memberid.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads Onyx's {@code X-Correlation-Id} (or generates one), puts it in the log context, the response
 * header and {@link #current()} so the body can echo it. The chain Onyx id -> this log -> MMI requestId
 * is how support follows one transaction without any PHI.
 */
@Component
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String id;
        if (supplied != null && VALID.matcher(supplied).matches()) {
            id = supplied;
        } else {
            id = UUID.randomUUID().toString();
            if (supplied != null) {
                log.warn("marker=CORRELATION_ID_REPLACED header rejected (length {}); generated {}", supplied.length(), id);
            }
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        response.setHeader("Cache-Control", "no-store");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
