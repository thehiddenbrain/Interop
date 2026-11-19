package com.thehiddenbrain.interop.epa.pave.service.config;

/**
 * Whether a write would be allowed right now, and why, for the banner and the write buttons.
 *
 * @param production    true when the active environment is a production environment
 * @param writesAllowed true when a write operation would be sent
 * @param floorAllows   the value of the configuration floor. While false no unlock is possible
 * @param reason        one plain sentence saying why writes are or are not allowed
 */
public record WriteAccess(boolean production, boolean writesAllowed, boolean floorAllows, String reason) {
}
