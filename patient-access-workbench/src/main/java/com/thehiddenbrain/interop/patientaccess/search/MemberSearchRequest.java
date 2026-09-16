package com.thehiddenbrain.interop.patientaccess.search;

import java.util.Map;

/**
 * Member lookup as the UI sends it. Any combination works: a member id (searched across the
 * environment's identifier systems, or the one named), a name (whole name, or family / given),
 * birth date, gender, the FHIR logical id, plus any extra Patient search parameters.
 */
public record MemberSearchRequest(
        String memberId,
        String identifierSystem,
        String name,
        String family,
        String given,
        String birthDate,
        String gender,
        String id,
        Map<String, String> extraParams,
        Boolean coverageFallback,
        Integer count) {

    public boolean hasMemberId() {
        return memberId != null && !memberId.isBlank();
    }

    public boolean hasDemographics() {
        return notBlank(name) || notBlank(family) || notBlank(given) || notBlank(birthDate) || notBlank(gender);
    }

    public boolean hasId() {
        return notBlank(id);
    }

    public boolean hasExtra() {
        return extraParams != null && !extraParams.isEmpty();
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
