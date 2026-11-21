package org.p32h.interop.epa.pave.service.scenario;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.SampleCase;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Loads mock scenario files, one JSON file per scenario, grouped by protocol directory.
 *
 * <p>On the mock profile every protocol directory is checked once when the application starts
 * ({@link ScenarioCheckAtStart}), and a damaged set stops the start with each file and its reason
 * named: a file that is not JSON or not a scenario,
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
public class ScenarioRepository {

    /**
     * How a scenario chosen by its id is said to have been chosen. A replay on mock sends the id the
     * original run recorded the same way, and says so in its own words in place of these.
     */
    public static final String CHOSEN_BY_ID = "chosen in the scenario selector";

    /** Every protocol directory the mock clients read. Each must exist and hold at least one scenario. */
    public static final List<String> PROTOCOLS = List.of("crd", "dtr", "pas", "raw");

    /** The protocol directories whose files answer for one another's sentinels: DTR and PAS files for CRD's, a CRD file for DTR's. */
    static final String CRD = "crd";
    static final String DTR = "dtr";
    static final String PAS = "pas";

    /** This tool's own sentinel member IDs, compared in their no-space upper-case form. */
    static final Pattern SENTINEL = Pattern.compile("P32H-[A-Z0-9]+(?:-[A-Z0-9]+)*");

    /**
     * Every top-level key a scenario file may hold. The protocols read their own sections from
     * {@link Scenario#raw()}: {@code nextQuestion} is the DTR adaptive rule, {@code paths} and
     * {@code responses} are the DTR claims the scenario tests drive. {@code crdSentinelMemberIds}
     * names the CRD sentinels a DTR or PAS scenario answers for the same case, kept apart from the
     * scenario's own sentinels so that the result can say which of the two selected it, and
     * {@code answersDtrSentinels} marks the CRD scenario that answers every DTR sentinel, and
     * {@code sampleCase} names the NPI and procedure codes the mock scenario table fills in with the
     * member ID. A key outside this
     * list is refused, because a misspelt one would otherwise be dropped and its scenario would
     * quietly lose what the key held.
     */
    static final Set<String> KNOWN_KEYS = Set.of("id", "number", "label", "description", "basis", "assumption", "sentinelMemberId",
            "sentinelMemberIds", "sentinelNpi", "sentinelNpis", "crdSentinelMemberIds", "answersDtrSentinels", "behavior", "httpStatus", "delayMillis",
            "response", "responseText", "expect", "steps", "nextQuestion", "paths", "responses", "sampleCase");

    /**
     * The scenarios found for a protocol, the files that could not be used, and what a tester
     * should know that does not stop the set being used: a file in the directory that is not read,
     * the file that is the default, an expect block at odds with its file. Notes never stop the
     * start, because a tester's own directory may hold work in progress, and never quote what a
     * file holds.
     */
    public record Loaded(List<Scenario> scenarios, List<String> problems, List<String> notes, List<String> warnedAtStart) {

        /**
         * @param warnedAtStart the notes that are also said in one WARN line when the application
         *                      starts on a directory of the tester's own: a file that is not read,
         *                      and a sentinel mapping the directory has lost or names wrongly. Each
         *                      is also among {@code notes}
         */
        public Loaded {
            notes = notes == null ? List.of() : List.copyOf(notes);
            warnedAtStart = warnedAtStart == null ? List.of() : List.copyOf(warnedAtStart);
        }

        public Loaded(List<Scenario> scenarios, List<String> problems, List<String> notes) {
            this(scenarios, problems, notes, null);
        }

        public Loaded(List<Scenario> scenarios, List<String> problems) {
            this(scenarios, problems, null, null);
        }
    }

    private final AppProperties app;

    public ScenarioRepository(AppProperties app) {
        this.app = app;
    }

