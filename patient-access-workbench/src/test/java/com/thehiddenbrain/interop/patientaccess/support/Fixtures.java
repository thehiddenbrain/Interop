package com.thehiddenbrain.interop.patientaccess.support;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Test fixtures: the HL7 example resources shipped under {@code /demo} (loaded fresh on every call so a
 * test can mutate them), Bundle builders and WireMock response helpers with the FHIR content type.
 */
public final class Fixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String FHIR_JSON = "application/fhir+json";

    private Fixtures() {
    }

    /** {@code demo("c4bb-Patient-Patient1")} loads {@code /demo/c4bb-Patient-Patient1.json} from the classpath. */
    public static ObjectNode demo(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/demo/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("no demo resource " + name);
            }
            return (ObjectNode) MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode parse(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String json(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode resource(String type, String id) {
        return MAPPER.createObjectNode().put("resourceType", type).put("id", id);
    }

    public static ObjectNode emptyBundle() {
        return bundle(List.of(), List.of(), 0, null);
    }

    /** A searchset with the given resources as {@code match} entries and {@code total} set to their number. */
    public static ObjectNode bundle(JsonNode... matches) {
        return bundle(List.of(matches), List.of(), matches.length, null);
    }

    public static ObjectNode bundle(List<JsonNode> matches, List<JsonNode> includes, Integer total, String nextUrl) {
        ObjectNode b = MAPPER.createObjectNode().put("resourceType", "Bundle").put("type", "searchset");
        if (total != null) {
            b.put("total", total);
        }
        ArrayNode links = b.putArray("link");
        links.addObject().put("relation", "self").put("url", "http://example.org/fhir/self");
        if (nextUrl != null) {
            links.addObject().put("relation", "next").put("url", nextUrl);
        }
        ArrayNode entries = b.putArray("entry");
        for (JsonNode m : matches) {
            ObjectNode e = entries.addObject();
            e.set("resource", m);
            e.putObject("search").put("mode", "match");
        }
        for (JsonNode i : includes) {
            ObjectNode e = entries.addObject();
            e.set("resource", i);
            e.putObject("search").put("mode", "include");
        }
        return b;
    }

    public static ObjectNode operationOutcome(String severity, String code, String diagnostics) {
        ObjectNode oo = MAPPER.createObjectNode().put("resourceType", "OperationOutcome");
        oo.putArray("issue").addObject().put("severity", severity).put("code", code).put("diagnostics", diagnostics);
        return oo;
    }

    public static ResponseDefinitionBuilder fhir(JsonNode body) {
        return fhir(200, body);
    }

    public static ResponseDefinitionBuilder fhir(int status, JsonNode body) {
        return WireMock.aResponse().withStatus(status).withHeader("Content-Type", FHIR_JSON).withBody(json(body));
    }

    public static ResponseDefinitionBuilder json(int status, String body) {
        return WireMock.aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}
