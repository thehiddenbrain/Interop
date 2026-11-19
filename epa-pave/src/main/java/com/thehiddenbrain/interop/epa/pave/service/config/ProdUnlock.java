package com.thehiddenbrain.interop.epa.pave.service.config;

import java.time.Instant;

/**
 * Proof that production writes were explicitly unlocked, for one environment, in one browser session.
 *
 * <p>This is a sealed interface with a single implementation that is not visible outside this
 * package. Nothing else can implement it or construct one: not other code, and not a framework
 * that builds handler arguments from request parameters, which would happily call a non-public
 * constructor on a class but cannot instantiate an interface.
 *
 * <p>The gateway honours an unlock only when it is the very instance the gateway minted and still
 * holds. A matching name and epoch are not enough. The unlock lives in the HTTP session in memory,
 * is never written anywhere, so it cannot survive a restart, and the gateway forgets every unlock
 * it has issued the moment the configuration changes.
 */
public sealed interface ProdUnlock permits GrantedUnlock {

    String environmentName();

    /** The configuration epoch this unlock belongs to. Any change to the configuration moves the epoch on. */
    long epoch();

    Instant grantedAt();
}
