package org.p32h.interop.epa.pave.service.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;

/**
 * What "Fill the form from these files" needs to know of the configuration to say, before
 * anything is saved, which address and which value each operation will be given (D-307): the
 * layers a call would resolve through, application.yml's addresses, and a stored collection that
 * would be asked first. It reads and never stores.
 */
public interface DraftSupport {

    /**
     * One operation the Edit form lists under Addresses, in its order and with its words.
     *
     * @param operation the operation a request is mapped to for it
     * @param key       the key an address is typed under: the operation's name, or SUBSCRIPTION
     * @param label     what the Edit form calls it
     * @param shortLabel the operation in a word or two
     */
    record Addressed(EpaOperation operation, String key, String label, String shortLabel) {
    }

    /**
     * One value the Edit form lists under Values.
     *
     * @param name  the variable name a request reads
     * @param label what the Edit form calls it
     * @param why   why a request needs it, in one line
     */
    record Known(String name, String label, String why) {
    }

    /** The operations of Addresses, as the Edit form lists them. */
    static List<Addressed> addressed() {
        List<Addressed> rows = new ArrayList<>();
        for (EpaOperation operation : StoredSettings.ADDRESSED) {
            boolean subscription = operation == EpaOperation.SUBSCRIPTION_CREATE;
            rows.add(new Addressed(operation, StoredSettings.key(operation), subscription ? "Subscription" : operation.label(),
                    operation.shortLabel()));
        }
        return rows;
    }

    /** The values of Values, as the Edit form lists them. */
    static List<Known> known() {
        return KnownValues.ALL.stream().map(each -> new Known(each.name(), each.label(), each.why())).toList();
    }

    /**
     * A template resolved through the layers a call under these files would see: the environment
     * export (else the stored one of the environment being edited), the collection, application.yml. A credential typed into the url is masked, a secret shown masked.
     *
     * @param forId the environment being edited, or null for a new one
     */
    Resolution resolve(String forId, PostmanEnvironment environment, PostmanCollection collection, String template);

    /** application.yml's address for the operation as a call would use it, or null when it gives none. */
    String fallback(EpaOperation operation);

    /** application.yml's callback url, or null. */
    String callbackUrl();

    /**
     * The request of another stored collection that a call would use for the operation before any
     * of the collection of this id, in words, or null when none would.
     */
    String usedBefore(EpaOperation operation, String collectionId);

    /** For a draft read with no configuration behind it: the files alone, no application.yml, no other collection. */
    static DraftSupport filesOnly() {
        VariableResolver resolver = new VariableResolver(name -> null, Clock.systemUTC(), () -> "00000000-0000-4000-8000-000000000000");
        return new DraftSupport() {
            @Override
            public Resolution resolve(String forId, PostmanEnvironment environment, PostmanCollection collection, String template) {
                List<VariableLayer> layers = new ArrayList<>();
                if (environment != null) {
                    layers.add(VariableLayer.environment(environment.name(), environment.inForce()));
                }
                if (collection != null) {
                    layers.add(VariableLayer.collection(collection.name(), collection.inForce()));
                }
                layers.add(VariableLayer.applicationYml(Map.of()));
                LiteralCredentials.Marked marked = LiteralCredentials.inUrl(template);
                return resolver.resolve(marked.template(), marked.before(layers));
            }

            @Override
            public String fallback(EpaOperation operation) {
                return null;
            }

            @Override
            public String callbackUrl() {
                return null;
            }

            @Override
            public String usedBefore(EpaOperation operation, String collectionId) {
                return null;
            }
        };
    }
}
