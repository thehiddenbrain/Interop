package org.p32h.interop.mmi;

import java.time.LocalDate;

/** The only integration of this service. One implementation talks HTTP; one serves fixtures for local runs and tests. */
public interface MmiClient {

    /**
     * @param memberId      the member id exactly as the EMR typed it, surrounding whitespace removed; sent to MMI unchanged
     * @param dateOfService the date of service (the first day of a period), sent to MMI as dosStartDate
     * @param correlationId the Onyx correlation id, forwarded as a header
     * @throws MmiException when MMI cannot be reached, rejects the call or answers unreadably
     */
    MmiResult search(String memberId, LocalDate dateOfService, String correlationId);

    /** {@code REST} or {@code STUB}, shown in the startup report and /actuator/info. */
    String kind();
}
