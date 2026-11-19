package org.p32h.interop.epa.pave.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.suite.SuiteCaseResult;
import org.p32h.interop.epa.pave.model.suite.SuitePreview;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;
import org.p32h.interop.epa.pave.service.suite.SuiteFileReader;
import org.p32h.interop.epa.pave.service.suite.SuiteTestBed;

/**
 * The sample suite parses with every case runnable, and on mock each case ends the way its own
 * purpose column says: four pass, one fails on purpose and one has no expectation. It runs through
 * the real case runner, the real CRD service and the bundled scenario files.
 */
class SampleSuiteTest {

    static final Path SAMPLE = Path.of("samples/suites/sample-suite.csv");

    private static String text() throws Exception {
        return SuiteFileReader.decode(Files.readAllBytes(SAMPLE));
    }

    @Test
    void theFileParsesAndEveryCaseCanBeRun() throws Exception {
        SuitePreview preview = new SuiteTestBed().service().preview(SAMPLE.getFileName().toString(), text());

        assertThat(preview.problems()).isEmpty();
        assertThat(preview.rowProblems()).isEmpty();
        assertThat(preview.runnable()).isTrue();
        assertThat(preview.total()).isEqualTo(6);
        assertThat(preview.valid()).isEqualTo(6);
        assertThat(preview.withExpectation()).isEqualTo(5);
        // The purpose column starts with # and is the tester's own, so the tool never reads it.
        assertThat(preview.columns()).contains("#purpose");
    }

    @Test
    void onMockEachCaseEndsAsItsPurposeColumnSays() throws Exception {
        String text = new String(Files.readAllBytes(SAMPLE), StandardCharsets.UTF_8);

        SuiteRunView run = new SuiteTestBed().service().start(SAMPLE.getFileName().toString(), text, null, null, null);

        assertThat(run.results()).extracting(SuiteCaseResult::caseId)
                .containsExactly("sample-01", "sample-02", "sample-03", "sample-04", "sample-05", "sample-06");
        assertThat(run.results()).extracting(SuiteCaseResult::outcome)
                .containsExactly("AUTH_REQUIRED", "NO_AUTH_REQUIRED", "NO_DETERMINATION", "SERVICE_ERROR", "NO_AUTH_REQUIRED",
                        run.results().get(5).outcome());
        assertThat(run.results()).extracting(SuiteCaseResult::result)
                .containsExactly(TestResult.PASS, TestResult.PASS, TestResult.PASS, TestResult.PASS, TestResult.FAIL, TestResult.UNVERIFIED);
        // Scenario 14 and scenario 2 must never read alike: no answer is not "not required".
        assertThat(run.results().get(2).outcome()).isNotEqualTo(run.results().get(1).outcome());
        assertThat(run.results().get(5).outcome()).isNotBlank();
    }
}
