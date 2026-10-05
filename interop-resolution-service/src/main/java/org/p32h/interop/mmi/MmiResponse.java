package org.p32h.interop.mmi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MmiResponse(String clientId, String clientType, String requestId, List<MmiMessage> messages,
        List<MmiMember> members) {

    public List<MmiMember> membersOrEmpty() {
        return members == null ? List.of() : members.stream().filter(m -> m != null).toList();
    }

    public List<MmiMessage> messagesOrEmpty() {
        return messages == null ? List.of() : messages.stream().filter(m -> m != null).toList();
    }
}
