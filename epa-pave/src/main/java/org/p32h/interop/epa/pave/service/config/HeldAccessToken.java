package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.SentSecrets;

/**
 * Whatever holds the access token for the services, asked to add the token it holds now to a set
 * of values to mask. The token never leaves as a value: it goes straight into the masking type.
 */
public interface HeldAccessToken {

    /** Adds the token held now, if there is one, as the access token. Adds nothing when none is held. */
    void addTo(SentSecrets.Builder secrets);
}
