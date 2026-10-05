package org.p32h.interop.mmi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MmiCoverage(String effDate, String endDate, String groupId, String voidFlag) {
}
