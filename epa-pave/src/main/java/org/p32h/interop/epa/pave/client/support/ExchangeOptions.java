package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.model.ResolvedHeader;
import java.util.List;

/**
 * How one call is to be sent.
 *
 * @param defaultHeaders headers the operation needs, such as Accept and Content-Type. A header of
 *                       the same name on the collection request replaces a default
 * @param bearerToken    an access token to send as a bearer credential, or null. Ignored when the
 *                       collection request already carries an Authorization header
 * @param bodyRequired   true when a success status with an empty body is a malformed response.
 *                       False for an operation that may legitimately answer with no content
 */
public record ExchangeOptions(List<ResolvedHeader> defaultHeaders, String bearerToken, boolean bodyRequired) {

    public ExchangeOptions {
        defaultHeaders = defaultHeaders == null ? List.of() : List.copyOf(defaultHeaders);
    }

    /** JSON in and JSON out, with a body expected back. */
    public static ExchangeOptions json(String mediaType) {
        return new ExchangeOptions(List.of(
                new ResolvedHeader("Accept", mediaType, mediaType),
                new ResolvedHeader("Content-Type", mediaType, mediaType)), null, true);
    }

    /** A read with no request body, with a body expected back. */
    public static ExchangeOptions read(String mediaType) {
        return new ExchangeOptions(List.of(new ResolvedHeader("Accept", mediaType, mediaType)), null, true);
    }

    public ExchangeOptions withBearerToken(String token) {
        return new ExchangeOptions(defaultHeaders, token, bodyRequired);
    }

    public ExchangeOptions withBodyRequired(boolean required) {
        return new ExchangeOptions(defaultHeaders, bearerToken, required);
    }

    /** Redacted, because it can hold an access token. */
    @Override
    public String toString() {
        return "ExchangeOptions[bodyRequired=" + bodyRequired + "]";
    }
}
