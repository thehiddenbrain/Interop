package org.p32h.interop.epa.pave.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.error.FaultAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a badly formed request before anything handles it, each with a 4xx in the shared shape
 * that repeats nothing of the request.
 *
 * <ul>
 *   <li><b>An address that holds a semicolon</b> that is not percent-encoded, with 400. The server
 *       and the framework read what follows a semicolon as a parameter of the path and cut it off
 *       before a handler sees the address, and the framework decodes it on its own, outside the
 *       exception handler: {@code /api/mode;x=%zz} was a 500 with the request written to the log
 *       by loggers this tool does not own. This tool uses no path parameters, so nothing is lost.
 *       A lookup of an identifier that holds a semicolon still works: the identifier arrives with
 *       it written {@code %3B}. A session id written into the address goes with it, so the session
 *       is carried by the cookie alone.</li>
 *   <li><b>An address with a percent sign that does not start an escape</b>, with 400. The server
 *       refuses most of these itself; this is for any that reach the application.</li>
 *   <li><b>A content type that is a wildcard</b>, such as {@code *}{@code /*} or {@code text/*}, with
 *       415. A request states what it sends; a wildcard says nothing, and the framework throws on
 *       one outside the exception handler's reach.</li>
 *   <li><b>A body larger than {@code spring.servlet.multipart.max-request-size}</b>, with 413: the
 *       one limit on what a request may carry, for an upload and for a JSON body alike. The
 *       largest body a tester sends is their own PAS bundle with a document inline, which an
 *       upload's limit also fits. A body that states its length is refused before it is read; a
 *       body sent in chunks is counted as it is read and refused when it passes the limit. A
 *       negative value means no limit, as it does to the framework, which documents -1 for that.
 *       Both size settings are named together in the answer: raising the one for the request alone
 *       let a large upload through to a second refusal, by the limit for one file.</li>
 *   <li><b>A body too large for the memory PAVE runs with</b>, with 413, when its stated length
 *       times {@link #HEAP_FACTOR} passes the most the heap may grow to. Reading a Postman export
 *       into a tree takes about that much, and running out part way gave an internal error with no
 *       way out; refused before it is read, the tester is told the setting that helps.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RequestShapeFilter extends OncePerRequestFilter {

    static final String MAX_REQUEST_SIZE = "spring.servlet.multipart.max-request-size";
    static final String MAX_FILE_SIZE = "spring.servlet.multipart.max-file-size";
    static final String FILE_SIZE_THRESHOLD = "spring.servlet.multipart.file-size-threshold";

    /**
     * A Postman export read into a tree takes many times its own size in memory. Twelve times is
     * the bound a body is held to here, so that the refusal comes before the heap runs out part way.
     */
    static final long HEAP_FACTOR = 12;

    private static final long MB = 1024L * 1024;

    private static final Logger log = LoggerFactory.getLogger(RequestShapeFilter.class);

    /** The most a body may carry, or a negative number when there is no limit. */
    private final long maxBody;
    private final String maxBodyText;
    /** The limit for one uploaded file as the operator wrote it, or null when it is no limit. */
    private final String maxFileText;
    private final long maxFile;
    /** The most the heap may grow to, read on each request so that a test can fix it. */
    private final LongSupplier maxHeap;

    @Autowired
    public RequestShapeFilter(Environment environment) {
        this(environment, () -> Runtime.getRuntime().maxMemory());
    }

    RequestShapeFilter(Environment environment, LongSupplier maxHeap) {
        String limit = Texts.orElse(Texts.trimToNull(environment.getProperty(MAX_REQUEST_SIZE)), "30MB");
        this.maxBody = DataSize.parse(limit).toBytes();
        this.maxBodyText = limit;
        // The framework's own default, which applies when the setting is absent.
        String fileLimit = Texts.orElse(Texts.trimToNull(environment.getProperty(MAX_FILE_SIZE)), "1MB");
        this.maxFile = DataSize.parse(fileLimit).toBytes();
        this.maxFileText = fileLimit;
        this.maxHeap = maxHeap;
        warnWhenUploadsCanReachTheDisk(environment);
    }

    /**
     * An upload is held in memory up to {@code file-size-threshold} and written to the temporary
     * folder beyond it, where a hard kill in the middle of one leaves it. The server holds every
     * uploaded file to {@code max-file-size}, so nothing reaches the disk while that is at or below the threshold.
     * When the operator lifts it past, the operator is told once at start-up.
     */
    private static void warnWhenUploadsCanReachTheDisk(Environment environment) {
        // The framework's own defaults, which apply when the setting is absent.
        String fileLimit = Texts.orElse(Texts.trimToNull(environment.getProperty(MAX_FILE_SIZE)), "1MB");
        String threshold = Texts.orElse(Texts.trimToNull(environment.getProperty(FILE_SIZE_THRESHOLD)), "0B");
        long fileBytes = DataSize.parse(fileLimit).toBytes();
        if (fileBytes < 0 || fileBytes > DataSize.parse(threshold).toBytes()) {
            // Both values are the operator's own settings, not member data.
            log.warn("{} is {} and {} is {}, so an uploaded file larger than {} is written to the temporary folder while it is read, "
                    + "and a hard kill in the middle of that upload leaves it there. To keep every upload in memory, set {} no higher "
                    + "than {}.", MAX_FILE_SIZE, fileLimit, FILE_SIZE_THRESHOLD, threshold, threshold, MAX_FILE_SIZE, FILE_SIZE_THRESHOLD);
        }
    }

    private boolean limited() {
        return maxBody >= 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String address = request.getRequestURI();
        if (address != null && address.indexOf(';') >= 0) {
            EdgeAnswers.write(response, HttpServletResponse.SC_BAD_REQUEST, ErrorKind.VALIDATION,
                    "The address holds a semicolon that is not percent-encoded. This tool takes none, so nothing was done.",
                    "Percent-encode a semicolon that is part of a value, as %3B, and send the request again.", null);
            return;
        }
        if (address != null && !wellEscaped(address)) {
            EdgeAnswers.write(response, HttpServletResponse.SC_BAD_REQUEST, ErrorKind.VALIDATION,
                    "The address holds a percent sign that does not start a valid escape, so nothing was done.",
                    "Percent-encode a percent sign that is part of a value, as %25, and send the request again.", null);
            return;
        }
        if (wildcard(request.getContentType())) {
            EdgeAnswers.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, ErrorKind.VALIDATION,
                    "The request states a content type that is a wildcard, so this tool cannot tell what it sends.",
                    "Send application/json, or multipart form data for an upload.", null);
            return;
        }
        long stated = request.getContentLengthLong();
        if (limited() && stated > maxBody) {
            tooLarge(response, multipart(request.getContentType()));
            return;
        }
        // After the size limit, so that a body over it is told about that limit first: raising the
        // heap would not let it through.
        long heap = maxHeap.getAsLong();
        if (stated > 0 && heap > 0 && heap != Long.MAX_VALUE && stated > heap / HEAP_FACTOR) {
            EdgeAnswers.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorKind.VALIDATION, tooLittleMemory(stated, heap),
                    FaultAdvice.MORE_MEMORY, null);
            return;
        }
        if (!limited()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(new Limited(request, maxBody), response);
        } catch (BodyTooLargeException e) {
            // Thrown where nothing caught it, such as a body read by a filter of the framework.
            tooLarge(response, multipart(request.getContentType()));
        }
    }

    private void tooLarge(HttpServletResponse response, boolean upload) throws IOException {
        // For a form upload the limit for one file is the one that matters when it is the lower:
        // a file cannot pass it however large the request may be.
        boolean fileLimitMatters = upload && maxFile >= 0 && (maxBody < 0 || maxFile < maxBody);
        String title = fileLimitMatters ? tooLargeTitle(maxBodyText, maxFileText) : tooLargeTitle(maxBodyText);
        EdgeAnswers.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorKind.VALIDATION, title, tooLargeAction(), null);
    }

    private static boolean multipart(String contentType) {
        return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    static String tooLargeTitle(String limit) {
        return "The request is larger than this tool accepts, which is " + limit + " in all. Nothing was done.";
    }

    /** The title for a form upload, naming the limit for one file beside the one for the request. */
    static String tooLargeTitle(String requestLimit, String fileLimit) {
        return "The upload is larger than this tool accepts, which is " + fileLimit + " for one file and " + requestLimit
                + " in all. Nothing was done.";
    }

    /** Names both size settings together: raising one alone let a large upload meet the other. */
    static String tooLargeAction() {
        return "Send a smaller body. To accept larger ones, raise " + MAX_REQUEST_SIZE + " and " + MAX_FILE_SIZE
                + " together in application.yml.";
    }

    /** R-36's sentence: the size in whole megabytes rounded up, the heap rounded down, so neither flatters the fit. */
    static String tooLittleMemory(long stated, long heap) {
        long size = (stated + MB - 1) / MB;
        return "This request is " + size + " MB and PAVE runs with " + heap / MB + " MB of memory, which is too little to read it.";
    }

    /** True when every percent sign starts an escape of two hexadecimal digits. */
    static boolean wellEscaped(String address) {
        for (int i = address.indexOf('%'); i >= 0; i = address.indexOf('%', i + 1)) {
            if (i + 2 >= address.length() || Character.digit(address.charAt(i + 1), 16) < 0 || Character.digit(address.charAt(i + 2), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean wildcard(String contentType) {
        if (Texts.blank(contentType)) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return type.isWildcardType() || type.isWildcardSubtype();
        } catch (InvalidMediaTypeException e) {
            // Not a content type at all. The framework answers that with 415 itself.
            return false;
        }
    }

    /** Thrown when a body read through this filter passes the limit. */
    static final class BodyTooLargeException extends IOException {

        BodyTooLargeException() {
            super("The request body passed the limit");
        }
    }

    /** The request with its body counted as it is read. */
    private static final class Limited extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        Limited(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream in;
        private final long limit;
        private long count;

        CountingStream(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                counted(read);
            }
            return read;
        }

        private void counted(int read) throws BodyTooLargeException {
            count += read;
            if (count > limit) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
