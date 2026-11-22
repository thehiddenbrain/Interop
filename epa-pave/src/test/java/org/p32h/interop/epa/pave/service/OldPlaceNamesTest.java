package org.p32h.interop.epa.pave.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The screens were rebuilt, and the tabs called Config, CRD, DTR, PAS and History are gone. A
 * sentence the Workbench shows that still sends a tester to one of them sends them nowhere (I-1).
 * This reads the string literals, not the comments, of the classes whose sentences reach the
 * Workbench. The production guardrail's sentences are left as they are on purpose (I-30), and they
 * live in other classes, so none of them is read here.
 */
class OldPlaceNamesTest {

    private static final String MAIN = "src/main/java/org/p32h/interop/epa/pave/";
    private static final List<String> CLASSES = List.of(
            "service/crd/CrdVerdicts.java", "service/crd/CrdRequestGlossary.java",
            "service/dtr/DtrVerdicts.java", "service/dtr/DtrService.java",
            "service/pas/PasVerdicts.java", "service/pas/SubscriptionVerdicts.java", "service/pas/NotificationVerdicts.java",
            "service/pas/NotificationReceiver.java",
            "service/cases/CaseFaults.java", "service/cases/ChainCaseStep.java", "service/cases/ProtocolCaseRunner.java",
            "service/raw/RawService.java", "service/suite/SuiteService.java", "service/ledger/RunReplay.java",
            "model/MaskedReading.java", "model/CarriedMasks.java", "model/NextAction.java", "model/NextActionOption.java",
            "web/ApiExceptionHandler.java", "service/ledger/RunIds.java", "service/run/RunSupport.java");
    private static final Pattern OLD = Pattern.compile("(Config|CRD|DTR|PAS|History) tab");
    /** A string literal on one line; the escapes inside it are kept as written. */
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*+\"");

    @Test
    void noSentenceTheWorkbenchShowsNamesATabThatIsGone() throws IOException {
        List<String> found = new ArrayList<>();
        for (String name : CLASSES) {
            List<String> lines = Files.readAllLines(Path.of(MAIN + name), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
                    continue;
                }
                Matcher literal = LITERAL.matcher(line);
                while (literal.find()) {
                    if (OLD.matcher(literal.group()).find()) {
                        found.add(name + ":" + (i + 1) + " " + literal.group());
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }

    /**
     * A send with no address names the place to type one, not a property alone: the property was
     * the whole sentence before, and a tester cannot set it from a screen (I-15). The guardrail's
     * sentences in the same class are not read: none of them says "is not set".
     */
    @Test
    void aSendWithNoAddressNamesThePlaceToTypeOneAndNotOnlyAProperty() throws IOException {
        List<String> found = new ArrayList<>();
        for (String name : List.of("service/config/ConfigStoreGateway.java", "service/run/RunSupport.java")) {
            List<String> lines = Files.readAllLines(Path.of(MAIN + name), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
                    continue;
                }
                Matcher literal = LITERAL.matcher(line);
                while (literal.find()) {
                    if (PROPERTY_ALONE.matcher(literal.group()).find()) {
                        found.add(name + ":" + (i + 1) + " " + literal.group());
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }

    private static final Pattern PROPERTY_ALONE = Pattern.compile("is not set|No collection request is mapped to|token endpoint \\(onyx");

    @Test
    void theCheckFindsAnOldPlaceNameInALiteralAndNotInAComment() {
        Matcher literal = LITERAL.matcher("return \"Next: check the PAS url on the Config tab.\"; // on the PAS tab");
        assertThat(literal.find()).isTrue();
        assertThat(OLD.matcher(literal.group()).find()).isTrue();
        assertThat(literal.find()).isFalse();
    }
}
