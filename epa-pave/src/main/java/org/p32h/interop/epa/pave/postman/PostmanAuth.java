package org.p32h.interop.epa.pave.postman;

import java.util.List;

/**
 * The authentication helper Postman would apply to a request, its own or inherited.
 *
 * <p>This tool does not apply it. Authentication is configured once, under {@code onyx.auth}, and
 * a request's own headers are sent as written. The helper is reported so that a tester is told
 * what Postman would have added, because a request that works in Postman and is refused here for
 * its credentials otherwise has no visible cause.
 *
 * @param type      the helper, for example bearer, basic, apikey or oauth2
 * @param from      where it is set, in words: this request, a named folder, or the collection
 * @param variables the placeholder names the helper refers to. Its values are never kept
 */
public record PostmanAuth(String type, String from, List<String> variables) {

    public PostmanAuth {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
