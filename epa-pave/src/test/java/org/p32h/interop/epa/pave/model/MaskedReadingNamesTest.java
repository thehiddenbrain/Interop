package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The names a masked reading is looked for under are the names the readings really go by, found
 * in their code. Every call in a reading that reads a value or finds its way by a name written in
 * it (child, children, elements, text, presentText, integer, bool, presence, has, get and the
 * like) names one of {@link MaskedReading#VALUES} or {@link MaskedReading#NAMES}. A name that is
 * only put on screen through shown() is not among them: the mask in the field is its own notice.
 * A reading that starts to go by a new name fails this test until the name is placed.
 */
class MaskedReadingNamesTest {

    private static final Path SOURCES = Path.of("src/main/java/org/p32h/interop/epa/pave");

    /** Every class that reads the answer of CRD, CRD discovery, DTR, PAS, a subscription call or a notification. */
    private static final List<String> READINGS = List.of("service/crd/CrdResponseMapper.java", "service/crd/CrdService.java",
            "service/crd/CrdConformanceChecker.java", "service/crd/CrdGuideRules.java", "client/crd/SentOrder.java",
            "client/crd/OnyxCrdConventions.java", "service/dtr/DtrResponseMapper.java", "service/dtr/DtrResponseShape.java",
            "service/dtr/DtrProfileCheck.java", "service/dtr/DtrFindings.java", "service/dtr/DtrService.java",
            "service/dtr/EnableWhenEvaluator.java", "service/dtr/DtrAnswers.java", "model/dtr/QuestionnaireJson.java",
            "client/dtr/OnyxDtrConventions.java", "service/pas/PasResponseMapper.java", "service/pas/PasFindings.java",
            "service/pas/PasResources.java", "client/pas/OnyxPasConventions.java", "service/pas/NotificationReader.java",
            "service/pas/SubscriptionResponseMapper.java", "model/FhirViews.java", "model/OperationOutcomes.java");

    private static final Pattern READ = Pattern.compile("\\b(child|children|elements|at|text|presentText|integer|longValue|bool|presence"
            + "|presences|has|get|path|valueKey)\\(\\s*(?:[\\w.()\\[\\]]+\\s*,\\s*)?\"([A-Za-z_][A-Za-z0-9_\\-]*)\"");

    @Test
    void everyNameAReadingGoesByIsAmongTheNamesAMaskedReadingIsLookedForUnder() throws IOException {
        Set<String> unplaced = new TreeSet<>();
        int found = 0;
        for (String file : READINGS) {
            Matcher matcher = READ.matcher(Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8));
            while (matcher.find()) {
                found++;
                if (!MaskedReading.findsWayBy(matcher.group(2))) {
                    unplaced.add(matcher.group(2) + " (" + file + ")");
                }
            }
        }
        assertThat(found).as("names read in the readings").isGreaterThan(200);
        assertThat(unplaced).as("names a reading goes by that a masked reading is not looked for under").isEmpty();
    }

    @Test
    void theValuesShownOnScreenAndDecidedByAreAmongTheValues() {
        // Read through shown() and then decided by: an outcome of error counts as errors reported, an issue of severity error is
        // an error, and the disposition, the indicator and the use are what the PAS and CRD verdicts and findings speak of.
        for (String name : List.of("outcome", "severity", "disposition", "indicator", "use", "status", "resourceType", "type")) {
            assertThat(MaskedReading.readsValueOf(name)).as(name).isTrue();
        }
        // A FHIR choice element is read by its kind of value.
        for (String name : List.of("valueCode", "valueBoolean", "valueCoding", "answerBoolean", "initialString")) {
            assertThat(MaskedReading.readsValueOf(name)).as(name).isTrue();
        }
        // Shown and nothing more: a mask in such a field is its own notice.
        for (String name : List.of("summary", "detail", "preAuthRef", "created", "timestamp", "description", "label", "values")) {
            assertThat(MaskedReading.findsWayBy(name)).as(name).isFalse();
        }
    }

    @Test
    void aMaskInAListOfPlainValuesUnderANameReadByIsFoundAsWellAsAMaskedName() {
        SentSecrets secrets = SentSecrets.builder().variable("profileWord", "https://example.org/p32h-placeholder/profile")
                .variable("resourceWord", "resource").build();
        JsonNode json = JsonSafe.parse("{\"meta\":{\"profile\":[\"https://example.org/p32h-placeholder/profile\"]},"
                + "\"resource\":{\"summary\":\"resource\"}}").node();
        MaskedReading reading = MaskedReading.of(json, secrets);
        assertThat(reading.readings()).containsExactly("the name of a value this tool reads by was read as [secret resourceWord]",
                "profile was read as [secret profileWord]");
        // The same word where nothing is read by it: a summary is only shown.
        assertThat(MaskedReading.of(JsonSafe.parse("{\"summary\":\"resource\"}").node(), secrets)).isNull();
    }
}
