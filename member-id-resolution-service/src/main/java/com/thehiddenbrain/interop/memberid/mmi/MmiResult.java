package com.thehiddenbrain.interop.memberid.mmi;

/** The MMI answer plus the requestId we sent (MMI echoes it, but ours is the one in our logs). */
public record MmiResult(String requestId, MmiResponse response) {
}
