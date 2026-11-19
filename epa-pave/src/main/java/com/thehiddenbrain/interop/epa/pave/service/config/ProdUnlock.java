package com.thehiddenbrain.interop.epa.pave.service.config;

import java.time.Instant;

/**
 * Proof that production writes were explicitly unlocked, for one environment, in one browser session.
 *
 * <p>The constructor is package-private. Only the gateway in this package can mint one, and it does
 * so only after the tester has confirmed by naming the environment and the configuration floor has
 * allowed it. The token lives in the HTTP session in memory. It is never written anywhere, so it
 * cannot survive a restart, and it names the configuration epoch it was granted in, so it stops
 * being valid the moment the active environment changes.
 */
public final class ProdUnlock {

    private final String environmentName;
    private final long epoch;
    private final Instant grantedAt;

    ProdUnlock(String environmentName, long epoch, Instant grantedAt) {
        this.environmentName = environmentName;
        this.epoch = epoch;
        this.grantedAt = grantedAt;
    }

    public String environmentName() {
        return environmentName;
    }

    /** The configuration epoch this unlock belongs to. Any environment switch moves the epoch on. */
    public long epoch() {
        return epoch;
    }

    public Instant grantedAt() {
        return grantedAt;
    }
}
