package com.thehiddenbrain.interop.patientaccess.conformance;

import com.thehiddenbrain.interop.patientaccess.common.ApiError;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.conformance.checks.SimpleCheck;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConformanceRunnerTest {

    @TempDir
    Path dir;
    TestGraph g;
    Environment env;
    RunStore store;
    ConformanceRunner runner;
    SimpleCheck passing;
    SimpleCheck throwing;
    SimpleCheck needsPatient;
    SimpleCheck upstreamFailure;

    @BeforeEach
    void setUp() {
        g = new TestGraph(dir);
        env = g.openEnvironment("wm", "http://127.0.0.1:1/fhir");
        passing = new SimpleCheck("discovery.pass", "discovery", "Passes", Severity.SHALL, "always passes", "cite", false,
                ctx -> CheckResult.builder(passing).detail("fhirVersion 4.0.1").evidence("req-1").evidence("req-1").pass("ok for " + ctx.environment().name()));
        throwing = new SimpleCheck("smart.throw", "smart", "Throws <b>", Severity.SHOULD, "blows up", "cite", false, ctx -> {
            throw new IllegalStateException("boom");
        });
        needsPatient = new SimpleCheck("patient.read", "patient", "Needs a patient", Severity.SHALL, "reads the patient", "cite", true,
                ctx -> CheckResult.builder(needsPatient).pass("patient " + ctx.patientId().orElseThrow()));
        upstreamFailure = new SimpleCheck("eob.upstream", "eob", "Upstream failure", Severity.MAY, "transport error", "cite", false, ctx -> {
            throw new WorkbenchException(ErrorCode.UPSTREAM_UNREACHABLE, "GET failed", List.of(), new ApiError.Upstream(null, "u", null, "req-9"), null);
        });
        // register in reverse order to prove the suite orders by group then id
        ConformanceSuite suite = new ConformanceSuite(List.of(upstreamFailure, needsPatient, throwing), List.of(() -> List.of(passing)));
        store = new RunStore(dir.resolve("conformance"));
        runner = new ConformanceRunner(suite, store, g.gateway, g.catalog, g.checker, g.priorAuth, g.discovery, g.properties, new SyncTaskExecutor(), g.clock);
    }

    @Test
    void runNowExecutesEveryCheckAndAggregatesStatuses() {
        ConformanceRun run = runner.runNow(env, null, null);
        assertThat(run.id()).startsWith("run-");
        assertThat(run.status()).isEqualTo(ConformanceRun.DONE);
        assertThat(run.environmentId()).isEqualTo(env.id());
        assertThat(run.environmentName()).isEqualTo("wm");
        assertThat(run.patientId()).isNull();
        assertThat(run.groups()).isEmpty();
        assertThat(run.totalChecks()).isEqualTo(4);
        assertThat(run.completedChecks()).isEqualTo(4);
        assertThat(run.startedAt()).isEqualTo(g.clock.instant());
        assertThat(run.finishedAt()).isEqualTo(g.clock.instant());
        assertThat(run.error()).isNull();
        assertThat(run.counts()).containsEntry("PASS", 1).containsEntry("ERROR", 2).containsEntry("SKIP", 1).containsEntry("FAIL", 0)
                .containsEntry("WARN", 0).containsEntry("INFO", 0);
        assertThat(run.results()).extracting(CheckResult::checkId).containsExactly("discovery.pass", "smart.throw", "patient.read", "eob.upstream");

        CheckResult pass = run.results().get(0);
        assertThat(pass.status()).isEqualTo(CheckStatus.PASS);
        assertThat(pass.message()).isEqualTo("ok for wm");
        assertThat(pass.details()).containsExactly("fhirVersion 4.0.1");
        assertThat(pass.requestIds()).containsExactly("req-1");
        assertThat(pass.severity()).isEqualTo(Severity.SHALL);
        assertThat(pass.group()).isEqualTo("discovery");
        assertThat(pass.title()).isEqualTo("Passes");
        assertThat(pass.durationMs()).isGreaterThanOrEqualTo(0);

        CheckResult error = run.results().get(1);
        assertThat(error.status()).isEqualTo(CheckStatus.ERROR);
        assertThat(error.message()).isEqualTo("check failed: java.lang.IllegalStateException: boom");

        CheckResult skipped = run.results().get(2);
        assertThat(skipped.status()).isEqualTo(CheckStatus.SKIP);
        assertThat(skipped.message()).contains("needs a patient id");

        CheckResult upstream = run.results().get(3);
        assertThat(upstream.status()).isEqualTo(CheckStatus.ERROR);
        assertThat(upstream.message()).isEqualTo("GET failed");
        assertThat(upstream.requestIds()).containsExactly("req-9");
    }

    @Test
    void patientAndGroupSelectionAreHonoured() {
        ConformanceRun withPatient = runner.runNow(env, "Patient1", List.of("patient", "discovery"));
        assertThat(withPatient.totalChecks()).isEqualTo(2);
        assertThat(withPatient.groups()).containsExactly("patient", "discovery");
        assertThat(withPatient.results()).extracting(CheckResult::checkId).containsExactly("discovery.pass", "patient.read");
        assertThat(withPatient.results().get(1).status()).isEqualTo(CheckStatus.PASS);
        assertThat(withPatient.results().get(1).message()).isEqualTo("patient Patient1");
        assertThat(withPatient.counts()).containsEntry("PASS", 2).containsEntry("SKIP", 0);

        assertThatThrownBy(() -> runner.start(env, null, List.of("nonexistent"))).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        // start() runs on the executor (synchronous here) and leaves a finished run behind
        ConformanceRun started = runner.start(env, null, List.of("discovery"));
        assertThat(store.find(started.id()).orElseThrow().status()).isEqualTo(ConformanceRun.DONE);
        assertThat(started.summary().totalChecks()).isEqualTo(1);
        assertThat(ConformanceRunner.counts(List.of())).containsKeys("PASS", "FAIL", "WARN", "INFO", "SKIP", "ERROR");
    }

    @Test
    void runsArePersistedReloadedListedNewestFirstAndDeleted() throws Exception {
        ConformanceRun first = runner.runNow(env, null, List.of("discovery"));
        g.clock.advance(Duration.ofMinutes(5));
        ConformanceRun second = runner.runNow(env, "p", List.of("patient"));
        Environment other = g.openEnvironment("other", "http://127.0.0.1:1/fhir");
        g.clock.advance(Duration.ofMinutes(5));
        ConformanceRun third = runner.runNow(other, null, List.of("smart"));

        Path file = dir.resolve("conformance").resolve(first.id() + ".json");
        assertThat(file).isRegularFile();
        assertThat(Files.readString(file)).describedAs("persisted JSON").contains("\"status\" : \"DONE\"").contains("discovery.pass");
        assertThat(store.list(null)).extracting(ConformanceRun.Summary::id).containsExactly(third.id(), second.id(), first.id());
        assertThat(store.list(env.id())).extracting(ConformanceRun.Summary::id).containsExactly(second.id(), first.id());
        assertThat(store.list(env.id()).get(0).counts()).containsEntry("PASS", 1);

        RunStore reloaded = new RunStore(dir.resolve("conformance"));
        ConformanceRun back = reloaded.find(first.id()).orElseThrow();
        assertThat(back.results()).hasSize(1);
        assertThat(back.results().get(0).status()).isEqualTo(CheckStatus.PASS);
        assertThat(back.results().get(0).requestIds()).containsExactly("req-1");
        assertThat(back.startedAt()).isEqualTo(first.startedAt());
        assertThat(reloaded.list(null)).hasSize(3);

        // a run that is still RUNNING is kept in memory only
        ConformanceRun running = new ConformanceRun("run-live", env.id(), env.name(), null, List.of(), ConformanceRun.RUNNING, g.clock.instant(), null, 1, 0,
                Map.of(), List.of(), null);
        store.save(running);
        assertThat(dir.resolve("conformance").resolve("run-live.json")).doesNotExist();
        assertThat(store.find("run-live")).isPresent();

        store.delete(first.id());
        assertThat(file).doesNotExist();
        assertThat(store.find(first.id())).isEmpty();
        assertThatThrownBy(() -> store.delete(first.id())).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(new RunStore(dir.resolve("conformance")).list(null)).hasSize(2);
        assertThat(new RunStore(dir.resolve("nowhere")).list(null)).isEmpty();
    }

    @Test
    void htmlReportRendersStatusesAndEscapesContent() {
        ConformanceRun run = runner.runNow(env, null, null);
        String html = new HtmlReport().render(run);
        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("<title>Conformance report " + run.id() + "</title>");
        assertThat(html).contains("<b>Environment:</b> wm").contains("<b>Patient:</b> (none)").contains("<b>Status:</b> DONE");
        assertThat(html).contains("<span class=\"s PASS\">PASS</span>").contains("<span class=\"s ERROR\">ERROR</span>").contains("<span class=\"s SKIP\">SKIP</span>");
        assertThat(html).contains("<span class=\"s PASS\">PASS 1</span>").contains("<span class=\"s ERROR\">ERROR 2</span>");
        assertThat(html).contains("<h2>Discovery</h2>").contains("<h2>SMART discovery</h2>").contains("<h2>Patient</h2>").contains("<h2>Claims (EOB)</h2>");
        assertThat(html).doesNotContain("<h2>Coverage</h2>");
        assertThat(html).contains("Throws &lt;b&gt;").doesNotContain("Throws <b>");
        assertThat(html).contains("check failed: java.lang.IllegalStateException: boom");
        assertThat(html).contains("<li>fhirVersion 4.0.1</li>").contains("evidence: req-1");
        assertThat(html).contains("<code class=\"muted\">discovery.pass</code>");

        ConformanceRun failed = new ConformanceRun("run-x", env.id(), "<env>", "<pat>", List.of(), ConformanceRun.FAILED, g.clock.instant(), g.clock.instant(),
                0, 0, ConformanceRunner.counts(List.of()), List.of(), "interrupted <now>");
        String failedHtml = new HtmlReport().render(failed);
        assertThat(failedHtml).contains("<b>Environment:</b> &lt;env&gt;").contains("<b>Patient:</b> &lt;pat&gt;")
                .contains("<p class=\"s ERROR\">interrupted &lt;now&gt;</p>").doesNotContain("<pat>").doesNotContain("<env>");
    }
}
