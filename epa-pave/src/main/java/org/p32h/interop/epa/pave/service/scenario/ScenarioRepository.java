package org.p32h.interop.epa.pave.service.scenario;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Loads mock scenario files, one JSON file per scenario, grouped by protocol directory.
 *
 * <p>Scenarios are read when they are asked for, not once at startup, so QA can add or edit a
 * case without recompiling and, with an external directory, without restarting. When
 * {@code app.scenario-dir} is set it replaces the bundled scenarios: a protocol's files are then
 * read from {@code <scenario-dir>/<protocol>/}.
 *
 * <p>The files are edited by hand, so each is read tolerantly and a file that cannot be used is
 * reported by name with the reason. It is never skipped silently, because a tester who added a
 * case and does not see it in the list needs to know why.
 */
@Component
public class ScenarioRepository {

    /** The scenarios found for a protocol, and the files that could not be used. */
    public record Loaded(List<Scenario> scenarios, List<String> problems) {
    }

    private final AppProperties app;

    public ScenarioRepository(AppProperties app) {
        this.app = app;
    }

    /** Every usable scenario for a protocol directory such as {@code crd}, in numbered order. */
    public List<Scenario> list(String protocolDirectory) {
        return load(protocolDirectory).scenarios();
    }

    public Loaded load(String protocolDirectory) {
        List<Scenario> scenarios = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> file : readFiles(protocolDirectory, problems).entrySet()) {
            try {
                scenarios.add(parse(file.getKey(), file.getValue()));
            } catch (IllegalArgumentException e) {
                problems.add(file.getKey() + ": " + e.getMessage());
            }
        }
        scenarios.sort(Comparator.comparingInt(Scenario::number).thenComparing(Scenario::id));
        Map<String, String> seen = new LinkedHashMap<>();
        List<Scenario> unique = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            String earlier = seen.putIfAbsent(scenario.id(), scenario.source());
            if (earlier != null) {
                problems.add(scenario.source() + ": the id \"" + scenario.id() + "\" is already used by " + earlier + ".");
            } else {
                unique.add(scenario);
            }
        }
        return new Loaded(List.copyOf(unique), List.copyOf(problems));
    }

    /**
     * Picks the scenario a mock answers from.
     *
     * <p>In order: a scenario chosen explicitly, then a sentinel member ID, then a sentinel NPI,
     * then the first scenario in the list. The default is stated in the result so it cannot pass
     * for a deliberate choice.
     *
     * @throws ValidationException when an explicitly chosen scenario id does not exist
     */
    public ScenarioSelection select(String protocolDirectory, MockSelection selection) {
        return select(protocolDirectory, selection, null);
    }

    /**
     * As {@link #select(String, MockSelection)}, and when neither an explicit choice nor the form's
     * member ID or NPI selects a scenario, a sentinel member ID that appears in the request body
     * does. That serves a tester who loaded a sample body and typed the sentinel into the JSON.
     */
    public ScenarioSelection select(String protocolDirectory, MockSelection selection, String requestBody) {
        List<Scenario> scenarios = list(protocolDirectory);
        if (scenarios.isEmpty()) {
            throw new IllegalStateException("There are no usable mock scenarios for " + protocolDirectory + ".");
        }
        if (selection != null && Texts.present(selection.scenarioId())) {
            String wanted = selection.scenarioId().strip();
            return scenarios.stream().filter(s -> s.id().equals(wanted)).findFirst()
                    .map(s -> new ScenarioSelection(s, "chosen in the scenario selector"))
                    .orElseThrow(() -> ValidationException.of("scenarioId", "There is no scenario with id \"" + wanted + "\"."));
        }
        if (selection != null && Texts.present(selection.memberId())) {
            String memberId = MemberIds.noSpace(selection.memberId());
            for (Scenario scenario : scenarios) {
                // Sentinels are this tool's own identifiers, so a difference in case is forgiven.
                if (Texts.present(scenario.sentinelMemberId())
                        && MemberIds.noSpace(scenario.sentinelMemberId()).equalsIgnoreCase(memberId)) {
                    return new ScenarioSelection(scenario, "selected by the sentinel member ID " + scenario.sentinelMemberId());
                }
            }
        }
        if (selection != null && Texts.present(selection.npi())) {
            String npi = selection.npi().strip();
            for (Scenario scenario : scenarios) {
                if (Texts.present(scenario.sentinelNpi()) && scenario.sentinelNpi().strip().equals(npi)) {
                    return new ScenarioSelection(scenario, "selected by the sentinel NPI " + scenario.sentinelNpi());
                }
            }
        }
        if (Texts.present(requestBody)) {
            for (Scenario scenario : scenarios) {
                if (Texts.present(scenario.sentinelMemberId()) && requestBody.contains("\"" + scenario.sentinelMemberId() + "\"")) {
                    return new ScenarioSelection(scenario, "selected by the sentinel member ID " + scenario.sentinelMemberId()
                            + " found in the request body");
                }
            }
        }
        return new ScenarioSelection(scenarios.get(0), "the default, because no scenario was chosen and no sentinel matched");
    }

    private Map<String, String> readFiles(String protocolDirectory, List<String> problems) {
        Map<String, String> files = new java.util.TreeMap<>();
        if (Texts.present(app.scenarioDir())) {
            Path directory = Path.of(app.scenarioDir().strip()).resolve(protocolDirectory);
            if (!Files.isDirectory(directory)) {
                problems.add("The scenario directory " + directory + " does not exist.");
                return files;
            }
            try (Stream<Path> stream = Files.list(directory)) {
                for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        files.put(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        problems.add(path.getFileName() + ": the file could not be read.");
                    }
                }
            } catch (IOException e) {
                problems.add("The scenario directory " + directory + " could not be listed.");
            }
            return files;
        }
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:scenarios/" + protocolDirectory + "/*.json");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    files.put(resource.getFilename(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            problems.add("The bundled scenarios for " + protocolDirectory + " could not be read.");
        }
        return files;
    }

    /** Reads one scenario file. Package-private so the parsing rules can be tested on text alone. */
    static Scenario parse(String source, String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (!parsed.ok()) {
            throw new IllegalArgumentException(parsed.error());
        }
        JsonNode root = parsed.node();
        if (!root.isObject()) {
            throw new IllegalArgumentException("a scenario file must hold one JSON object.");
        }
        String id = JsonSafe.presentText(root, "id");
        if (id == null) {
            throw new IllegalArgumentException("the file has no id.");
        }
        String label = JsonSafe.presentText(root, "label");
        if (label == null) {
            throw new IllegalArgumentException("the file has no label.");
        }
        Integer number = JsonSafe.integer(root, "number");
        if (number == null) {
            throw new IllegalArgumentException("the file has no number, or its number is not a whole number.");
        }

        ScenarioStep main = root.has("behavior") || root.has("response") || root.has("responseText") || root.has("httpStatus")
                ? parseStep(root, "the scenario") : null;
        if (main != null) {
            // The top-level expect block is the claim of the scenario as a whole, kept on the scenario.
            main = new ScenarioStep(main.behavior(), main.httpStatus(), main.delayMillis(), main.body(), null);
        }
        Map<String, ScenarioStep> steps = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> step : JsonSafe.properties(JsonSafe.child(root, "steps"))) {
            steps.put(step.getKey(), parseStep(step.getValue(), "step \"" + step.getKey() + "\""));
        }
        if (main == null && steps.isEmpty()) {
            throw new IllegalArgumentException("the file describes no call: it has neither a behavior nor any steps.");
        }

        return new Scenario(id, number, label, JsonSafe.presentText(root, "description"),
                JsonSafe.presentText(root, "sentinelMemberId"), JsonSafe.presentText(root, "sentinelNpi"), main, steps,
                parseExpect(JsonSafe.child(root, "expect")), JsonSafe.presentText(root, "basis"),
                JsonSafe.presentText(root, "assumption"), root, source);
    }

    private static ScenarioStep parseStep(JsonNode node, String what) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(what + " must be a JSON object.");
        }
        String behaviorText = JsonSafe.presentText(node, "behavior");
        ScenarioBehavior behavior;
        try {
            // A missing behavior means respond. That is the only reading under which a file with a
            // status and a body and nothing else makes sense, and it is stated here, not guessed later.
            behavior = behaviorText == null ? ScenarioBehavior.RESPOND : ScenarioBehavior.parse(behaviorText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " has the behavior \"" + behaviorText + "\". " + e.getMessage());
        }
        Integer status = JsonSafe.integer(node, "httpStatus");
        if (behavior == ScenarioBehavior.RESPOND) {
            if (status == null) {
                throw new IllegalArgumentException(what + " responds but has no httpStatus, or its httpStatus is not a number.");
            }
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException(what + " has the httpStatus " + status + ", which is not an HTTP status.");
            }
        } else {
            status = null;
        }
        Long delay = JsonSafe.longValue(node, "delayMillis");
        if (node.has("delayMillis") && (delay == null || delay < 0)) {
            throw new IllegalArgumentException(what + " has a delayMillis that is not a whole number of milliseconds.");
        }
        String body = null;
        if (behavior == ScenarioBehavior.RESPOND) {
            if (node.has("responseText")) {
                // Used for a body that is deliberately not JSON, such as a malformed or truncated one.
                body = JsonSafe.text(node, "responseText");
                if (body == null) {
                    throw new IllegalArgumentException(what + " has a responseText that is not text.");
                }
            } else if (node.has("response") && !node.get("response").isNull()) {
                body = JsonSafe.pretty(node.get("response"));
            }
        }
        return new ScenarioStep(behavior, status, delay == null ? 0L : delay, body, parseExpect(JsonSafe.child(node, "expect")));
    }

    private static ScenarioExpect parseExpect(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String outcome = JsonSafe.presentText(node, "outcome");
        if (outcome == null) {
            throw new IllegalArgumentException("the expect block has no outcome.");
        }
        Map<String, String> codes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> code : JsonSafe.properties(JsonSafe.child(node, "codes"))) {
            String value = JsonSafe.presentText(code.getValue());
            if (value == null) {
                throw new IllegalArgumentException("the expect block has no value for the code \"" + code.getKey() + "\".");
            }
            codes.put(code.getKey(), value);
        }
        return new ScenarioExpect(outcome, JsonSafe.presentText(node, "errorType"), JsonSafe.integer(node, "httpStatus"), codes,
                JsonSafe.presentText(node, "delegatedVendor"));
    }
}
