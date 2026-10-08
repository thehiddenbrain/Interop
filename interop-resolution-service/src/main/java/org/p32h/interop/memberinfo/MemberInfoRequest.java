package org.p32h.interop.memberinfo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The member information request body: the member ids to look up and the date of service as MM/dd/yyyy. */
public record MemberInfoRequest(List<String> memberIds, String dos) {

    private static final DateTimeFormatter DOS = DateTimeFormatter.ofPattern("MM/dd/uuuu");

    /** One member id, as the member index stores it, and the date of service. */
    public static MemberInfoRequest of(String memberId, LocalDate dateOfService) {
        return new MemberInfoRequest(List.of(memberId), DOS.format(dateOfService));
    }
}
