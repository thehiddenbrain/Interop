package org.p32h.interop.epa.pave.devharness;

import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.PaveApplication;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.client.RawRequestClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
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
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.cases.CrdCaseStep;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.cases.ProtocolCaseRunner;
import org.p32h.interop.epa.pave.service.chain.ChainService;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.raw.RawService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.suite.SuiteFileReader;
import org.p32h.interop.epa.pave.service.suite.SuiteService;
import org.p32h.interop.epa.pave.service.suite.TestCsv;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.web.ChainController;
import org.p32h.interop.epa.pave.web.CrdController;
import org.p32h.interop.epa.pave.web.ModeController;
import org.p32h.interop.epa.pave.web.RawController;
import org.p32h.interop.epa.pave.web.SuiteController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application as far as it is built, started with stand-ins for the parts still being written
 * (the configuration layer, the ledger, the code list and the lookups), so the frontend can be
 * built against real CRD, suite and chain responses before those parts arrive.
 *
 * <p>Test scope only, never in the jar, and removed when the real parts are wired. The /dev
 * endpoints exist so a screen state can be reached on purpose: a production environment, a
 * refused write, a ledger that did not record.
 */
// A test configuration, so that the application's own context test never picks this class or
// its nested controllers up when it scans the root package with the test classes on the path.
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = PaveApplication.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test(\\$.*)?"),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PaveApplication.class)})
public class DevHarness {

    public static void main(String[] args) {
        SpringApplication.run(DevHarness.class, args);
    }

    @Bean
    FakeGateway gateway() {
        return FakeGateway.unconfigured();
    }

    @Bean
    InMemoryLedger ledger() {
        return new InMemoryLedger();
    }

    @Bean
    ProcedureCodeDirectory codeList() {
        return new FakeCodeList()
                .row("95810", AuthRequirement.REQUIRED, "Medicare", null)
                .row("95811", AuthRequirement.NOT_REQUIRED, "Medicare", null)
                .row("70553", AuthRequirement.REQUIRED, "Medicare", "Evolent");
    }

    @Bean
    MemberLookup members() {
        return id -> new MemberLookupResult(LookupStatus.FOUND, MemberIds.forms(id),
                MemberDetail.builder().memberId(id).firstName("Given").lastName("Placeholder").dateOfBirth("1970-01-31")
                        .gender("female").lineOfBusiness("Medicare").build(), List.of(), List.of(), List.of(), null, null,
                "Found by the stand-in lookup.");
    }

    @Bean
    ProviderLookup providers() {
        return npi -> new ProviderLookupResult(LookupStatus.FOUND, npi,
                ProviderDetail.builder().npi(npi).firstName("Pat").lastName("Provider").build(), List.of(), List.of(), List.of(), null,
                null, "Found by the stand-in lookup.");
    }

    @Bean
    CrdService crdService(EpaProperties epa, OperationGateway gateway, OnyxCrdClient client, ProcedureCodeDirectory codes, RunSupport support) {
        return new CrdService(epa, gateway, client, codes, support);
    }

    @Bean
    RawService rawService(EpaProperties epa, OperationGateway gateway, RawRequestClient client, RunSupport support) {
        return new RawService(epa, gateway, client, support);
    }

    @Bean
    OutcomeCatalogue outcomes() {
        return new OutcomeCatalogue().register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS);
    }

    @Bean
    CaseRunner caseRunner(MemberLookup members, ProviderLookup providers, CrdService crd) {
        return new ProtocolCaseRunner(members, providers)
                .register(EpaOperation.CRD_ORDER_SIGN, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SIGN))
                .register(EpaOperation.CRD_ORDER_SELECT, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SELECT));
    }

    @Bean(destroyMethod = "close")
    SuiteService suiteService(ObjectProvider<CaseRunner> runner, ObjectProvider<RunLedger> ledger, OperationGateway gateway,
                              EpaProperties epa, OutcomeCatalogue outcomes, RunSupport support) {
        return new SuiteService(runner, ledger, gateway, epa, outcomes, new SuiteFileReader(new TestCsv()), support);
    }

    @Bean
    ChainService chainService(ObjectProvider<RunLedger> ledger, OutcomeCatalogue outcomes) {
        return new ChainService(ledger, outcomes);
    }

    @RestController
    static class Mode extends ModeController {
        Mode(EpaProperties epa, OperationGateway gateway, Environment environment) {
            super(epa, gateway, environment);
        }
    }

    @RestController
    static class Crd extends CrdController {
        Crd(CrdService service, ScenarioRepository scenarios, EpaProperties epa) {
            super(service, scenarios, epa);
        }
    }

    @RestController
    static class Raw extends RawController {
        Raw(RawService service) {
            super(service);
        }
    }

    @RestController
    static class Suites extends SuiteController {
        Suites(SuiteService service) {
            super(service);
        }
    }

    @RestController
    static class Chains extends ChainController {
        Chains(ChainService service) {
            super(service);
        }
    }

    /** Switches for reaching a screen state on purpose. None of this exists in the application. */
    @RestController
    @RequestMapping("/dev")
    static class Dev {

        private final FakeGateway gateway;
        private final InMemoryLedger ledger;

        Dev(FakeGateway gateway, InMemoryLedger ledger) {
            this.gateway = gateway;
            this.ledger = ledger;
        }

        /** kind: sandbox, uat, prod, or none. Moves the configuration epoch, as activating an environment does. */
        @PostMapping("/environment")
        Map<String, Object> environment(@RequestParam(name = "name", required = false) String name,
                                        @RequestParam(name = "kind", defaultValue = "none") String kind) {
            if ("none".equalsIgnoreCase(kind)) {
                gateway.environment(null, null);
            } else {
                gateway.environment(name == null ? "Onyx " + kind : name, EnvironmentKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)));
            }
            gateway.changeConfiguration();
            return Map.of("epoch", gateway.epoch());
        }

        /** fail: blocked, not-configured, or none. */
        @PostMapping("/gateway")
        Map<String, Object> gateway(@RequestParam(name = "fail", defaultValue = "none") String fail) {
            EnvironmentRef environment = gateway.activeEnvironment();
            gateway.failing(switch (fail) {
                case "blocked" -> new ProdWriteBlockedException(EpaOperation.CRD_ORDER_SIGN, environment,
                        "Writes to the production environment " + environment.name() + " are blocked.",
                        "Unlock writes on the Config tab, or switch environment.");
                case "not-configured" -> new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN,
                        "There is no url for CRD order-sign in the active configuration.");
                default -> null;
            });
            return Map.of("fail", fail);
        }

        /** refuse: the message a ledger that did not record would give. Leave it out to record again. */
        @PostMapping("/ledger")
        Map<String, Object> ledger(@RequestParam(name = "refuse", required = false) String refuse) {
            ledger.refusing(refuse);
            return Map.of("refusing", refuse != null);
        }
    }
}
