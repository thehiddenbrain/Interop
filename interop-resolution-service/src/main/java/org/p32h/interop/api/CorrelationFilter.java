package org.p32h.interop.api;

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
 * header and {@link #current()} so the body can echo it. The caller's {@code requestId}, once the body has been
 * read and the id found usable, joins it in the log context ({@link #rememberRequestId(String)}). The chain
 * caller id -> this log -> MMI requestId is how support follows one transaction.
 */
@Component
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    /** What an id supplied by a caller may look like: the correlation header and the body's requestId alike. */
    static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
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
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Keeps the caller's requestId for the log and for an error response. */
    static void rememberRequestId(String requestId) {
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
    }

    /** The caller's requestId when the request carried a usable one, else null. */
    public static String requestId() {
        return MDC.get(REQUEST_ID_MDC_KEY);
    }
}
