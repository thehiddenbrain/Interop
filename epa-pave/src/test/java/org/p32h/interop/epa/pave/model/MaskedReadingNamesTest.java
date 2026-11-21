package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The names a masked reading is looked for under are the names each reading really goes by,
 * found in its code. Every call in a reading that reads a value or finds its way by a name written
 * in it (child, children, elements, text, presentText, integer, bool, presence, has, get and the
 * like) names one its {@link MaskedReading.Reading} goes by. A name that is only put on screen
 * through shown() is not found that way, so every name a reading puts on screen through shown()
 * is listed here by hand: either as one whose value is then compared, carried to a next step or
 * held against a value set, with where, or as one that is only shown, where the mask in the field
 * is its own notice. A reading that starts to go by a new name, or to show one, fails this test
 * until the name is placed.
 */
class MaskedReadingNamesTest {

    private static final Path SOURCES = Path.of("src/main/java/org/p32h/interop/epa/pave");

    /** What every reading of FHIR resources reads its codes and issues with. */
    private static final List<String> SHARED = List.of("model/FhirViews.java", "model/OperationOutcomes.java");

    /** The classes each reading is made of. */
    private static final Map<MaskedReading.Reading, List<String>> READINGS = Map.of(
            MaskedReading.Reading.CRD, List.of("service/crd/CrdResponseMapper.java", "service/crd/CrdConformanceChecker.java",
                    "service/crd/CrdGuideRules.java", "client/crd/SentOrder.java"),
            MaskedReading.Reading.DISCOVERY, List.of("service/crd/CrdService.java"),
            MaskedReading.Reading.DTR, List.of("service/dtr/DtrResponseMapper.java", "service/dtr/DtrResponseShape.java",
                    "service/dtr/DtrProfileCheck.java", "service/dtr/DtrFindings.java", "service/dtr/DtrService.java",
                    "service/dtr/EnableWhenEvaluator.java", "service/dtr/DtrAnswers.java", "model/dtr/QuestionnaireJson.java"),
            MaskedReading.Reading.PAS, List.of("service/pas/PasResponseMapper.java", "service/pas/PasFindings.java",
                    "service/pas/PasResources.java"),
            MaskedReading.Reading.SUBSCRIPTION, List.of("service/pas/SubscriptionResponseMapper.java"),
            // A notification's ClaimResponses are read by the PAS mapper.
            MaskedReading.Reading.NOTIFICATION, List.of("service/pas/NotificationReader.java", "service/pas/PasResponseMapper.java",
                    "service/pas/PasFindings.java"));

    private static final Pattern READ = Pattern.compile("\\b(child|children|elements|at|text|presentText|integer|longValue|bool|presence"
            + "|presences|has|get|path|valueKey)\\(\\s*(?:[\\w.()\\[\\]]+\\s*,\\s*)?\"([A-Za-z_][A-Za-z0-9_\\-]*)\"");

