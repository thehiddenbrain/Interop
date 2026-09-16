package com.thehiddenbrain.interop.patientaccess.demo;

import com.thehiddenbrain.interop.patientaccess.common.Json;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the demo server's CapabilityStatement from the IG catalog: every supported resource type with the
 * profiles, search parameters, includes and reverse includes the C4BB / PDex / US Core / USDF packages declare
 * (plus the extra parameters this server evaluates), the SMART security section with the OAuth URIs
 * extension, and the implementation-guide references the CMS Patient Access API is expected to cite.
 */
@Component
@DemoEnabled
public class DemoCapabilityStatement {

    public static final String SOFTWARE_NAME = "Patient Access Workbench demo server";
    public static final String C4BB_CAPABILITY = "http://hl7.org/fhir/us/carin-bb/CapabilityStatement/c4bb";
    static final String RESTFUL_SECURITY_SERVICE = "http://terminology.hl7.org/CodeSystem/restful-security-service";
    /** The catalog was generated from the US Core master branch; the demo declares the STU the Patient Access API cites. */
    static final Map<String, String> IG_VERSION_FALLBACK = Map.of("uscore", "6.1.0");

    private static final ObjectMapper MAPPER = Json.MAPPER;

    private final IgCatalog catalog;
    private final DemoSearchEngine engine;

    public DemoCapabilityStatement(IgCatalog catalog, DemoSearchEngine engine) {
        this.catalog = catalog;
        this.engine = engine;
    }

    public ObjectNode build(String fhirBase, String authBase, String version) {
        ObjectNode cs = MAPPER.createObjectNode();
        cs.put("resourceType", "CapabilityStatement");
        cs.put("id", "patient-access-workbench-demo");
        cs.put("name", "PatientAccessWorkbenchDemo");
        cs.put("title", "Patient Access Workbench demo Patient Access API");
        cs.put("status", "active");
        cs.put("experimental", true);
        cs.put("date", "2026-09-01");
        cs.put("publisher", "Patient Access Workbench");
        cs.put("description", "In-process demo of a CMS-9115-F / CMS-0057-F Patient Access API (CARIN Blue Button, Da Vinci PDex incl. "
                + "prior authorization, US Core, Da Vinci US Drug Formulary) serving the HL7 example resources plus synthetic sample data.");
        cs.put("kind", "instance");
        cs.putArray("instantiates").add(C4BB_CAPABILITY);
        ArrayNode igs = cs.putArray("implementationGuide");
        for (String key : List.of("c4bb", "pdex", "uscore", "usdf")) {
            IgCatalog.IgInfo ig = catalog.igs().get(key);
            if (ig != null) {
                String v = ig.version() == null || ig.version().isBlank() || "master".equals(ig.version()) ? IG_VERSION_FALLBACK.get(key) : ig.version();
                igs.add(v == null ? ig.canonical() : ig.canonical() + "|" + v);
            }
        }
        ObjectNode software = cs.putObject("software");
        software.put("name", SOFTWARE_NAME);
        software.put("version", version);
        ObjectNode implementation = cs.putObject("implementation");
        implementation.put("description", "Demo Patient Access API of the Patient Access Workbench (sample data only; not a payer system)");
        implementation.put("url", fhirBase);
        cs.put("fhirVersion", "4.0.1");
        cs.putArray("format").add("json").add("application/fhir+json");

        ObjectNode rest = cs.putArray("rest").addObject();
        rest.put("mode", "server");
        rest.put("documentation", "Read, vread and search on every listed type. Searches support _count, page, _include, _revinclude, "
                + "_lastUpdated and _profile; unknown parameters are ignored unless Prefer: handling=strict is sent.");
        ObjectNode security = rest.putObject("security");
        ObjectNode oauth = security.putArray("extension").addObject();
        oauth.put("url", SmartDiscoveryService.OAUTH_URIS_EXTENSION);
        ArrayNode uris = oauth.putArray("extension");
        uris.addObject().put("url", "token").put("valueUri", authBase + "/token");
        uris.addObject().put("url", "authorize").put("valueUri", authBase + "/authorize");
        uris.addObject().put("url", "register").put("valueUri", authBase + "/register");
        security.put("cors", true);
        ObjectNode service = security.putArray("service").addObject();
        ObjectNode coding = service.putArray("coding").addObject();
        coding.put("system", RESTFUL_SECURITY_SERVICE);
        coding.put("code", "SMART-on-FHIR");
        coding.put("display", "SMART-on-FHIR");
        service.put("text", "OAuth2 using SMART-on-FHIR profile (see http://docs.smarthealthit.org)");
        security.put("description", "Bearer tokens from the demo authorization server: client credentials (demo client), "
                + "backend-services JWT assertions, or the SMART standalone launch (authorization code + PKCE) signing in as a demo patient.");

        ArrayNode resources = rest.putArray("resource");
        for (String type : engine.supportedTypes()) {
            resources.add(resource(type));
        }
        return cs;
    }

