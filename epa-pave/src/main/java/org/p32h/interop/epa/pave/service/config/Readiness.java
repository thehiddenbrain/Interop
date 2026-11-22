package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.Names;

/**
 * Why an operation cannot be sent as things stand, in the words the Workbench shows a tester in
 * the middle of a case (I-1, I-15): no property name, no braces, and the place in Configuration
 * where the missing thing is typed. The property names stay in Configuration > Advanced > In force
 * now. With no environment active nothing else is said: a url or a value cannot matter before one is.
 */
final class Readiness {

    /** The one sentence while no environment is active. */
    static final String NO_ENVIRONMENT = "No environment is active. Open Configuration and make one active.";

    private Readiness() {
    }

    /** An operation with no address: no collection request, no typed address, no url as PAVE was started. */
    static String noAddress(EpaOperation operation) {
        return "No address for " + operation.shortLabel() + ". Type one under Addresses of the environment in Configuration.";
    }

    /**
     * Values the call needs and nothing gives, named as the tester knows them from a Postman
     * environment.
     *
     * @param inAddress true when the address itself needs them, false when only a header does
     */
    static String valuesMissing(EpaOperation operation, List<String> names, boolean inAddress) {
        return "The " + operation.shortLabel() + (inAddress ? " address" : " request") + " needs a value for "
                + String.join(", ", names.stream().map(name -> ConfigStore.oneLine(Names.shown(name))).toList())
                + ". Add it under Values of the environment in Configuration.";
    }
}
