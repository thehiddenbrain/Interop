package com.thehiddenbrain.interop.patientaccess.common;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper for everything outside Spring MVC (files, history, the demo FHIR server, error bodies).
 * Same settings as the Spring-managed mapper: lenient reading (unknown properties and absent primitives are
 * fine, so files written by another version still load), ISO-8601 dates, and plain {@code /} in output because
 * FHIR JSON is full of URLs and Jackson 3 escapes slashes by default.
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
            .build();

    private Json() {
    }
}
