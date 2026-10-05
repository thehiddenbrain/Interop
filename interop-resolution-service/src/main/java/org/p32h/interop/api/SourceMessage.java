package org.p32h.interop.api;

/**
 * What the member lookup said in its messages when it had no member for the id (type, HTTP-like status, code,
 * text), forwarded as it came. Named without the source system on purpose: nothing a caller receives names MMI.
 */
public record SourceMessage(String type, String status, String code, String text) {
}
