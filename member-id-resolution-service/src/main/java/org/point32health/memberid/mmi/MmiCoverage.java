package org.point32health.memberid.mmi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MmiCoverage(String effDate, String endDate, String groupId, String voidFlag) {
}