    private ObjectNode resource(String type) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("type", type);
        Optional<IgCatalog.ResourceSpec> spec = catalog.resource(type);
        spec.ifPresent(s -> {
            if (!s.profiles().isEmpty()) {
                ArrayNode profiles = r.putArray("supportedProfile");
                s.profiles().forEach(p -> profiles.add(p.url()));
            }
        });
        ArrayNode interactions = r.putArray("interaction");
        for (String code : List.of("read", "vread", "search-type")) {
            interactions.addObject().put("code", code);
        }
        r.put("versioning", "versioned");
        r.put("readHistory", false);
        r.put("updateCreate", false);
        ArrayNode includes = r.putArray("searchInclude");
        ArrayNode revIncludes = r.putArray("searchRevInclude");
        spec.ifPresent(s -> {
            s.includes().forEach(i -> includes.add(i.contains(":") ? i : type + ":" + i));
            s.revIncludes().forEach(revIncludes::add);
        });
        if (includes.isEmpty()) {
            r.remove("searchInclude");
        }
        if (revIncludes.isEmpty()) {
            r.remove("searchRevInclude");
        }
        ArrayNode params = r.putArray("searchParam");
        addParam(params, type, "_id", spec);
        addParam(params, type, "_lastUpdated", spec);
        for (String name : engine.supportedParams(type)) {
            addParam(params, type, name, spec);
        }
        return r;
    }

    private static void addParam(ArrayNode params, String type, String name, Optional<IgCatalog.ResourceSpec> spec) {
        DemoParams.Def def = DemoParams.def(name);
        Optional<IgCatalog.SearchParamSpec> declared = spec.flatMap(s -> s.param(name));
        ObjectNode p = params.addObject();
        p.put("name", name);
        p.put("definition", declared.map(IgCatalog.SearchParamSpec::definition).filter(d -> d != null && !d.isBlank())
                .orElse(fallbackDefinition(type, name)));
        p.put("type", declared.map(IgCatalog.SearchParamSpec::type).orElse(def.kind().name().toLowerCase(Locale.ROOT)));
        String doc = declared.map(IgCatalog.SearchParamSpec::description).filter(d -> d != null && !d.isBlank()).orElse(null);
        String expectation = declared.map(IgCatalog.SearchParamSpec::expectation).orElse(null);
        if (doc != null || expectation != null) {
            p.put("documentation", (expectation == null ? "" : expectation + (declared.get().igs().isEmpty() ? "" : " in " + String.join(", ", declared.get().igs())))
                    + (doc == null ? "" : (expectation == null ? "" : ": ") + doc));
        }
    }

    /** Base R4 SearchParameter canonicals for parameters the IGs do not declare themselves. */
    static String fallbackDefinition(String type, String name) {
        String base = "http://hl7.org/fhir/SearchParameter/";
        return switch (name) {
            case "_id" -> base + "Resource-id";
            case "_lastUpdated" -> base + "Resource-lastUpdated";
            case "_profile" -> base + "Resource-profile";
            case "patient", "code", "date", "identifier", "type", "encounter" -> base + "clinical-" + name;
            case "family", "given", "birthdate", "gender", "address", "address-city", "address-state", "address-postalcode" -> base + "individual-" + name;
            default -> base + type + "-" + name;
        };
    }
}
