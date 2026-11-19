package org.p32h.interop.epa.pave.postman;

import java.util.List;
import java.util.Map;

/**
 * A Postman collection export, schema v2.1, flattened: every request found anywhere in the folder
 * tree, in document order.
 *
 * @param postmanId the id Postman gave the collection, or null
 * @param name      the collection name. Never blank: an export without one is refused
 * @param schema    the schema url the export states
 * @param variables the variables defined on the collection, disabled ones included
 * @param requests  every request, in the order the file lists them
 * @param notes     what the parser set aside or had to decide, in words, for the upload result
 */
public record PostmanCollection(String postmanId, String name, String schema, List<PostmanVariable> variables,
                                List<PostmanRequest> requests, List<String> notes) {

    public PostmanCollection {
        variables = variables == null ? List.of() : List.copyOf(variables);
        requests = requests == null ? List.of() : List.copyOf(requests);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The collection variables resolution uses: enabled ones only, the last entry of a repeated name. */
    public Map<String, PostmanVariable> inForce() {
        return PostmanEnvironment.inForce(variables);
    }

    /** The request with the given id, or null. */
    public PostmanRequest request(String requestId) {
        if (requestId == null) {
            return null;
        }
        for (PostmanRequest request : requests) {
            if (requestId.equals(request.id())) {
                return request;
            }
        }
        return null;
    }

    /** Prints the name and a count. */
    @Override
    public String toString() {
        return "PostmanCollection[" + name + ", " + requests.size() + " requests]";
    }
}
