package org.p32h.interop.epa.pave.service.suite;

import org.p32h.interop.epa.pave.service.codelist.CsvRowsAdapter;
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
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.config.CaseWiring;
import org.p32h.interop.epa.pave.service.cases.ChainCaseStep;
import org.p32h.interop.epa.pave.service.cases.CrdCaseStep;
import org.p32h.interop.epa.pave.service.cases.DtrCaseStep;
import org.p32h.interop.epa.pave.service.cases.PasCaseStep;
import org.p32h.interop.epa.pave.service.cases.StepGateway;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.cases.ProtocolCaseRunner;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A suite service over the real case runner, the real CRD, DTR and PAS services and the mock
 * scenario files, with fakes for the gateway, the ledger, the code list and the two lookups.
 */
public final class SuiteTestBed {

    public static final String SURNAME = "Placeholder-Surname";
    public static final String HEADER = "caseId,protocol,memberId,npi,codes,expected";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));

    public final FakeGateway gateway = FakeGateway.unconfigured();
    /** The gateway the services and the suite use: the fake, with one step of a chain refused or failed when a test says so. */
    public final StepGateway steps = new StepGateway(gateway);
    public final InMemoryLedger ledger = new InMemoryLedger();
    /** The ledger the services write to and the suite reads from. The in-memory one unless a test gives the real one. */
    public RunLedger store = ledger;
    public final FakeCodeList codeList = new FakeCodeList();
    public final Deque<Runnable> queued = new ArrayDeque<>();
    /** Runs a suite inside start() unless a test asks for the task to be held. */
    public boolean hold;
    /** Wraps the real runner, so a test can act between two cases. */
    public java.util.function.UnaryOperator<CaseRunner> wrap = runner -> runner;
    public boolean withRunner = true;
    public boolean withLedger = true;
    public String mode = "mock";
    /** The clock of the runs and the suite. A test that needs time itself to fail gives another. */
    public Clock clock = CLOCK;

    private final MemberLookup members = members();
    private final ProviderLookup providers = npi -> new ProviderLookupResult(LookupStatus.FOUND, npi,
            ProviderDetail.builder().npi(npi).lastName("Provider").build(), List.of(), List.of(), List.of(), null, null, "message");

    /** The lookup every case of this bed is given: one member, of last name {@link #SURNAME}, whatever the id. */
    public static MemberLookup members() {
        return id -> new MemberLookupResult(LookupStatus.FOUND, MemberIds.forms(id),
                MemberDetail.builder().memberId(id).lastName(SURNAME).dateOfBirth("1970-01-31").lineOfBusiness("Medicare").build(),
                List.of(), List.of(), List.of(), null, null, "message");
    }

    public static String suite(String... rows) {
        return HEADER + "\n" + String.join("\n", rows);
    }

    /** The runner as the application wires it: every operation a case can run, and the chain, over the mock clients. */
    public ProtocolCaseRunner runner() {
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        RunSupport support = support(epa);
        ScenarioRepository scenarios = new ScenarioRepository(APP);
        CrdService crd = new CrdService(epa, steps, new MockOnyxCrdClient(scenarios, new MockExchanges(APP)), codeList, support);
        DtrService dtr = new DtrService(epa, steps, new MockOnyxDtrClient(scenarios, new MockExchanges(APP)), codeList, support);
        PasService pas = new PasService(OnyxProperties.defaults(), steps, new MockOnyxPasClient(scenarios, new MockExchanges(APP)), codeList,
                support, ledgerProvider());
        return new ProtocolCaseRunner(members, providers)
                .scenarios((protocol, id) -> scenarios.list(protocol.name().toLowerCase(java.util.Locale.ROOT)).stream()
                        .anyMatch(scenario -> scenario.id().equals(id)))
                .register(EpaOperation.CRD_ORDER_SIGN, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SIGN))
                .register(EpaOperation.CRD_ORDER_SELECT, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SELECT))
                .register(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, new DtrCaseStep(dtr))
                .register(EpaOperation.PAS_SUBMIT, new PasCaseStep(pas, EpaOperation.PAS_SUBMIT))
                .register(EpaOperation.PAS_INQUIRE, new PasCaseStep(pas, EpaOperation.PAS_INQUIRE))
                .registerChain(new ChainCaseStep(crd, dtr, pas, outcomes(), support));
    }

    /** The outcomes of every protocol, as the application registers them. */
    public static OutcomeCatalogue outcomes() {
        return new CaseWiring().outcomeCatalogue();
    }

    private org.springframework.beans.factory.ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", store);
        return beans.getBeanProvider(RunLedger.class);
    }

    private RunSupport support(EpaProperties epa) {
        return new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(), ledgerProvider(), clock);
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

    /** The same, with the time a case in flight is given to finish once the tool begins to stop. */
    public SuiteService serviceOnItsOwnThread(java.time.Duration stopBound) {
        return service(null, stopBound);
    }

    private SuiteService service(Executor executor) {
        return service(executor, SuiteService.LONGEST_STOP);
    }

    private SuiteService service(Executor executor, java.time.Duration stopBound) {
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (withRunner) {
            beans.addBean("runner", wrap.apply(runner()));
        }
        if (withLedger) {
            beans.addBean("ledger", store);
        }
        return new SuiteService(beans.getBeanProvider(CaseRunner.class), beans.getBeanProvider(RunLedger.class), steps, epa,
                outcomes(), new SuiteFileReader(new CsvRowsAdapter()), support(epa), executor, stopBound);
    }
}
