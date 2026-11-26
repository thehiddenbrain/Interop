package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanRequest;

/**
 * One uploaded collection as the store holds it, with what each of its requests is mapped to.
 *
 * @param id               the id this tool gives the collection, derived from its name. Uploading
 *                         another file with the same name replaces this one and keeps the id, so
 *                         mapping corrections survive a re-upload
 * @param content          the parsed export
 * @param file             where the uploaded file sits, relative to the store directory
 * @param originalFileName the name the file was uploaded under, for display only
 * @param uploadedAt       when it was uploaded, as an ISO instant
 * @param requests         every request of the collection, in document order, with its mapping
 */
record StoredCollection(String id, PostmanCollection content, String file, String originalFileName, String uploadedAt,
                        List<Mapped> requests) {

    /**
     * One request and the operation it stands for.
     *
     * @param request    the request as parsed
     * @param detection  what detection made of its name and url, worked out again at every start
     * @param correction the operation an operator chose in its place, or null. Persisted
     */
    record Mapped(PostmanRequest request, OperationDetector.Detection detection, EpaOperation correction) {

        /** The operation in force: the operator's correction when there is one, otherwise the detection. */
        EpaOperation effective() {
            return correction != null ? correction : detection.operation();
        }
    }

    StoredCollection {
        requests = requests == null ? List.of() : List.copyOf(requests);
    }

    String name() {
        return content.name();
    }

    /** The request with the given id, or null. */
    Mapped request(String requestId) {
        if (requestId == null) {
            return null;
        }
        for (Mapped mapped : requests) {
            if (requestId.equals(mapped.request().id())) {
                return mapped;
            }
        }
        return null;
    }

    /** Prints the name and a count. The content holds bodies and header values. */
    @Override
    public String toString() {
        return "StoredCollection[" + content.name() + ", " + requests.size() + " requests]";
    }
}
