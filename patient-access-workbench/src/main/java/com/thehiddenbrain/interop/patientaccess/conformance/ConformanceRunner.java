package com.thehiddenbrain.interop.patientaccess.conformance;

import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.Ids;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.patient.PriorAuthSummarizer;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/** Starts runs in the background, executes checks with bounded parallelism, keeps progress and persists results. */
@Service
public class ConformanceRunner {

    private static final Logger log = LoggerFactory.getLogger(ConformanceRunner.class);

    private final ConformanceSuite suite;
    private final RunStore store;
    private final FhirGateway gateway;
    private final IgCatalog catalog;
    private final ProfileLiteChecker checker;
    private final PriorAuthSummarizer priorAuth;
    private final SmartDiscoveryService discovery;
    private final WorkbenchProperties properties;
    private final Executor executor;
    private final Clock clock;
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public ConformanceRunner(ConformanceSuite suite, RunStore store, FhirGateway gateway, IgCatalog catalog, ProfileLiteChecker checker,
                             PriorAuthSummarizer priorAuth, SmartDiscoveryService discovery, WorkbenchProperties properties,
                             @Qualifier("workbenchExecutor") TaskExecutor executor, Clock clock) {
        this.suite = suite;
        this.store = store;
        this.gateway = gateway;
        this.catalog = catalog;
        this.checker = checker;
        this.priorAuth = priorAuth;
        this.discovery = discovery;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    public ConformanceRun start(Environment env, String patientId, Collection<String> groups) {
        List<Check> checks = suite.forGroups(groups);
        if (checks.isEmpty()) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "no checks match the requested groups " + groups);
        }
        String runId = "run-" + Ids.next(10);
        ConformanceRun run = new ConformanceRun(runId, env.id(), env.name(), patientId, groups == null ? List.of() : List.copyOf(groups),
                ConformanceRun.RUNNING, clock.instant(), null, checks.size(), 0, new LinkedHashMap<>(), List.of(), null);
        store.save(run);
        executor.execute(() -> execute(run, env, patientId, checks));
        return run;
    }

    /** Runs synchronously (tests and scripts). */
    public ConformanceRun runNow(Environment env, String patientId, Collection<String> groups) {
        List<Check> checks = suite.forGroups(groups);
        String runId = "run-" + Ids.next(10);
        ConformanceRun run = new ConformanceRun(runId, env.id(), env.name(), patientId, groups == null ? List.of() : List.copyOf(groups),
                ConformanceRun.RUNNING, clock.instant(), null, checks.size(), 0, new LinkedHashMap<>(), List.of(), null);
        store.save(run);
        execute(run, env, patientId, checks);
        return store.find(runId).orElseThrow();
    }

    public void cancel(String runId) {
        cancelled.add(runId);
    }

    private void execute(ConformanceRun run, Environment env, String patientId, List<Check> checks) {
        CheckContext ctx = new CheckContext(run.id(), env, patientId, gateway, catalog, checker, priorAuth, discovery, properties);
        List<CheckResult> results = new ArrayList<>();
        Semaphore slots = new Semaphore(Math.max(1, properties.conformance().concurrency()));
        List<Thread> workers = new ArrayList<>();
        try {
            for (Check check : checks) {
                if (cancelled.contains(run.id())) {
                    break;
                }
                slots.acquire();
                Thread t = new Thread(() -> {
                    try {
                        CheckResult result = runOne(check, ctx);
                        synchronized (results) {
                            results.add(result);
                            store.save(progress(run, results, ConformanceRun.RUNNING, null));
                        }
                    } finally {
                        slots.release();
                    }
                }, "paw-check-" + check.id());
                workers.add(t);
                t.start();
            }
            for (Thread t : workers) {
                t.join();
            }
            String status = cancelled.remove(run.id()) ? ConformanceRun.CANCELLED : ConformanceRun.DONE;
            store.save(progress(run, results, status, null));
            log.info("conformance run {} {}: {}", run.id(), status, counts(results));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.save(progress(run, results, ConformanceRun.FAILED, "interrupted"));
        } catch (RuntimeException e) {
            log.error("conformance run {} failed", run.id(), e);
            store.save(progress(run, results, ConformanceRun.FAILED, e.getMessage()));
        }
    }

    CheckResult runOne(Check check, CheckContext ctx) {
        long start = System.nanoTime();
        try {
            if (check.needsPatient() && ctx.patientId().isEmpty()) {
                return CheckResult.builder(check).skip("needs a patient id; start the run with a member selected");
            }
            CheckResult r = check.run(ctx);
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new CheckResult(r.checkId(), r.group(), r.title(), r.severity(), r.status(), r.message(), r.details(), r.requestIds(), ms);
        } catch (WorkbenchException e) {
            CheckResult.Builder b = CheckResult.builder(check).duration((System.nanoTime() - start) / 1_000_000);
            if (e.getUpstream() != null) {
                b.evidence(e.getUpstream().requestId());
            }
            return b.error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("check {} threw", check.id(), e);
            return CheckResult.builder(check).duration((System.nanoTime() - start) / 1_000_000).error("check failed: " + e);
        }
    }

    private ConformanceRun progress(ConformanceRun run, List<CheckResult> results, String status, String error) {
        List<CheckResult> ordered = new ArrayList<>(results);
        List<String> order = suite.all().stream().map(Check::id).toList();
        ordered.sort((a, b) -> Integer.compare(order.indexOf(a.checkId()), order.indexOf(b.checkId())));
        return new ConformanceRun(run.id(), run.environmentId(), run.environmentName(), run.patientId(), run.groups(), status, run.startedAt(),
                ConformanceRun.RUNNING.equals(status) ? null : clock.instant(), run.totalChecks(), ordered.size(), counts(ordered), ordered, error);
    }

    static Map<String, Integer> counts(List<CheckResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CheckStatus s : CheckStatus.values()) {
            counts.put(s.name(), 0);
        }
        for (CheckResult r : results) {
            counts.merge(r.status().name(), 1, Integer::sum);
        }
        return counts;
    }
}
