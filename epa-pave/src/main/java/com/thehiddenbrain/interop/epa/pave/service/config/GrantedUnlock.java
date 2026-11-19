package com.thehiddenbrain.interop.epa.pave.service.config;

import java.time.Instant;

/** The only implementation of {@link ProdUnlock}. Package-private, so only the gateway in this package mints one. */
final class GrantedUnlock implements ProdUnlock {

    private final String environmentName;
    private final long epoch;
    private final Instant grantedAt;

    GrantedUnlock(String environmentName, long epoch, Instant grantedAt) {
        this.environmentName = environmentName;
        this.epoch = epoch;
        this.grantedAt = grantedAt;
    }

    @Override
    public String environmentName() {
        return environmentName;
    }

    @Override
    public long epoch() {
        return epoch;
    }

    @Override
    public Instant grantedAt() {
        return grantedAt;
    }
}
