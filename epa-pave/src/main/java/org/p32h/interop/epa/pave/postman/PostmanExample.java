package org.p32h.interop.epa.pave.postman;

import java.util.List;

/**
 * A response saved beside a collection request. The vendor ships these as examples of what the
 * service answers, and a tester compares one against what actually came back.
 *
 * @param name    the name the example was saved under
 * @param status  the status text, for example OK, or null when the export gives none
 * @param code    the HTTP status code, or null when the export gives none
 * @param headers the response headers as saved
 * @param body    the response body exactly as saved, or null when the example has none
 */
public record PostmanExample(String name, String status, Integer code, List<PostmanHeader> headers, String body) {

    public PostmanExample {
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /** Prints no body. A saved response is vendor sample data and can read like member data. */
    @Override
    public String toString() {
        return "PostmanExample[" + name + ", " + code + "]";
    }
}
