package org.point32health.memberprofile.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
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

/**
 * Rejects request bodies larger than {@code member-profile.http.max-body-bytes} with 413 before Jackson
 * reads them. A declared Content-Length above the limit is refused outright; a chunked body is cut off at
 * the limit while it is being read. The real request is a few dozen bytes.
 */
@Configuration
public class RequestSizeLimitFilter {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> bodySizeFilter(MemberProfileProperties properties, ObjectMapper mapper) {
        long limit = properties.http().maxBodyBytes();
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith("/api/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (request.getContentLengthLong() > limit) {
                    reject(response);
                    return;
                }
                try {
                    chain.doFilter(new LimitedRequest(request, limit), response);
                } catch (BodyTooLargeException e) {
                    if (!response.isCommitted()) reject(response);
                }
            }

            private void reject(HttpServletResponse response) throws IOException {
                ErrorCode code = ErrorCode.PAYLOAD_TOO_LARGE;
                response.reset();
                response.setStatus(code.status().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                mapper.writeValue(response.getOutputStream(), ApiError.of(code, code.message()));
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    /** Thrown by the limited stream; unwrapped by the filter into a 413. */
    static final class BodyTooLargeException extends RuntimeException {
        BodyTooLargeException() {
            super("request body exceeds the configured limit", null, false, false);
        }
    }

    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                private int count(int n) {
                    if (n > 0) {
                        read += n;
                        if (read > limit) throw new BodyTooLargeException();
                    }
                    return n;
                }

                @Override public int read() throws IOException { int b = delegate.read(); return b < 0 ? b : count(1) > 0 ? b : b; }
                @Override public int read(byte[] b, int off, int len) throws IOException { return count(delegate.read(b, off, len)); }
                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
                @Override public void close() throws IOException { delegate.close(); }
            };
        }
    }
}
