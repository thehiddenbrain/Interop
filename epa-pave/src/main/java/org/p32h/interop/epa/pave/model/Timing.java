package org.p32h.interop.epa.pave.model;

/**
 * How long a call took against the threshold it is judged by.
 *
 * @param slow true when the call completed but took longer than the threshold
 */
public record Timing(long elapsedMillis, long thresholdMillis, boolean slow) {

    public static Timing of(long elapsedMillis, long thresholdMillis) {
        return new Timing(elapsedMillis, thresholdMillis, thresholdMillis > 0 && elapsedMillis > thresholdMillis);
    }
}
