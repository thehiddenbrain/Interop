package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;

/**
 * One uploaded environment as the store holds it.
 *
 * @param id                  the id this tool gives the environment, derived from its name. Safe
 *                            in a url path and in a file name, which the name itself may not be
 * @param name                the environment name, which is its identity: uploading another file
 *                            with the same name replaces this one
 * @param kind                sandbox, uat or prod, as declared at upload. Never inferred
 * @param content             the parsed export
 * @param file                where the uploaded file sits, relative to the store directory
 * @param originalFileName    the name the file was uploaded under, for display only
 * @param uploadedAt          when it was uploaded, as an ISO instant
 * @param acknowledgedNotProd true when the uploader confirmed that this environment, declared as
 *                            something other than production, is not production. Required when
 *                            the upload reads as production, and kept whenever it is given. A
 *                            write to a url that reads as production is sent under this
 *                            environment only when this is true
 * @param productionSigns     what about the upload looked like production, in words. Empty when
 *                            nothing did
 */
record StoredEnvironment(String id, String name, EnvironmentKind kind, PostmanEnvironment content, String file,
                         String originalFileName, String uploadedAt, boolean acknowledgedNotProd, List<String> productionSigns) {

    StoredEnvironment {
        productionSigns = productionSigns == null ? List.of() : List.copyOf(productionSigns);
    }

    EnvironmentRef ref() {
        return new EnvironmentRef(name, kind);
    }

    /** Prints the name and the kind. The content holds variable values, which can be credentials. */
    @Override
    public String toString() {
        return "StoredEnvironment[" + name + ", " + kind + "]";
    }
}
