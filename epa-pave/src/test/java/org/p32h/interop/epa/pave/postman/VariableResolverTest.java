package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;

class VariableResolverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-05T15:04:05.070Z"), ZoneOffset.UTC);

    /** Stands in for the operating system, so no test depends on the environment of whoever runs the build. */
    private final Map<String, String> operatingSystem = new HashMap<>();
    private final AtomicInteger guids = new AtomicInteger();
    private final VariableResolver resolver = new VariableResolver(operatingSystem::get, CLOCK,
            () -> "00000000-0000-4000-8000-" + String.format("%012d", guids.incrementAndGet()));

    private static Map<String, PostmanVariable> variables(String... keysAndValues) {
        Map<String, PostmanVariable> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put(keysAndValues[i], new PostmanVariable(keysAndValues[i], keysAndValues[i + 1], true, "default"));
        }
        return out;
    }

    private static Map<String, String> values(String... keysAndValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }

    private static List<VariableLayer> layers(Map<String, String> run, Map<String, PostmanVariable> environment,
                                              Map<String, PostmanVariable> collection, Map<String, String> yml) {
        return List.of(VariableLayer.runValues(run), VariableLayer.environment("Onyx Sandbox", environment),
                VariableLayer.collection("Onyx Sandbox ePA", collection), VariableLayer.applicationYml(yml));
    }

    private Resolution resolveInEnvironment(String template, String... keysAndValues) {
        return resolver.resolve(template, List.of(VariableLayer.environment("Onyx Sandbox", variables(keysAndValues))));
    }

    private static VariableUse use(Resolution resolution, String name) {
        return resolution.variables().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // The order of the layers
    // ------------------------------------------------------------------------------------------

    @Test
    void theFirstLayerThatHoldsAValueWinsInTheOrderRunEnvironmentCollectionYml() {
        Map<String, String> run = values("x", "from-run");
        Map<String, PostmanVariable> environment = variables("x", "from-environment");
        Map<String, PostmanVariable> collection = variables("x", "from-collection");
        Map<String, String> yml = values("x", "from-yml");

        Resolution all = resolver.resolve("{{x}}", layers(run, environment, collection, yml));
        assertThat(all.send()).isEqualTo("from-run");
        assertThat(use(all, "x").source()).isEqualTo(VariableSource.RUN_VALUE);

        Resolution withoutRun = resolver.resolve("{{x}}", layers(Map.of(), environment, collection, yml));
        assertThat(withoutRun.send()).isEqualTo("from-environment");
        assertThat(use(withoutRun, "x").source()).isEqualTo(VariableSource.ENVIRONMENT);

        Resolution withoutEnvironment = resolver.resolve("{{x}}", layers(Map.of(), Map.of(), collection, yml));
        assertThat(withoutEnvironment.send()).isEqualTo("from-collection");
        assertThat(use(withoutEnvironment, "x").source()).isEqualTo(VariableSource.COLLECTION);

        Resolution ymlOnly = resolver.resolve("{{x}}", layers(Map.of(), Map.of(), Map.of(), yml));
        assertThat(ymlOnly.send()).isEqualTo("from-yml");
        assertThat(use(ymlOnly, "x").source()).isEqualTo(VariableSource.APPLICATION_YML);

        Resolution nowhere = resolver.resolve("{{x}}", layers(Map.of(), Map.of(), Map.of(), Map.of()));
        assertThat(nowhere.complete()).isFalse();
        assertThat(nowhere.unresolved()).containsExactly("x");
        assertThat(use(nowhere, "x").source()).isEqualTo(VariableSource.UNRESOLVED);
    }

    @Test
    void eachPlaceholderIsResolvedFromItsOwnLayerAndTheTextAroundItIsKept() {
        Resolution resolution = resolver.resolve("{{scheme}}://{{host}}/fhir/{{ version }}?a=1", layers(values("version", "R4"),
                variables("host", "sandbox.example.invalid"), variables("scheme", "https"), Map.of()));

        assertThat(resolution.complete()).isTrue();
        assertThat(resolution.send()).isEqualTo("https://sandbox.example.invalid/fhir/R4?a=1");
        assertThat(resolution.display()).isEqualTo(resolution.send());
        assertThat(resolution.variables()).extracting(VariableUse::name).containsExactly("scheme", "host", "version");
        assertThat(resolution.variables()).extracting(VariableUse::source)
                .containsExactly(VariableSource.COLLECTION, VariableSource.ENVIRONMENT, VariableSource.RUN_VALUE);
        ResolvedText text = resolution.text();
        assertThat(text.template()).isEqualTo("{{scheme}}://{{host}}/fhir/{{ version }}?a=1");
        assertThat(text.value()).isEqualTo("https://sandbox.example.invalid/fhir/R4?a=1");
        assertThat(text.resolved()).isTrue();
    }

    @Test
    void textWithNoPlaceholderIsReturnedAsItIs() {
        Resolution resolution = resolver.resolve("{\"single\": {\"brace\": \"{id}\"}, \"empty\": \"{{}}\"}", List.of());

        assertThat(resolution.complete()).isTrue();
        assertThat(resolution.send()).isEqualTo("{\"single\": {\"brace\": \"{id}\"}, \"empty\": \"{{}}\"}");
        assertThat(resolution.variables()).isEmpty();
        assertThat(resolver.resolve(null, null).send()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Blank is not a value
    // ------------------------------------------------------------------------------------------

    @Test
    void aVariableThatIsDefinedButBlankFallsThroughToTheNextLayer() {
        Resolution resolution = resolver.resolve("{{baseUrl}}/x", layers(values("baseUrl", "  "),
                variables("baseUrl", ""), variables("baseUrl", "https://collection.example.invalid"), Map.of()));

        assertThat(resolution.send()).isEqualTo("https://collection.example.invalid/x");
        assertThat(use(resolution, "baseUrl").source()).isEqualTo(VariableSource.COLLECTION);
    }

    @Test
    void aVariableThatIsBlankEverywhereIsUnresolvedAndTheReasonSaysWhereItIsBlank() {
        Resolution resolution = resolver.resolve("{{baseUrl}}/cds-services/{{neverDefined}}", layers(Map.of(),
                variables("baseUrl", ""), variables("baseUrl", null), Map.of()));

        // Postman would send this to "/cds-services/". Here it is not sent at all.
        assertThat(resolution.complete()).isFalse();
        assertThat(resolution.send()).isNull();
        assertThat(resolution.display()).isNull();
        assertThat(resolution.unresolved()).containsExactly("baseUrl", "neverDefined");
        assertThat(resolution.reasons()).containsOnlyKeys("baseUrl");
        assertThat(resolution.reasons().get("baseUrl")).isEqualTo(
                "It is defined in the environment \"Onyx Sandbox\" and in the collection \"Onyx Sandbox ePA\", but its value is blank.");
        // A name with no reason is simply not defined anywhere.
        assertThat(resolution.explanations().get("neverDefined")).isEqualTo("No layer defines it.");
        assertThat(resolution.shown()).isEqualTo("{{baseUrl}}/cds-services/{{neverDefined}}");
        assertThat(resolution.text().resolved()).isFalse();
        assertThat(resolution.text().unresolved()).containsExactly("baseUrl", "neverDefined");
    }

    // ------------------------------------------------------------------------------------------
    // Values that refer to other variables
    // ------------------------------------------------------------------------------------------

    @Test
    void aValueThatHoldsPlaceholdersIsResolvedThroughEveryLayer() {
        Resolution resolution = resolver.resolve("{{tokenUrl}}", layers(Map.of(), variables("tokenUrl", "{{authHost}}/oauth2/{{realm}}/token"),
                variables("authHost", "https://auth.example.invalid"), values("realm", "p32h")));

        assertThat(resolution.send()).isEqualTo("https://auth.example.invalid/oauth2/p32h/token");
        assertThat(resolution.variables()).extracting(VariableUse::name).containsExactly("authHost", "realm", "tokenUrl");
        assertThat(use(resolution, "tokenUrl").source()).isEqualTo(VariableSource.ENVIRONMENT);
        assertThat(use(resolution, "tokenUrl").display()).isEqualTo("https://auth.example.invalid/oauth2/p32h/token");
        assertThat(use(resolution, "authHost").source()).isEqualTo(VariableSource.COLLECTION);
        assertThat(use(resolution, "realm").source()).isEqualTo(VariableSource.APPLICATION_YML);
    }

    @Test
    void whenAValueNeedsAVariableThatIsMissingTheMissingOneIsNamed() {
        Resolution resolution = resolveInEnvironment("{{baseUrl}}/Claim/$submit", "baseUrl", "https://{{host}}:{{port}}/fhir", "host", "h.example.invalid");

        assertThat(resolution.complete()).isFalse();
        // The tester can type or define {{port}}. Being told that {{baseUrl}} has no value would send them to the wrong place.
        assertThat(resolution.unresolved()).containsExactly("port");
        assertThat(use(resolution, "baseUrl").source()).isEqualTo(VariableSource.UNRESOLVED);
        assertThat(use(resolution, "baseUrl").display()).isNull();
        assertThat(use(resolution, "host").source()).isEqualTo(VariableSource.ENVIRONMENT);
        assertThat(resolution.explanations().get("baseUrl")).isEqualTo(
                "It is defined in the environment \"Onyx Sandbox\", but its value needs {{port}}, which has no value.");
        assertThat(resolution.shown()).isEqualTo("https://h.example.invalid:{{port}}/fhir/Claim/$submit");
    }

    @Test
    void aVariableThatRefersToItselfIsUnresolvedAndDoesNotOverflowTheStack() {
        Resolution direct = resolveInEnvironment("{{a}}", "a", "x{{a}}");
        assertThat(direct.complete()).isFalse();
        assertThat(direct.unresolved()).containsExactly("a");
        assertThat(direct.reasons().get("a")).isEqualTo("Its value refers back to itself (a -> a).");

        Resolution throughOthers = resolveInEnvironment("{{a}}/{{b}}", "a", "{{b}}", "b", "{{c}}", "c", "{{a}}");
        assertThat(throughOthers.complete()).isFalse();
        assertThat(throughOthers.reasons().get("a")).isEqualTo("Its value refers back to itself (a -> b -> c -> a).");
        assertThat(throughOthers.send()).isNull();
        assertThat(throughOthers.variables()).allMatch(v -> v.source() == VariableSource.UNRESOLVED);
    }

    @Test
    void nestingStopsAtAFixedDepth() {
        List<String> chain = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            chain.add("v" + i);
            chain.add(i == 11 ? "end" : "{{v" + (i + 1) + "}}");
        }
        Resolution tooDeep = resolveInEnvironment("{{v0}}", chain.toArray(String[]::new));
        assertThat(tooDeep.complete()).isFalse();
        assertThat(tooDeep.unresolved()).containsExactly("v" + VariableResolver.MAX_DEPTH);
        assertThat(tooDeep.reasons().get("v" + VariableResolver.MAX_DEPTH)).isEqualTo("It is nested more than 10 variables deep.");

        // Ten deep still resolves.
        Resolution deepEnough = resolveInEnvironment("{{v2}}", chain.toArray(String[]::new));
        assertThat(deepEnough.send()).isEqualTo("end");
    }

    @Test
    void valuesThatMultiplyWithoutEndStopAtALimit() {
        String megabyte = "x".repeat(1_000_000);
        Resolution resolution = resolveInEnvironment("{{big}}".repeat(18), "big", megabyte);

        assertThat(resolution.complete()).isFalse();
        assertThat(resolution.unresolved()).containsExactly("big");
        assertThat(resolution.reasons().get("big")).isEqualTo("Its value expands to more text than this tool resolves.");
        assertThat(resolveInEnvironment("{{big}}".repeat(3), "big", megabyte).send()).hasSize(3_000_000);
    }

    // ------------------------------------------------------------------------------------------
    // Secrets read from the operating system
    // ------------------------------------------------------------------------------------------

    @Test
    void aPaveVariableOfTheOperatingSystemIsReadAndIsAlwaysASecret() {
        operatingSystem.put("PAVE_GATEWAY_REF", "os-value-7781");
        // The variable that points at it is not called anything that reads as a credential.
        Resolution resolution = resolveInEnvironment("https://example.invalid/x?ref={{gatewayRef}}", "gatewayRef", " {{env:PAVE_GATEWAY_REF}} ");

        assertThat(resolution.send()).isEqualTo("https://example.invalid/x?ref=os-value-7781");
        assertThat(resolution.display()).isEqualTo("https://example.invalid/x?ref=[secret gatewayRef]");
        VariableUse pointing = use(resolution, "gatewayRef");
        assertThat(pointing.source()).isEqualTo(VariableSource.OPERATING_SYSTEM);
        assertThat(pointing.secret()).isTrue();
        assertThat(pointing.display()).isEqualTo("[secret gatewayRef]");
        VariableUse read = use(resolution, "env:PAVE_GATEWAY_REF");
        assertThat(read.source()).isEqualTo(VariableSource.OPERATING_SYSTEM);
        assertThat(read.secret()).isTrue();
        assertThat(read.display()).isEqualTo("[secret env:PAVE_GATEWAY_REF]");
    }

    @Test
    void aReferenceWrittenStraightIntoATemplateIsReadTheSameWay() {
        operatingSystem.put("PAVE_MEMBER_API_KEY", "os-value-4410");
        Resolution resolution = resolver.resolve("{{env:PAVE_MEMBER_API_KEY}}", List.of());

        assertThat(resolution.send()).isEqualTo("os-value-4410");
        assertThat(resolution.display()).isEqualTo("[secret env:PAVE_MEMBER_API_KEY]");
        assertThat(resolution.variables()).containsExactly(
                new VariableUse("env:PAVE_MEMBER_API_KEY", VariableSource.OPERATING_SYSTEM, true, "[secret env:PAVE_MEMBER_API_KEY]"));
    }

    @Test
    void anOperatingSystemVariableThatIsNotNamedForThisToolCannotBeRead() {
        operatingSystem.put("HOME", "/home/placeholder-user");
        operatingSystem.put("AWS_SECRET_ACCESS_KEY", "os-value-must-not-leak");
        operatingSystem.put("PAVE", "os-value-bare-prefix");

        for (String name : new String[] {"HOME", "AWS_SECRET_ACCESS_KEY", "PAVE", "pave_lowercase", "XPAVE_X", "PAVE_A B", "PAVE_"}) {
            Resolution direct = resolver.resolve("https://example.invalid/?leak={{env:" + name + "}}", List.of());
            assertThat(direct.complete()).as(name).isFalse();
            assertThat(direct.unresolved()).as(name).containsExactly("env:" + name);
            assertThat(direct.reasons().get("env:" + name)).as(name).startsWith(
                    "Only operating system variables whose names start with PAVE_ and hold nothing but letters, digits and underscores can be read");
            assertThat(direct.shown()).as(name).doesNotContain("placeholder-user").doesNotContain("os-value");

            Resolution pointed = resolveInEnvironment("{{leak}}", "leak", "{{env:" + name + "}}");
            assertThat(pointed.complete()).as(name).isFalse();
            assertThat(pointed.unresolved()).as(name).containsExactly("env:" + name);
        }
        assertThat(resolver.resolve("{{env:}}", List.of()).unresolved()).containsExactly("env:");
        assertThat(resolver.resolve("{{env:}}", List.of()).reasons().get("env:")).isEqualTo("It names no operating system variable.");
    }

    @Test
    void anOperatingSystemVariableThatIsNotSetIsUnresolvedAndTheReasonNamesIt() {
        Resolution resolution = resolveInEnvironment("Basic {{clientSecret}}", "clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}");

        assertThat(resolution.complete()).isFalse();
        assertThat(resolution.unresolved()).containsExactly("env:PAVE_ONYX_CLIENT_SECRET");
        // The variable clientSecret is there. What is missing is the operating system variable, and the reason says so.
        assertThat(resolution.reasons()).containsOnlyKeys("env:PAVE_ONYX_CLIENT_SECRET");
        assertThat(resolution.reasons().get("env:PAVE_ONYX_CLIENT_SECRET")).isEqualTo(
                "The operating system variable PAVE_ONYX_CLIENT_SECRET is not set where this tool is running. Set it and restart the "
                        + "tool, or type a value for this run. It is read because {{clientSecret}} in the environment \"Onyx Sandbox\" "
                        + "points at it.");
        assertThat(use(resolution, "clientSecret").source()).isEqualTo(VariableSource.UNRESOLVED);
        assertThat(use(resolution, "clientSecret").secret()).isTrue();
        assertThat(resolution.explanations().get("clientSecret")).startsWith(
                "It is defined in the environment \"Onyx Sandbox\" as a reference to the operating system.");

        operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "   ");
        assertThat(resolveInEnvironment("{{clientSecret}}", "clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}").complete()).isFalse();
        operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "os-value-9034");
        assertThat(resolveInEnvironment("{{clientSecret}}", "clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}").send()).isEqualTo("os-value-9034");
    }

    @Test
    void aValueTypedForTheRunWinsOverTheOperatingSystemAndIsStillMasked() {
        operatingSystem.put("PAVE_KEY", "os-value-1111");
        Resolution resolution = resolver.resolve("{{apiRef}}", List.of(VariableLayer.runValues(values("env:PAVE_KEY", "typed-value-2222")),
                VariableLayer.environment("Onyx Sandbox", variables("apiRef", "{{env:PAVE_KEY}}"))));

        assertThat(resolution.send()).isEqualTo("typed-value-2222");
        assertThat(resolution.display()).doesNotContain("typed-value-2222").doesNotContain("os-value-1111");
        assertThat(use(resolution, "env:PAVE_KEY").source()).isEqualTo(VariableSource.RUN_VALUE);
        assertThat(use(resolution, "env:PAVE_KEY").secret()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Values the tool generates
    // ------------------------------------------------------------------------------------------

    @Test
    void theDynamicVariablesThatNeedNoScriptAreGenerated() {
        Resolution resolution = resolver.resolve("{{$guid}}|{{$randomUUID}}|{{$timestamp}}|{{$isoTimestamp}}", List.of());

        assertThat(resolution.send()).isEqualTo(
                "00000000-0000-4000-8000-000000000001|00000000-0000-4000-8000-000000000002|1767625445|2026-01-05T15:04:05.070Z");
        assertThat(resolution.variables()).extracting(VariableUse::source).containsOnly(VariableSource.GENERATED);
        assertThat(resolution.variables()).extracting(VariableUse::name).containsExactly("$guid", "$randomUUID", "$timestamp", "$isoTimestamp");
        assertThat(resolution.variables().get(0).display()).isEqualTo("00000000-0000-4000-8000-000000000001");
    }

    @Test
    void everyUseOfAGeneratedIdentifierGetsItsOwnValueAsInPostman() {
        Resolution resolution = resolver.resolve("{\"a\": \"{{$guid}}\", \"b\": \"{{$guid}}\"}", List.of());

        assertThat(resolution.send()).isEqualTo(
                "{\"a\": \"00000000-0000-4000-8000-000000000001\", \"b\": \"00000000-0000-4000-8000-000000000002\"}");
        // One entry for each use, since each has its own value.
        assertThat(resolution.variables()).extracting(VariableUse::display)
                .containsExactly("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002");
    }

    @Test
    void theRealGeneratorsProduceValuesOfTheRightShape() {
        Resolution resolution = VariableResolver.forProcess(CLOCK).resolve("{{$guid}} {{$randomUUID}}", List.of());

        String[] made = resolution.send().split(" ");
        assertThat(made).hasSize(2).allMatch(value -> value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertThat(made[0]).isNotEqualTo(made[1]);
    }

    @Test
    void anyOtherDynamicVariableIsReportedAsSomethingPostmanWouldHaveComputed() {
        Resolution resolution = resolver.resolve("{{$randomInt}}-{{$randomFirstName}}", List.of());

        assertThat(resolution.complete()).isFalse();
        assertThat(resolution.unresolved()).containsExactly("$randomInt", "$randomFirstName");
        assertThat(resolution.reasons().get("$randomInt")).isEqualTo(
                "Postman computes {{$randomInt}} itself and this tool does not. Type a value for it for this run.");
        // Typing one is the way out, and it works.
        assertThat(resolver.resolve("{{$randomInt}}", List.of(VariableLayer.runValues(values("$randomInt", "42")))).send()).isEqualTo("42");
    }

    // ------------------------------------------------------------------------------------------
    // Masking
    // ------------------------------------------------------------------------------------------

    @Test
    void aSecretIsMaskedInTheFormThatIsShownAndIntactInTheFormThatIsSent() {
        Map<String, PostmanVariable> environment = new LinkedHashMap<>(variables("clientId", "pave-client", "apiKey", "hunter2-by-name"));
        environment.put("tenantCode", new PostmanVariable("tenantCode", "hunter2-by-type", true, "secret"));
        Resolution resolution = resolver.resolve("id={{clientId}}&key={{apiKey}}&tenant={{tenantCode}}",
                List.of(VariableLayer.environment("Onyx Sandbox", environment)));

        assertThat(resolution.send()).isEqualTo("id=pave-client&key=hunter2-by-name&tenant=hunter2-by-type");
        assertThat(resolution.display()).isEqualTo("id=pave-client&key=[secret apiKey]&tenant=[secret tenantCode]");
        assertThat(use(resolution, "clientId").secret()).isFalse();
        assertThat(use(resolution, "clientId").display()).isEqualTo("pave-client");
        assertThat(use(resolution, "apiKey").secret()).isTrue();
        assertThat(use(resolution, "apiKey").display()).isEqualTo("[secret apiKey]");
        assertThat(use(resolution, "tenantCode").secret()).isTrue();
        assertThat(use(resolution, "tenantCode").display()).isEqualTo("[secret tenantCode]");
    }

    @Test
    void aSecretReachedThroughAnotherVariableIsMaskedThere() {
        Resolution resolution = resolveInEnvironment("{{greeting}}", "greeting", "Bearer {{accessToken}} for {{clientId}}",
                "accessToken", "hunter2-nested", "clientId", "pave-client");

        assertThat(resolution.send()).isEqualTo("Bearer hunter2-nested for pave-client");
        assertThat(resolution.display()).isEqualTo("Bearer [secret accessToken] for pave-client");
        // The variable that leads to the secret is not a secret itself, and what is shown for it still hides the secret.
        assertThat(use(resolution, "greeting").secret()).isFalse();
        assertThat(use(resolution, "greeting").display()).isEqualTo("Bearer [secret accessToken] for pave-client");
    }

    @Test
    void aSecretBuiltFromOtherVariablesIsMaskedAsAWhole() {
        Resolution resolution = resolveInEnvironment("{{authHeader}}", "authHeader", "Basic {{clientId}}:{{plainPart}}",
                "clientId", "pave-client", "plainPart", "hunter2-part");

        assertThat(resolution.send()).isEqualTo("Basic pave-client:hunter2-part");
        assertThat(resolution.display()).isEqualTo("[secret authHeader]");
        assertThat(resolution.pieces()).hasSize(1);
        assertThat(resolution.pieces().get(0).kind()).isEqualTo(Resolution.Kind.SECRET);
    }

    @Test
    void aValueTypedOverASecretIsASecretToo() {
        Map<String, PostmanVariable> environment = Map.of("tenantCode", new PostmanVariable("tenantCode", "hunter2-stored", true, "secret"));
        Resolution resolution = resolver.resolve("{{tenantCode}}", List.of(VariableLayer.runValues(values("tenantCode", "hunter2-typed")),
                VariableLayer.environment("Onyx Sandbox", environment)));

        assertThat(resolution.send()).isEqualTo("hunter2-typed");
        assertThat(resolution.display()).isEqualTo("[secret tenantCode]");
        assertThat(use(resolution, "tenantCode").source()).isEqualTo(VariableSource.RUN_VALUE);
    }

    @Test
    void noPrintedOrSerialisedFormEverHoldsASecretValue() {
        operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "hunter2-from-os");
        Map<String, PostmanVariable> environment = new LinkedHashMap<>(variables("baseUrl", "https://example.invalid",
                "apiKey", "hunter2-by-name", "clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}", "greeting", "Hello {{apiKey}}",
                "authHeader", "Bearer {{apiKey}}"));
        environment.put("tenantCode", new PostmanVariable("tenantCode", "hunter2-by-type", true, "secret"));
        List<VariableLayer> layers = List.of(VariableLayer.runValues(values("password", "hunter2-typed")),
                VariableLayer.environment("Onyx Sandbox", environment));
        Resolution resolution = resolver.resolve(
                "{{baseUrl}}/x?k={{apiKey}}&s={{clientSecret}}&t={{tenantCode}}&g={{greeting}}&h={{authHeader}}&p={{password}}&m={{missing}}",
                layers);
        Resolution complete = resolver.resolve(
                "{{baseUrl}}/x?k={{apiKey}}&s={{clientSecret}}&t={{tenantCode}}&g={{greeting}}&h={{authHeader}}&p={{password}}", layers);

        assertThat(complete.send()).contains("hunter2-by-name").contains("hunter2-from-os").contains("hunter2-by-type").contains("hunter2-typed");
        for (Resolution each : List.of(resolution, complete)) {
            List<Object> forms = new ArrayList<>(List.of(each, each.text(), each.variables(), each.pieces(), each.reasons(),
                    each.explanations(), each.shown(), layers));
            if (each.display() != null) {
                forms.add(each.display());
            }
            for (Object form : forms) {
                assertThat(form.toString()).as(form.getClass().getSimpleName()).doesNotContain("hunter2");
            }
            String json = JsonSafe.mapper().writeValueAsString(Map.of("text", each.text(), "variables", each.variables()));
            assertThat(json).doesNotContain("hunter2").contains("[secret apiKey]");
        }
        assertThat(complete.display()).isEqualTo("https://example.invalid/x?k=[secret apiKey]&s=[secret clientSecret]&t=[secret tenantCode]"
                + "&g=Hello [secret apiKey]&h=[secret authHeader]&p=[secret password]");
    }

    @Test
    void aSecretThatCouldNotBeCompletedIsOneHoleAndShowsNothingOfItsText() {
        Resolution resolution = resolveInEnvironment("{{authHeader}}", "authHeader", "Basic hunter2-literal-part {{neverDefined}}");

        assertThat(resolution.complete()).isFalse();
        assertThat(resolution.unresolved()).containsExactly("neverDefined");
        assertThat(resolution.shown()).isEqualTo("{{authHeader}}");
        assertThat(resolution.pieces()).hasSize(1);
        assertThat(resolution.pieces().get(0).kind()).isEqualTo(Resolution.Kind.MISSING);
    }
}
