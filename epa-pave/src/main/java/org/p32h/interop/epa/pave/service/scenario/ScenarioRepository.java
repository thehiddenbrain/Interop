package org.p32h.interop.epa.pave.service.scenario;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Loads mock scenario files, one JSON file per scenario, grouped by protocol directory.
 *
 * <p>Every protocol directory is checked once when the application starts, and a damaged set
 * stops the start with each file and its reason named: a file that is not JSON or not a scenario,
 * an id used twice, a sentinel member ID or NPI claimed by two files of one protocol, a top-level
 * key the loader does not know (which is how a misspelt key shows itself), and a protocol
 * directory that is missing or empty. A damaged file left to be found at request time would turn
 * into a run answered by the wrong scenario, which nobody could explain.
 *
 * <p>After that, scenarios are read again each time they are asked for, so QA can edit a case in
 * the directory {@code app.scenario-dir} names without restarting. When that setting is given it
 * replaces the bundled scenarios: a protocol's files are then read from
 * {@code <scenario-dir>/<protocol>/}. A set damaged while the tool runs is not answered from: the
 * run is refused with HTTP 422 naming every file and its reason, and the scenario list shows the
 * same problems, until the files are put right. Answering from the files that are left would let
 * a sentinel whose file broke fall to the default scenario without a word.
 *
 * <p>Nothing of a scenario body is logged or put in a message. A problem names the file, the rule
 * it broke and, at most, a key or a sentinel, which are this tool's own identifiers.
 */
@Component
public class ScenarioRepository implements InitializingBean {

    /** Every protocol directory the mock clients read. Each must exist and hold at least one scenario. */
    public static final List<String> PROTOCOLS = List.of("crd", "dtr", "pas", "raw");

    /** This tool's own sentinel member IDs, compared in their no-space upper-case form. */
    static final Pattern SENTINEL = Pattern.compile("P32H-[A-Z0-9]+(?:-[A-Z0-9]+)*");

    /**
     * Every top-level key a scenario file may hold. The protocols read their own sections from
     * {@link Scenario#raw()}: {@code nextQuestion} is the DTR adaptive rule, and {@code paths} and
     * {@code responses} are the DTR claims the scenario tests drive. A key outside this list is
     * refused, because a misspelt one would otherwise be dropped and its scenario would quietly
     * lose what the key held.
     */
    static final Set<String> KNOWN_KEYS = Set.of("id", "number", "label", "description", "basis", "assumption", "sentinelMemberId",
            "sentinelMemberIds", "sentinelNpi", "sentinelNpis", "behavior", "httpStatus", "delayMillis", "response", "responseText",
            "expect", "steps", "nextQuestion", "paths", "responses");

    /** The scenarios found for a protocol, and the files that could not be used. */
    public record Loaded(List<Scenario> scenarios, List<String> problems) {
    }

    private final AppProperties app;

    public ScenarioRepository(AppProperties app) {
        this.app = app;
    }

    /**
     * Every scenario for a protocol directory such as {@code crd}, in numbered order.
     *
     * @throws BadInputException when any file of the protocol has a problem. {@link #load} lists
     *                           the problems without refusing
     */
    public List<Scenario> list(String protocolDirectory) {
        return usable(protocolDirectory);
    }

    /** Refuses to start on a damaged scenario set. Spring calls this once the bean is made. */
    @Override
    public void afterPropertiesSet() {
        checkEveryProtocol();
    }

    /**
     * Reads every protocol directory and throws when any of them has a problem, naming each file
     * and its reason.
     *
     * @throws IllegalStateException when the scenario set cannot be used
     */
    public void checkEveryProtocol() {
        List<String> found = new ArrayList<>();
        for (String protocol : PROTOCOLS) {
            List<String> problems = load(protocol).problems();
            if (!problems.isEmpty()) {
                found.add("Mock scenarios for " + protocol + ": " + String.join(" ", problems));
            }
        }
        if (!found.isEmpty()) {
            String where = Texts.present(app.scenarioDir()) ? "in " + Path.of(app.scenarioDir().strip()) + " (app.scenario-dir)"
                    : "bundled with the application";
            throw new IllegalStateException("The mock scenarios " + where + " cannot be used, so the tool does not start. "
                    + String.join(" ", found));
        }
    }