    /**
     * Every scenario for a protocol directory such as {@code crd}, in numbered order.
     *
     * @throws ScenarioSetUnusableException when any file of the protocol has a problem. {@link #load}
     *                                       lists the problems without refusing
     */
    public List<Scenario> list(String protocolDirectory) {
        return usable(protocolDirectory);
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

    /**
     * Every scenario of a protocol directory, every problem that stops the set being used, and
     * every note, those that need the files of another protocol included: a sentinel mapping a
     * directory copied before the mappings became data has lost, an entry of
     * {@code crdSentinelMemberIds} that is no CRD file's sentinel, and DTR files with no CRD file
     * to answer their sentinels. The notes never stop the set being used, so a run reads only
     * {@link #loadOwn}.
     */
    public Loaded load(String protocolDirectory) {
        Loaded own = loadOwn(protocolDirectory);
        Map<String, List<String>> aboutFile = new LinkedHashMap<>();
        List<String> listOnly = new ArrayList<>();
        for (Scenario scenario : own.scenarios()) {
            aboutFile.put(scenario.source(), new ArrayList<>(mappingNotes(protocolDirectory, scenario)));
        }
        if (own.scenarios().stream().anyMatch(scenario -> !scenario.crdSentinelMemberIds().isEmpty())) {
            Loaded crd = loadOwn(CRD);
            // A CRD set that cannot be used says so on its own list; judged against it, every entry would look unknown.
            if (crd.problems().isEmpty()) {
                Set<String> crdSentinels = new HashSet<>();
                crd.scenarios().forEach(scenario -> scenario.sentinelMemberIds().forEach(sentinel -> crdSentinels.add(memberKey(sentinel))));
                for (Scenario scenario : own.scenarios()) {
                    for (String entry : scenario.crdSentinelMemberIds()) {
                        if (!crdSentinels.contains(memberKey(entry))) {
                            aboutFile.get(scenario.source()).add(scenario.source() + ": " + memberKey(entry) + " in its crdSentinelMemberIds is "
                                    + "the sentinel member ID of no CRD scenario, so it selects this file without meaning the same case in CRD.");
                        }
                    }
                }
            }
        }
        if (CRD.equals(protocolDirectory) || DTR.equals(protocolDirectory)) {
            Loaded crd = CRD.equals(protocolDirectory) ? own : loadOwn(CRD);
            Loaded dtr = DTR.equals(protocolDirectory) ? own : loadOwn(DTR);
            if (crd.problems().isEmpty() && dtr.problems().isEmpty() && !crd.scenarios().isEmpty() && !dtr.scenarios().isEmpty()
                    && crd.scenarios().stream().noneMatch(Scenario::answersDtrSentinels)) {
                listOnly.add("No CRD scenario carries \"answersDtrSentinels\": true, so a DTR sentinel member ID typed in CRD selects no CRD "
                        + "scenario and is answered by the CRD default, " + crd.scenarios().get(0).source() + ", and a journey with it does "
                        + "not reach DTR as the DTR scenario's label says.");
            }
        }
        if (listOnly.isEmpty() && aboutFile.values().stream().allMatch(List::isEmpty)) {
            return own;
        }
        List<String> notes = new ArrayList<>(own.notes());
        List<String> warned = new ArrayList<>(own.warnedAtStart());
        List<Scenario> noted = new ArrayList<>();
        for (Scenario scenario : own.scenarios()) {
            List<String> more = aboutFile.get(scenario.source());
            notes.addAll(more);
            warned.addAll(more);
            if (more.isEmpty()) {
                noted.add(scenario);
            } else {
                List<String> all = new ArrayList<>(scenario.notes());
                all.addAll(more);
                noted.add(scenario.withNotes(all));
            }
        }
        notes.addAll(listOnly);
        warned.addAll(listOnly);
        return new Loaded(List.copyOf(noted), own.problems(), notes, warned);
    }

    /**
     * A file that speaks of a sentinel of another protocol selecting it while it lists none. Such
     * a file was written before the mappings became data: nothing is guessed back from its words,
     * and the tester is told which key carries the mapping now.
     */
    static List<String> mappingNotes(String protocolDirectory, Scenario scenario) {
        String description = scenario.description() == null ? "" : scenario.description();
        if (CRD.equals(protocolDirectory)) {
            if (!scenario.answersDtrSentinels() && SPEAKS_OF_DTR_SENTINELS.matcher(description).find()) {
                return List.of(scenario.source() + ": its description speaks of DTR sentinel member IDs, and the file does not carry "
                        + "\"answersDtrSentinels\": true, so it answers none of them. The CRD scenario that answers every DTR sentinel now "
                        + "says so with that key; a directory copied before the key existed needs it added.");
            }
            return List.of();
        }
        if ((DTR.equals(protocolDirectory) || PAS.equals(protocolDirectory)) && scenario.crdSentinelMemberIds().isEmpty()
                && SPEAKS_OF_A_CRD_SENTINEL.matcher(description).find()) {
            return List.of(scenario.source() + ": its description speaks of a CRD sentinel member ID that selects it, and the file lists "
                    + "none under crdSentinelMemberIds, so no CRD sentinel selects it. A DTR or PAS scenario now names the CRD sentinels it "
                    + "answers for the same case under that key; a directory copied before the key existed needs it added.");
        }
        return List.of();
    }

    /** How the bundled files, before and after the mappings became data, speak of a CRD sentinel that selects them. */
    private static final Pattern SPEAKS_OF_A_CRD_SENTINEL = Pattern.compile("(?i)\\bCRD sentinels?(?: member IDs?)? P32H-");
    /** How the CRD file that answers DTR sentinels speaks of them. */
    private static final Pattern SPEAKS_OF_DTR_SENTINELS = Pattern.compile("(?i)\\bDTR sentinel");

    /** One protocol's files read on their own: what a run needs, and every note that needs no other protocol. */
    private Loaded loadOwn(String protocolDirectory) {
        List<Scenario> scenarios = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> files = readFiles(protocolDirectory, problems, notes);
        // Every note so far is about a file that is not read.
        List<String> notRead = List.copyOf(notes);
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
        // A CRD sentinel a file answers for the same case is claimed like its own: either way it selects that file.
        for (Scenario scenario : unique) {
            for (String sentinel : scenario.sentinelMemberIds()) {
                String key = memberKey(sentinel);
                String earlier = memberSentinels.putIfAbsent(key, scenario.source());
                if (earlier != null) {
                    problems.add(scenario.source() + ": the sentinel member ID " + key + " is already claimed by " + earlier
                            + ". One sentinel selects one scenario.");
                }
            }
            for (String sentinel : scenario.crdSentinelMemberIds()) {
                String key = memberKey(sentinel);
                String earlier = memberSentinels.putIfAbsent(key, scenario.source());
                if (earlier != null) {
                    problems.add(scenario.source() + ": the CRD sentinel member ID " + key + " in its crdSentinelMemberIds is already "
                            + "claimed by " + earlier + ". One sentinel selects one scenario.");
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
        problems.addAll(protocolRules(protocolDirectory, unique));
        List<Scenario> noted = new ArrayList<>();
        for (int i = 0; i < unique.size(); i++) {
            List<String> about = notesAbout(unique.get(i), i == 0);
            notes.addAll(about);
            noted.add(about.isEmpty() ? unique.get(i) : unique.get(i).withNotes(about));
        }
        return new Loaded(List.copyOf(noted), List.copyOf(problems), notes, notRead);
    }

    /**
     * The note that a file is the default. It only explains, so the summary carries it apart from
     * the notes that report something wrong with a file, and the screen can put it behind "About
     * this step" without telling notes apart by their words.
     */
    static String defaultNote(Scenario scenario) {
        return scenario.source() + ": it is the default, because its number (" + scenario.number() + ") is the lowest. A run that "
                + "neither a chosen scenario nor a sentinel selects is answered from it.";
    }

    /**
     * What can be said of one file from what is already loaded, without running it: that it is
     * the default, and that its expect block claims another HTTP status than the file answers
     * with. Whether the outcome it claims is the one it produces takes the protocol's own reading
     * of the response, which the scenario tests do for the bundled files.
     */
    static List<String> notesAbout(Scenario scenario, boolean first) {
        List<String> notes = new ArrayList<>();
        if (first) {
            notes.add(defaultNote(scenario));
        }
        ScenarioExpect expect = scenario.expect();
        ScenarioStep main = scenario.main();
        if (expect != null && expect.httpStatus() != null && main != null && main.httpStatus() != null
                && !expect.httpStatus().equals(main.httpStatus())) {
            notes.add(scenario.source() + ": its expect block claims HTTP " + expect.httpStatus() + ", and the file answers with HTTP "
                    + main.httpStatus() + ", so the outcome it claims may not be the one it produces.");
        }
        for (Map.Entry<String, ScenarioStep> step : scenario.steps().entrySet()) {
            ScenarioExpect claimed = step.getValue().expect();
            Integer status = step.getValue().httpStatus();
            if (claimed != null && claimed.httpStatus() != null && status != null && !claimed.httpStatus().equals(status)) {
                notes.add(scenario.source() + ": the expect block of its step \"" + step.getKey() + "\" claims HTTP " + claimed.httpStatus()
                        + ", and the step answers with HTTP " + status + ", so the outcome it claims may not be the one it produces.");
            }
        }
        return notes;
    }

    /**
     * What only some protocols may say: a CRD file answers no CRD sentinel of another file, only
     * a CRD file answers DTR sentinels, and only one does, or the order of the files would decide.
     */
    private static List<String> protocolRules(String protocolDirectory, List<Scenario> scenarios) {
        List<String> problems = new ArrayList<>();
        String answering = null;
        for (Scenario scenario : scenarios) {
            if (CRD.equals(protocolDirectory) && !scenario.crdSentinelMemberIds().isEmpty()) {
                problems.add(scenario.source() + ": a CRD scenario cannot list crdSentinelMemberIds. Give a CRD sentinel as the file's own "
                        + "sentinelMemberId.");
            }
            if (scenario.answersDtrSentinels()) {
                if (!CRD.equals(protocolDirectory)) {
                    problems.add(scenario.source() + ": only a CRD scenario can answer DTR sentinels, so answersDtrSentinels belongs in a "
                            + "CRD file.");
                } else if (answering != null) {
                    problems.add(scenario.source() + ": answersDtrSentinels is already true in " + answering + ". One CRD scenario answers "
                            + "every DTR sentinel.");
                } else {
                    answering = scenario.source();
                }
            }
        }
        return problems;
    }

    /**
     * The scenarios of a protocol, refused when any file of it has a problem. Every run comes
     * through here, so a set damaged while the tool runs is never answered from in part.
     *
     * @throws ScenarioSetUnusableException naming each file and its reason
     */
    private List<Scenario> usable(String protocolDirectory) {
        Loaded loaded = loadOwn(protocolDirectory);
        if (!loaded.problems().isEmpty()) {
            throw new ScenarioSetUnusableException(protocolDirectory, loaded.problems());
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
     * The one rule every mock chooses its scenario by, for a member ID typed on the form and for
     * one found in a pasted request body alike.
     *
     * <p>In order: a scenario chosen explicitly; then the form's member ID; then the form's NPI;
     * then, when nothing on the form selected a scenario, every text value of the request body,
     * read as the form is, a member ID first and then an NPI; then the first scenario in the list, which the result calls the default. A member ID is
     * looked for, in order, among the files' own sentinels, among the CRD sentinels a DTR or PAS
     * file answers for the same case ({@code crdSentinelMemberIds}), and in CRD among the
     * sentinels of the DTR files, which the CRD file marked {@code answersDtrSentinels} answers
     * so that a journey with one of them reaches DTR. A sentinel in a body is compared whole, as
     * the form's member ID is: no spaces, any letter case. A body that is not JSON holds no values,
     * and a sentinel inside a longer value is not one.
     *
     * @throws ValidationException when an explicitly chosen scenario id does not exist
     */
    public ScenarioSelection select(String protocolDirectory, MockSelection selection, String requestBody) {
        List<Scenario> scenarios = usable(protocolDirectory);
        if (selection != null && Texts.present(selection.scenarioId())) {
            String wanted = selection.scenarioId().strip();
            return scenarios.stream().filter(s -> s.id().equals(wanted)).findFirst()
                    .map(s -> new ScenarioSelection(s, CHOSEN_BY_ID, ScenarioSelection.By.CHOSEN, false,
                            CRD.equals(protocolDirectory) && s.answersDtrSentinels() ? dtrScenarioOf(selection, requestBody) : null))
                    .orElseThrow(() -> ValidationException.of("scenarioId", "There is no scenario with id \"" + wanted
                            + "\". Choose one from the Mock scenario list, or leave it empty to let the member ID choose."));
        }
        if (selection != null && Texts.present(selection.memberId())) {
            ScenarioSelection byMember = byMemberId(protocolDirectory, scenarios, Set.of(memberKey(selection.memberId())), false);
            if (byMember != null) {
                return byMember;
            }
        }
        if (selection != null && Texts.present(selection.npi())) {
            ScenarioSelection byNpi = byNpi(scenarios, Set.of(selection.npi().strip()), false);
            if (byNpi != null) {
                return byNpi;
            }
        }
        if (Texts.present(requestBody)) {
            Set<String> memberKeys = new HashSet<>();
            Set<String> wholeValues = new HashSet<>();
            JsonSafe.Parsed parsed = JsonSafe.parse(requestBody);
            if (parsed.ok()) {
                collectTexts(parsed.node(), memberKeys, wholeValues);
            }
            // The body is read in the order the form is: a member ID first, then an NPI.
            ScenarioSelection byBody = memberKeys.isEmpty() ? null : byMemberId(protocolDirectory, scenarios, memberKeys, true);
            if (byBody == null && !wholeValues.isEmpty()) {
                byBody = byNpi(scenarios, wholeValues, true);
            }
            if (byBody != null) {
                return byBody;
            }
        }
        return new ScenarioSelection(scenarios.get(0), "the default, because no scenario was chosen and no test member ID or NPI matched",
                ScenarioSelection.By.DEFAULT, false, null);
    }

    /**
     * The DTR scenario whose own sentinel is the member ID on the form or, when that is none, one
     * found in the body, or null. Used when the CRD file that answers DTR sentinels was chosen in
     * the selector, so that it names what that DTR scenario holds however it came to answer. A DTR
     * set that cannot be used is not read here: a run chosen by hand must not fail on files of
     * another protocol, and the DTR list names their problems.
     */
    private Scenario dtrScenarioOf(MockSelection selection, String requestBody) {
        Loaded dtr = loadOwn(DTR);
        if (!dtr.problems().isEmpty()) {
            return null;
        }
        List<Set<String>> candidates = new ArrayList<>();
        if (Texts.present(selection.memberId())) {
            candidates.add(Set.of(memberKey(selection.memberId())));
        }
        JsonSafe.Parsed parsed = Texts.present(requestBody) ? JsonSafe.parse(requestBody) : null;
        if (parsed != null && parsed.ok()) {
            Set<String> values = new HashSet<>();
            collectTexts(parsed.node(), values, new HashSet<>());
            candidates.add(values);
        }
        for (Set<String> candidate : candidates) {
            for (Scenario scenario : dtr.scenarios()) {
                for (String sentinel : scenario.sentinelMemberIds()) {
                    if (candidate.contains(memberKey(sentinel))) {
                        return scenario;
                    }
                }
            }
        }
        return null;
    }

    /** The scenario one of these member IDs selects by the member ID part of {@link #select}, or null. */
    private ScenarioSelection byMemberId(String protocolDirectory, List<Scenario> scenarios, Set<String> keys, boolean inBody) {
        String where = inBody ? " found in the request body" : "";
        for (Scenario scenario : scenarios) {
            for (String sentinel : scenario.sentinelMemberIds()) {
                if (keys.contains(memberKey(sentinel))) {
                    return new ScenarioSelection(scenario, "selected by the test member ID " + sentinel + where,
                            ScenarioSelection.By.SENTINEL, inBody, null);
                }
            }
        }
        for (Scenario scenario : scenarios) {
            for (String sentinel : scenario.crdSentinelMemberIds()) {
                if (keys.contains(memberKey(sentinel))) {
                    return new ScenarioSelection(scenario, "selected by the CRD test member ID " + sentinel + where
                            + ", which this scenario answers for the same case", ScenarioSelection.By.CRD_SENTINEL, inBody, null);
                }
            }
        }
        Scenario answering = scenarios.stream().filter(Scenario::answersDtrSentinels).findFirst().orElse(null);
        if (answering == null) {
            return null;
        }
        // Read only when a CRD file answers DTR sentinels, so the DTR files are what decides, and a DTR scenario added later is answered too.
        for (Scenario dtr : usable(DTR)) {
            for (String sentinel : dtr.sentinelMemberIds()) {
                if (keys.contains(memberKey(sentinel))) {
                    return new ScenarioSelection(answering, "selected because " + sentinel + (inBody ? ", found in the request body," : "")
                            + " is the test member ID of DTR scenario " + dtr.id() + ", and CRD answers documentation required for "
                            + "every DTR test member ID so that the journey reaches DTR", ScenarioSelection.By.DTR_SENTINEL, inBody, dtr);
                }
            }
        }
        return null;
    }

    /** The scenario one of these NPIs selects by the NPI part of {@link #select}, or null. An NPI is compared as typed, spaces at its ends aside. */
    private static ScenarioSelection byNpi(List<Scenario> scenarios, Set<String> npis, boolean inBody) {
        for (Scenario scenario : scenarios) {
            for (String sentinel : scenario.sentinelNpis()) {
                if (npis.contains(sentinel.strip())) {
                    return new ScenarioSelection(scenario, "selected by the test NPI " + sentinel + (inBody ? " found in the request body" : ""),
                            ScenarioSelection.By.SENTINEL_NPI, inBody, null);
                }
            }
        }
        return null;
    }

    /**
     * Every text value of a body, once in the form a member ID is compared in and once as the NPI
     * on the form is. A whole number is kept for the NPI as well: an identifier written without
     * quotes is still the value the body carries.
     */
    private static void collectTexts(JsonNode node, Set<String> memberKeys, Set<String> wholeValues) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            memberKeys.add(memberKey(node.stringValue()));
            wholeValues.add(node.stringValue().strip());
            return;
        }
        if (node.isIntegralNumber()) {
            wholeValues.add(node.asString());
            return;
        }
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(node)) {
            collectTexts(property.getValue(), memberKeys, wholeValues);
        }
        for (JsonNode element : JsonSafe.elements(node)) {
            collectTexts(element, memberKeys, wholeValues);
        }
    }

    private Map<String, String> readFiles(String protocolDirectory, List<String> problems, List<String> notes) {
        Map<String, String> files = new java.util.TreeMap<>();
        if (Texts.present(app.scenarioDir())) {
            Path directory = Path.of(app.scenarioDir().strip()).resolve(protocolDirectory);
            if (!Files.isDirectory(directory)) {
                problems.add("The scenario directory " + directory + " does not exist.");
                return files;
            }
            try (Stream<Path> stream = Files.list(directory)) {
                List<Path> listed = stream.sorted().toList();
                for (Path path : listed) {
                    // Named so that a file saved with another extension or in a folder is not taken for a loaded one.
                    String name = path.getFileName().toString();
                    if (Files.isDirectory(path)) {
                        notes.add(name + ": it is a folder, and folders inside " + protocolDirectory + " are not read. A scenario file "
                                + "belongs directly in " + protocolDirectory + ".");
                    } else if (!name.endsWith(".json")) {
                        notes.add(name + ": it is not read, because only files whose names end in .json are scenario files.");
                    }
                }
                for (Path path : listed.stream().filter(p -> !Files.isDirectory(p) && p.getFileName().toString().endsWith(".json")).toList()) {
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
            throw new IllegalArgumentException(unknownKeys(unknown));
        }
        List<String> memberSentinels = sentinels(root, "sentinelMemberId", "sentinelMemberIds", "sentinel member ID");
        List<String> npiSentinels = sentinels(root, "sentinelNpi", "sentinelNpis", "sentinel NPI");
        List<String> crdSentinels = sentinels(root, null, "crdSentinelMemberIds", "CRD sentinel member ID");
        for (String sentinel : crdSentinels) {
            if (memberSentinels.stream().anyMatch(own -> memberKey(own).equals(memberKey(sentinel)))) {
                throw new IllegalArgumentException("it names one sentinel member ID both as its own and among its crdSentinelMemberIds.");
            }
        }
        JsonNode dtrFlag = JsonSafe.child(root, "answersDtrSentinels");
        if (!JsonSafe.nullOrMissing(dtrFlag) && !dtrFlag.isBoolean()) {
            throw new IllegalArgumentException("its answersDtrSentinels is not true or false.");
        }
        boolean answersDtrSentinels = dtrFlag.isBoolean() && dtrFlag.booleanValue();
        for (String sentinel : java.util.stream.Stream.concat(memberSentinels.stream(), crdSentinels.stream()).toList()) {
            if (!SENTINEL.matcher(memberKey(sentinel)).matches()) {
                // The value is not quoted: something that is not a sentinel could be a real member ID.
                throw new IllegalArgumentException("one of its sentinel member IDs is not a P32H- sentinel. A scenario is selected only by "
                        + "this tool's own identifiers, so nothing that could be a real member ID is written in a scenario file.");
            }
        }
        for (String sentinel : npiSentinels) {
            if (!Npi.isValid(sentinel.strip())) {
                throw new IllegalArgumentException("one of its sentinel NPIs does not pass the NPI check, so no request could ever carry it. "
                        + "Use ten digits whose last digit is the NPI check digit of the first nine, such as 9999999995.");
            }
        }
        sampleCase(root);
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
                JsonSafe.presentText(root, "assumption"), root, source, memberSentinels, npiSentinels, crdSentinels, answersDtrSentinels);
    }

    /**
     * The refusal of keys the loader does not know. A key is quoted only when it looks like a key
     * name of this tool, letters alone and not long: those are the misspellings the refusal is for.
     * Anything else, such as an ID pasted where a key belongs, could be data, so it is counted and
     * not quoted, because the refusal is logged at start-up and shown on screen.
     */
    static String unknownKeys(List<String> unknown) {
        List<String> named = unknown.stream().filter(key -> KEY_NAME.matcher(key).matches()).toList();
        int unnamed = unknown.size() - named.size();
        if (unnamed == 0) {
            return "it has " + (named.size() == 1 ? "a key" : "keys") + " this tool does not know: " + String.join(", ", named) + ". Check "
                    + (named.size() == 1 ? "its" : "their") + " spelling.";
        }
        String others = (unnamed == 1 ? "a key" : unnamed + " keys") + " that " + (unnamed == 1 ? "is" : "are")
                + " not a scenario key and " + (unnamed == 1 ? "does" : "do") + " not look like one, so " + (unnamed == 1 ? "it is" : "they are")
                + " not quoted here";
        if (named.isEmpty()) {
            return "it has " + others + ". Check the file's top-level keys.";
        }
        return "it has " + (named.size() == 1 ? "a key" : "keys") + " this tool does not know: " + String.join(", ", named) + ", and "
                + others + ". Check the file's top-level keys.";
    }

    /**
     * The file's sample case, or null when it names none. A sample whose NPI fails the check, or
     * that holds a blank code, is refused: one click would fill a case the tool itself refuses to
     * send. Like a sentinel NPI, the value is not quoted.
     */
    static SampleCase sampleCase(JsonNode root) {
        JsonNode sample = JsonSafe.child(root, "sampleCase");
        if (JsonSafe.nullOrMissing(sample)) {
            return null;
        }
        if (!sample.isObject()) {
            throw new IllegalArgumentException("its sampleCase is not an object with an npi and procedureCodes.");
        }
        JsonNode npi = JsonSafe.child(sample, "npi");
        if (npi == null || !npi.isString() || !Npi.isValid(npi.stringValue().strip())) {
            throw new IllegalArgumentException("the NPI of its sampleCase does not pass the NPI check, so the case it fills could not be sent. "
                    + "Use ten digits whose last digit is the NPI check digit of the first nine, such as 1234567893.");
        }
        JsonNode codes = JsonSafe.child(sample, "procedureCodes");
        if (codes == null || !codes.isArray() || codes.isEmpty()) {
            throw new IllegalArgumentException("its sampleCase has no list of procedureCodes.");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode code : JsonSafe.elements(codes)) {
            String value = code.isString() ? JsonSafe.presentText(code) : null;
            if (value == null) {
                throw new IllegalArgumentException("the procedureCodes of its sampleCase hold a blank entry or one that is not text.");
            }
            out.add(value);
        }
        return new SampleCase(npi.stringValue().strip(), out);
    }

    /** What a key name of this tool looks like: letters only, as every key in {@link #KNOWN_KEYS} is. */
    private static final Pattern KEY_NAME = Pattern.compile("[A-Za-z]{1,40}");

    /**
     * The sentinels a file names under the single key and the list key, single first. A key that is
     * present must hold what it says: one text, or a list of texts. A blank single key means none,
     * as it always has.
     */
    private static List<String> sentinels(JsonNode root, String singleKey, String listKey, String what) {
        List<String> out = new ArrayList<>();
        JsonNode single = singleKey == null ? null : JsonSafe.child(root, singleKey);
        if (singleKey != null && !JsonSafe.nullOrMissing(single)) {
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
            String key = "sentinelNpi".equals(singleKey) ? value : memberKey(value);
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
