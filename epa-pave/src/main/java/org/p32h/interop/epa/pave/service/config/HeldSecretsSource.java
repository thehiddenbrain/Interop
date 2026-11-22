package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.SentSecrets;

/**
 * Secrets this tool holds outside the configuration store, such as those of the lookups saved on
 * screen (plan 5.2, 9.2). The gateway adds what every bean of this type holds to the secrets each
 * answer is masked with, so a service that repeats one of them is not shown with it, whichever
 * call it answers.
 */
public interface HeldSecretsSource {

    /** The secrets held now, each under the mask it is shown as. Never null. */
    SentSecrets held();
}
