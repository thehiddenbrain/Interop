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
 * through shown() is not among them: the mask in the field is its own notice. A reading that
 * starts to go by a new name fails this test until the name is placed.
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

    @Test
    void theValuesShownAndThenJudgedAreAmongTheNamesAndTheValuesOnlyShownAreNot() {
        // Put on screen through shown() and then judged.
        assertThat(MaskedReading.Reading.PAS.goesBy("outcome")).as("an error outcome counts as errors reported").isTrue();
        assertThat(MaskedReading.Reading.SUBSCRIPTION.goesBy("severity")).as("an error issue is a failed write").isTrue();
        assertThat(MaskedReading.Reading.SUBSCRIPTION.goesBy("status")).as("held against its value set").isTrue();
        assertThat(MaskedReading.Reading.DTR.goesBy("status")).as("a completed response ends the loop").isTrue();
        assertThat(MaskedReading.Reading.CRD.goesBy("type")).as("a SMART link is offered as the next step").isTrue();
        assertThat(MaskedReading.Reading.NOTIFICATION.goesBy("type")).as("a heartbeat is not an event").isTrue();
        // A FHIR choice element is read by its kind of value.
        for (String name : List.of("valueCode", "valueBoolean", "valueCoding", "answerBoolean", "initialString")) {
            assertThat(MaskedReading.Reading.DTR.goesBy(name)).as(name).isTrue();
        }
        // Only shown: a mask in such a field is its own notice. The PAS decision is read from review action codes, never from
        // the disposition, and a card's indicator decides nothing.
        assertThat(MaskedReading.Reading.PAS.goesBy("disposition")).isFalse();
        assertThat(MaskedReading.Reading.PAS.goesBy("preAuthRef")).isFalse();
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
