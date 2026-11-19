package org.p32h.interop.epa.pave.service.suite;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.cases.CrdCaseStep;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.cases.ProtocolCaseRunner;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A suite service over the real case runner, the real CRD service and the mock scenario files,
 * with fakes for the gateway, the ledger, the code list and the two lookups.
 */
public final class SuiteTestBed {

    public static final String SURNAME = "Placeholder-Surname";
    public static final String HEADER = "caseId,protocol,memberId,npi,codes,expected";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));

    public final FakeGateway gateway = FakeGateway.unconfigured();
    public final InMemoryLedger ledger = new InMemoryLedger();
    public final FakeCodeList codeList = new FakeCodeList();
    public final Deque<Runnable> queued = new ArrayDeque<>();
    /** Runs a suite inside start() unless a test asks for the task to be held. */
    public boolean hold;
    /** Wraps the real runner, so a test can act between two cases. */
    public java.util.function.UnaryOperator<CaseRunner> wrap = runner -> runner;
    public boolean withRunner = true;
    public boolean withLedger = true;
    public String mode = "mock";

    private final MemberLookup members = id -> new MemberLookupResult(LookupStatus.FOUND, MemberIds.forms(id),
            MemberDetail.builder().memberId(id).lastName(SURNAME).dateOfBirth("1970-01-31").lineOfBusiness("Medicare").build(),
            List.of(), List.of(), List.of(), null, null, "message");
    private final ProviderLookup providers = npi -> new ProviderLookupResult(LookupStatus.FOUND, npi,
            ProviderDetail.builder().npi(npi).lastName("Provider").build(), List.of(), List.of(), List.of(), null, null, "message");

    public static String suite(String... rows) {
        return HEADER + "\n" + String.join("\n", rows);
    }

    public CaseRunner runner() {
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        CrdService crd = new CrdService(epa, gateway, new MockOnyxCrdClient(new ScenarioRepository(APP), new MockExchanges(APP)),
                codeList, support(epa));
        return new ProtocolCaseRunner(members, providers)
                .register(EpaOperation.CRD_ORDER_SIGN, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SIGN))
                .register(EpaOperation.CRD_ORDER_SELECT, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SELECT));
    }

    private RunSupport support(EpaProperties epa) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), CLOCK);
    }

    public SuiteService service() {
        Executor executor = task -> {
            if (hold) {
                queued.add(task);
            } else {
                task.run();
            }
        };
        return service(executor);
    }

    /** The service as the application builds it, running a suite on its own background thread. */
    public SuiteService serviceOnItsOwnThread() {
        return service(null);
    }

    private SuiteService service(Executor executor) {
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (withRunner) {
            beans.addBean("runner", wrap.apply(runner()));
        }
        if (withLedger) {
            beans.addBean("ledger", ledger);
        }
        OutcomeCatalogue outcomes = new OutcomeCatalogue().register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS);
        return new SuiteService(beans.getBeanProvider(CaseRunner.class), beans.getBeanProvider(RunLedger.class), gateway, epa,
                outcomes, new SuiteFileReader(new TestCsv()), support(epa), executor);
    }
}
