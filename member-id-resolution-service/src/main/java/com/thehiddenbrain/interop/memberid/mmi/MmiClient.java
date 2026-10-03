package com.thehiddenbrain.interop.memberid.mmi;

/** The only integration of this service. One implementation talks HTTP; one serves fixtures for local runs and tests. */
public interface MmiClient {

    /**
     * @param memberId      the normalised id (separators removed, upper-cased)
     * @param correlationId the Onyx correlation id, forwarded as a header
     * @throws MmiException when MMI cannot be reached, rejects the call or answers unreadably
     */
    MmiResult search(String memberId, String correlationId);

    /** {@code REST} or {@code STUB}, shown in the startup report and /actuator/info. */
    String kind();
}
