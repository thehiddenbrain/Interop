package org.point32health.memberid.api;

/** Why the records MMI returned could not be reduced to one person, and what Onyx can do about it. */
public record Ambiguity(String reason, String hint) {
}
