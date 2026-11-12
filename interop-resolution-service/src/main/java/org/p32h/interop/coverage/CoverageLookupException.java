package org.p32h.interop.coverage;

/**
 * The coverage view could not be read: the database cannot be reached, does not answer in time or fails the query.
 * 503 {@code COVERAGE_LOOKUP_UNAVAILABLE}, so Onyx may retry later. Nothing a caller receives names the database: the
 * wording is "the coverage lookup".
 */
public class CoverageLookupException extends RuntimeException {

    public static final String UNAVAILABLE = "COVERAGE_LOOKUP_UNAVAILABLE";

    private final String traceId;

    /** @param traceId the member lookup's request id, returned to Onyx as {@code traceId} */
    public CoverageLookupException(String traceId, Throwable cause) {
        super("the coverage lookup is unavailable", cause);
        this.traceId = traceId;
    }

    public String traceId() {
        return traceId;
    }
}