    public Loaded load(String protocolDirectory) {
        List<Scenario> scenarios = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, String> files = readFiles(protocolDirectory, problems);
        if (files.isEmpty() && problems.isEmpty()) {
            problems.add(Texts.present(app.scenarioDir())
                    ? "The scenario directory " + Path.of(app.scenarioDir().strip()).resolve(protocolDirectory) + " holds no scenario files."
                    : "There are no bundled scenario files for " + protocolDirectory + ".");
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
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
        // Two files claiming one sentinel would leave the choice to the order of their numbers.
        Map<String, String> memberSentinels = new LinkedHashMap<>();
        Map<String, String> npiSentinels = new LinkedHashMap<>();
        for (Scenario scenario : unique) {
            for (String sentinel : scenario.sentinelMemberIds()) {
                String key = memberKey(sentinel);
                String earlier = memberSentinels.putIfAbsent(key, scenario.source());
                if (earlier != null) {
                    problems.add(scenario.source() + ": the sentinel member ID " + key + " is already claimed by " + earlier
                            + ". One sentinel selects one scenario.");
                }
            }
            for (String sentinel : scenario.sentinelNpis()) {
                String earlier = npiSentinels.putIfAbsent(sentinel.strip(), scenario.source());
                if (earlier != null) {
                    problems.add(scenario.source() + ": the sentinel NPI " + sentinel.strip() + " is already claimed by " + earlier
                            + ". One sentinel selects one scenario.");
                }
            }
        }
        return new Loaded(List.copyOf(unique), List.copyOf(problems));
    }

    /**
     * The scenarios of a protocol, refused when any file of it has a problem. Every run comes
     * through here, so a set damaged while the tool runs is never answered from in part.
     *
     * @throws BadInputException naming each file and its reason
     */
    private List<Scenario> usable(String protocolDirectory) {
        Loaded loaded = load(protocolDirectory);
        if (!loaded.problems().isEmpty()) {
            throw new BadInputException("The mock scenarios for " + protocolDirectory + " cannot be used, so nothing was answered. "
                    + String.join(" ", loaded.problems()),
                    "Put the files named right, then run again. The scenario list for " + protocolDirectory + " shows the same problems.");
        }
        return loaded.scenarios();
    }

    /** Sentinels are this tool's own identifiers, so a difference in case or spacing is forgiven. */
    private static String memberKey(String memberId) {
        return MemberIds.noSpace(memberId).toUpperCase(Locale.ROOT);
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
        List<Scenario> scenarios = usable(protocolDirectory);
        if (selection != null && Texts.present(selection.scenarioId())) {
            String wanted = selection.scenarioId().strip();
            return scenarios.stream().filter(s -> s.id().equals(wanted)).findFirst()
                    .map(s -> new ScenarioSelection(s, "chosen in the scenario selector"))
                    .orElseThrow(() -> ValidationException.of("scenarioId", "There is no scenario with id \"" + wanted + "\"."));
        }
        if (selection != null && Texts.present(selection.memberId())) {
            String memberId = memberKey(selection.memberId());
            for (Scenario scenario : scenarios) {
                for (String sentinel : scenario.sentinelMemberIds()) {
                    if (memberKey(sentinel).equals(memberId)) {
                        return new ScenarioSelection(scenario, "selected by the sentinel member ID " + sentinel);
                    }
                }
            }
        }
        if (selection != null && Texts.present(selection.npi())) {
            String npi = selection.npi().strip();
            for (Scenario scenario : scenarios) {
                for (String sentinel : scenario.sentinelNpis()) {
                    if (sentinel.strip().equals(npi)) {
                        return new ScenarioSelection(scenario, "selected by the sentinel NPI " + sentinel);
                    }
                }
            }
        }
        if (Texts.present(requestBody)) {
            // Each text value of the body is compared whole, as the form's member ID is: no spaces, any letter case.
            // A body that is not JSON holds no values, and a sentinel inside a longer value is not one.
            Set<String> values = new HashSet<>();
            JsonSafe.Parsed parsed = JsonSafe.parse(requestBody);
            if (parsed.ok()) {
                collectTexts(parsed.node(), values);
            }
            for (Scenario scenario : scenarios) {
                for (String sentinel : scenario.sentinelMemberIds()) {
                    if (values.contains(memberKey(sentinel))) {
                        return new ScenarioSelection(scenario, "selected by the sentinel member ID " + sentinel + " found in the request body");
                    }
                }
            }
        }
        return new ScenarioSelection(scenarios.get(0), "the default, because no scenario was chosen and no sentinel matched");
    }

    private static void collectTexts(JsonNode node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            out.add(memberKey(node.stringValue()));
            return;
        }
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(node)) {
            collectTexts(property.getValue(), out);
        }
        for (JsonNode element : JsonSafe.elements(node)) {
            collectTexts(element, out);
        }
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
            // The parser's own wording speaks of a body. Here it is the file, and only its position is kept.
            throw new IllegalArgumentException(parsed.error().replaceFirst("^The body", "The file"));
        }
        JsonNode root = parsed.node();
        if (!root.isObject()) {
            throw new IllegalArgumentException("a scenario file must hold one JSON object.");
        }
        if (parsed.repeatedKeys()) {
            throw new IllegalArgumentException("it repeats a key, and only the last value of a repeated key would count. Keep one.");
        }
        List<String> unknown = JsonSafe.properties(root).stream().map(Map.Entry::getKey).filter(key -> !KNOWN_KEYS.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("it has " + (unknown.size() == 1 ? "a key" : "keys") + " this tool does not know: "
                    + String.join(", ", unknown) + ". Check " + (unknown.size() == 1 ? "its" : "their") + " spelling.");
        }
        List<String> memberSentinels = sentinels(root, "sentinelMemberId", "sentinelMemberIds", "sentinel member ID");
        List<String> npiSentinels = sentinels(root, "sentinelNpi", "sentinelNpis", "sentinel NPI");
        for (String sentinel : memberSentinels) {
            if (!SENTINEL.matcher(memberKey(sentinel)).matches()) {
                // The value is not quoted: something that is not a sentinel could be a real member ID.
                throw new IllegalArgumentException("one of its sentinel member IDs is not a P32H- sentinel. A scenario is selected only by "
                        + "this tool's own identifiers, so nothing that could be a real member ID is written in a scenario file.");
            }
        }
        for (String sentinel : npiSentinels) {
            if (!Npi.isValid(sentinel.strip())) {
                throw new IllegalArgumentException("one of its sentinel NPIs does not pass the NPI check, so no request could ever carry it. "
                        + "Make one with Npi.withCheckDigit.");
            }
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
                memberSentinels.isEmpty() ? null : memberSentinels.get(0), npiSentinels.isEmpty() ? null : npiSentinels.get(0), main, steps,
                parseExpect(JsonSafe.child(root, "expect")), JsonSafe.presentText(root, "basis"),
                JsonSafe.presentText(root, "assumption"), root, source, memberSentinels, npiSentinels);
    }

    /**
     * The sentinels a file names under the single key and the list key, single first. A key that is
     * present must hold what it says: one text, or a list of texts. A blank single key means none,
     * as it always has.
     */
    private static List<String> sentinels(JsonNode root, String singleKey, String listKey, String what) {
        List<String> out = new ArrayList<>();
        JsonNode single = JsonSafe.child(root, singleKey);
        if (!JsonSafe.nullOrMissing(single)) {
            if (!single.isString()) {
                throw new IllegalArgumentException("its " + singleKey + " is not text.");
            }
            String value = JsonSafe.presentText(single);
            if (value != null) {
                out.add(value);
            }
        }
        JsonNode list = JsonSafe.child(root, listKey);
        if (!JsonSafe.nullOrMissing(list)) {
            if (!list.isArray()) {
                throw new IllegalArgumentException("its " + listKey + " is not a list.");
            }
            for (JsonNode element : JsonSafe.elements(list)) {
                String value = element.isString() ? JsonSafe.presentText(element) : null;
                if (value == null) {
                    throw new IllegalArgumentException("its " + listKey + " holds something that is not a " + what + ".");
                }
                out.add(value);
            }
        }
        Set<String> seen = new HashSet<>();
        for (String value : out) {
            String key = singleKey.equals("sentinelMemberId") ? memberKey(value) : value;
            if (!seen.add(key)) {
                throw new IllegalArgumentException("it names one " + what + " twice.");
            }
        }
        return out;
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
