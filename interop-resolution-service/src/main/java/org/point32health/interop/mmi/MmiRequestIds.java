package org.point32health.interop.mmi;

import java.security.SecureRandom;
import java.util.Locale;

/** MMI's convention: {@code <AppName>-<epoch millis>-<5-digit random>}. */
public final class MmiRequestIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private MmiRequestIds() {
    }

    public static String next(String clientId) {
        return String.format(Locale.ROOT, "%s-%d-%05d", clientId, System.currentTimeMillis(), RANDOM.nextInt(100_000));
    }
}
