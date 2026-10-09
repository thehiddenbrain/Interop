package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the member lookup said in its messages when it had no member for the id (type, HTTP-like status, code,
 * text), forwarded as it came. Named without the source system on purpose: nothing a caller receives names MMI.
 */
@Schema(description = "What the member lookup said when it had no member for the id, forwarded as it came")
public record SourceMessage(
        @Schema(description = "The message type as the lookup sent it", example = "ERROR") String type,
        @Schema(description = "The status as the lookup sent it", example = "404") String status,
        @Schema(description = "The message code as the lookup sent it", example = "MEMBER_NOT_FOUND") String code,
        @Schema(description = "The message text as the lookup sent it") String text) {
}
