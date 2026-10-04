package org.point32health.memberid.mmi;

/**
 * The MMI answer plus the requestId we sent (MMI echoes it, but ours is the one in our logs) and the HTTP status
 * MMI answered with. MMI answers 404 when it has no member for the id; that is a normal answer, not a failure.
 */
public record MmiResult(String requestId, int httpStatus, MmiResponse response) {
}