    @Test
    void everyNameAReadingGoesByIsAmongTheNamesItsMaskedReadingIsLookedForUnder() throws IOException {
        assertThat(READINGS.keySet()).containsExactlyInAnyOrder(MaskedReading.Reading.values());
        Set<String> unplaced = new TreeSet<>();
        int found = 0;
        for (Map.Entry<MaskedReading.Reading, List<String>> reading : READINGS.entrySet()) {
            List<String> files = new java.util.ArrayList<>(reading.getValue());
            if (reading.getKey() != MaskedReading.Reading.DISCOVERY) {
                files.addAll(SHARED);
            }
            for (String file : files) {
                Matcher matcher = READ.matcher(Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8));
                while (matcher.find()) {
                    found++;
                    if (!reading.getKey().goesBy(matcher.group(2))) {
                        unplaced.add(reading.getKey() + ": " + matcher.group(2) + " (" + file + ")");
                    }
                }
            }
        }
        assertThat(found).as("names read in the readings").isGreaterThan(200);
        assertThat(unplaced).as("names a reading goes by that its masked reading is not looked for under").isEmpty();
    }

    private static final Pattern SHOWN = Pattern.compile("\\bshown\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

    /** Names put on screen through shown() and then compared, carried or held against a value set, with where. */
    private static final Map<MaskedReading.Reading, Map<String, String>> JUDGED = Map.of(
            MaskedReading.Reading.CRD, Map.of(
                    "type", "CrdVerdicts offers a link whose type is smart as the DTR launch",
                    "appContext", "CrdVerdicts carries that link's appContext into the DTR launch"),
            MaskedReading.Reading.DISCOVERY, Map.of(),
            MaskedReading.Reading.DTR, Map.of(
                    "status", "a completed response ends the loop, and a status is held against its value set",
                    "readOnly", "dtr-items.js says a question is read only when the value is true",
                    "answerValueSet", "dtr-items.js offers answers from the value set it names; the rule for answer[x] covers it too"),
            MaskedReading.Reading.PAS, Map.of(
                    "outcome", "an error outcome counts as errors reported",
                    "preAuthRef", "PasVerdicts holds it against its items' numbers, and PasService keeps it to match a notification",
                    "start", "PasVerdicts holds the start of preAuthPeriod against its items' periods",
                    "end", "PasVerdicts holds the end of preAuthPeriod against its items' periods"),
            MaskedReading.Reading.SUBSCRIPTION, Map.of(
                    "id", "SubscriptionResponseMapper holds it against the id asked about, and a created one is carried to the next call",
                    "endpoint", "SubscriptionVerdicts holds it against the address the request named",
                    "severity", "an error or fatal issue is a failed write",
                    "status", "held against its value set",
                    "type", "the channel type is held against its value set"),
            MaskedReading.Reading.NOTIFICATION, Map.of(
                    "outcome", "read as PAS reads it",
                    "preAuthRef", "NotificationReceiver matches a notification to its submission by the authorization number",
                    "start", "read as PAS reads it",
                    "end", "read as PAS reads it"));

    /** Names put on screen through shown() and judged by nothing: the mask in the field is its own notice. */
    private static final Map<MaskedReading.Reading, Set<String>> ONLY_SHOWN = Map.of(
            MaskedReading.Reading.CRD, Set.of("description", "detail", "diagnostics", "icon", "indicator", "isRecommended", "label",
                    "resourceId", "selectionBehavior", "severity", "summary", "uuid"),
            MaskedReading.Reading.DISCOVERY, Set.of("description", "hook", "id", "title"),
            MaskedReading.Reading.DTR, Set.of("authored", "contentType", "date", "description", "diagnostics", "div",
                    "prefix", "publisher", "severity"),
            MaskedReading.Reading.PAS, Set.of("created", "detailSequence", "diagnostics", "disposition", "number", "severity",
                    "subDetailSequence", "timestamp", "use"),
            MaskedReading.Reading.SUBSCRIPTION, Set.of("criteria", "diagnostics", "end", "error", "fullUrl", "mode", "payload", "reason",
                    "total"),
            MaskedReading.Reading.NOTIFICATION, Set.of("created", "detailSequence", "diagnostics", "disposition", "eventNumber",
                    "eventsSinceSubscriptionStart", "number", "severity", "subDetailSequence", "timestamp", "topic", "use"));

    @Test
    void everyNameAReadingShowsIsPlacedAsJudgedWithItsReasonOrAsOnlyShown() throws IOException {
        assertThat(JUDGED.keySet()).containsExactlyInAnyOrder(MaskedReading.Reading.values());
        assertThat(ONLY_SHOWN.keySet()).containsExactlyInAnyOrder(MaskedReading.Reading.values());
        Set<String> wrong = new TreeSet<>();
        for (Map.Entry<MaskedReading.Reading, List<String>> reading : READINGS.entrySet()) {
            MaskedReading.Reading which = reading.getKey();
            List<String> files = new java.util.ArrayList<>(reading.getValue());
            if (which != MaskedReading.Reading.DISCOVERY) {
                files.addAll(SHARED);
            }
            Set<String> read = new TreeSet<>();
            Set<String> shown = new TreeSet<>();
            for (String file : files) {
                String source = Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8);
                Matcher reads = READ.matcher(source);
                while (reads.find()) {
                    read.add(reads.group(2));
                }
                Matcher shows = SHOWN.matcher(source);
                while (shows.find()) {
                    shown.add(shows.group(1));
                }
            }
            for (String name : shown) {
                if (!read.contains(name) && !JUDGED.get(which).containsKey(name) && !ONLY_SHOWN.get(which).contains(name)) {
                    wrong.add(which + ": " + name + " is shown and not placed as judged or as only shown");
                }
            }
            JUDGED.get(which).forEach((name, why) -> {
                if (!which.goesBy(name)) {
                    wrong.add(which + ": " + name + " is judged (" + why + ") and its masked reading is not looked for under it");
                }
                if (!shown.contains(name)) {
                    wrong.add(which + ": " + name + " is listed as judged and the reading no longer shows it");
                }
            });
            for (String name : ONLY_SHOWN.get(which)) {
                if (which.goesBy(name)) {
                    wrong.add(which + ": " + name + " is listed as only shown and the reading goes by it");
                }
                if (!shown.contains(name)) {
                    wrong.add(which + ": " + name + " is listed as only shown and the reading no longer shows it");
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void theValuesShownAndThenJudgedAreAmongTheNamesAndTheValuesOnlyShownAreNot() {
        // Put on screen through shown() and then judged.
        assertThat(MaskedReading.Reading.PAS.goesBy("outcome")).as("an error outcome counts as errors reported").isTrue();
        assertThat(MaskedReading.Reading.SUBSCRIPTION.goesBy("severity")).as("an error issue is a failed write").isTrue();
        assertThat(MaskedReading.Reading.SUBSCRIPTION.goesBy("status")).as("held against its value set").isTrue();
        assertThat(MaskedReading.Reading.DTR.goesBy("status")).as("a completed response ends the loop").isTrue();
        assertThat(MaskedReading.Reading.CRD.goesBy("type")).as("a SMART link is offered as the next step").isTrue();
        assertThat(MaskedReading.Reading.CRD.goesBy("appContext")).as("carried into the DTR launch").isTrue();
        assertThat(MaskedReading.Reading.SUBSCRIPTION.goesBy("id")).as("held against the id asked, and carried").isTrue();
        assertThat(MaskedReading.Reading.SUBSCRIPTION.goesBy("endpoint")).as("held against the address sent").isTrue();
        assertThat(MaskedReading.Reading.PAS.goesBy("preAuthRef")).as("held against the items' numbers, and kept").isTrue();
        assertThat(MaskedReading.Reading.NOTIFICATION.goesBy("type")).as("a heartbeat is not an event").isTrue();
        // A FHIR choice element is read by its kind of value.
        for (String name : List.of("valueCode", "valueBoolean", "valueCoding", "answerBoolean", "initialString")) {
            assertThat(MaskedReading.Reading.DTR.goesBy(name)).as(name).isTrue();
        }
        // Only shown: a mask in such a field is its own notice. The PAS decision is read from review action codes, never from
        // the disposition, and a card's indicator decides nothing.
        assertThat(MaskedReading.Reading.PAS.goesBy("disposition")).isFalse();
        assertThat(MaskedReading.Reading.CRD.goesBy("indicator")).isFalse();
        assertThat(MaskedReading.Reading.CRD.goesBy("status")).as("the status of the order a system action updates").isFalse();
        assertThat(MaskedReading.Reading.DISCOVERY.goesBy("id")).isFalse();
        assertThat(MaskedReading.Reading.DISCOVERY.goesBy("valueCode")).isFalse();
    }

    @Test
    void aMaskInAListOfPlainValuesUnderANameReadByIsFoundAsWellAsAMaskedName() {
        SentSecrets secrets = SentSecrets.builder().variable("profileWord", "https://example.org/p32h-placeholder/profile")
                .variable("resourceWord", "resource").build();
        JsonNode json = JsonSafe.parse("{\"meta\":{\"profile\":[\"https://example.org/p32h-placeholder/profile\"]},"
                + "\"resource\":{\"summary\":\"resource\"}}").node();
        MaskedReading reading = MaskedReading.of(json, secrets, MaskedReading.Reading.DTR);
        assertThat(reading.readings()).containsExactly("the name of a value this tool reads by was read as [secret resourceWord]",
                "profile was read as [secret profileWord]");
        // The same word where nothing is read by it: a summary is only shown.
        assertThat(MaskedReading.of(JsonSafe.parse("{\"summary\":\"resource\"}").node(), secrets, MaskedReading.Reading.DTR)).isNull();
    }
}
