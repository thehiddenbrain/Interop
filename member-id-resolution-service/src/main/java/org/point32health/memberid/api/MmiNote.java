package org.point32health.memberid.api;

/** What MMI said in its {@code messages[]} when it had no member for the id (type, HTTP-like status, code, text). */
public record MmiNote(String type, String status, String code, String text) {
}
