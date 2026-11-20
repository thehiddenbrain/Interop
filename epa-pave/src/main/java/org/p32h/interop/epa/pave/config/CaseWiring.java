package org.p32h.interop.epa.pave.config;

import java.util.stream.Stream;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.cases.ChainCaseStep;
import org.p32h.interop.epa.pave.service.cases.CrdCaseStep;
import org.p32h.interop.epa.pave.service.cases.DtrCaseStep;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.cases.PasCaseStep;
import org.p32h.interop.epa.pave.service.cases.ProtocolCaseRunner;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrCodes;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.pas.PasCodes;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.suite.CsvRows;
import org.p32h.interop.epa.pave.service.suite.SuiteFileReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts together the parts that span the protocols: the catalogue of every outcome each protocol
 * can produce, and the runner that runs one case from its inputs alone, for suites and replay.
 *
 * <p>These are assembled here and not by a stereotype on each class because each one is told
 * about every protocol in one place. A protocol that is added to the tool and not registered here
 * would still run from its own tab, and would be "not runnable" from a suite with its outcomes
 * shown as bare codes, so this is the one place to look when a protocol is added.
 */
@Configuration
public class CaseWiring {

    @Bean
    public OutcomeCatalogue outcomeCatalogue() {
        // A subscription call and a notification that arrived are both recorded under the
        // subscription protocol, each with outcomes of its own, so the two lists are read as one.
        OutcomeCode[] subscription = Stream.concat(Stream.of(SubscriptionOutcome.values()), Stream.of(NotificationOutcome.values()))
                .toArray(OutcomeCode[]::new);
        return new OutcomeCatalogue()
                .register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS)
                .register(Protocol.DTR, DtrOutcome.values(), DtrCodes.EXPECTATION_ELEMENTS)
                .register(Protocol.PAS, PasOutcome.values(), PasCodes.EXPECTATION_ELEMENTS)
                .register(Protocol.SUBSCRIPTION, subscription, null);
    }

    /**
     * The operations a suite row or a replay can run. The ones left out need what a person or an
     * earlier step supplies, which a case does not hold: answering a questionnaire, and creating
     * or changing a subscription.
     */
    @Bean
    public CaseRunner caseRunner(MemberLookup members, ProviderLookup providers, CrdService crd, DtrService dtr, PasService pas,
                                 OutcomeCatalogue outcomes, RunSupport support) {
        return new ProtocolCaseRunner(members, providers)
                .register(EpaOperation.CRD_ORDER_SIGN, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SIGN))
                .register(EpaOperation.CRD_ORDER_SELECT, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SELECT))
                .register(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, new DtrCaseStep(dtr))
                .register(EpaOperation.PAS_SUBMIT, new PasCaseStep(pas, EpaOperation.PAS_SUBMIT))
                .register(EpaOperation.PAS_INQUIRE, new PasCaseStep(pas, EpaOperation.PAS_INQUIRE))
                .registerChain(new ChainCaseStep(crd, dtr, pas, outcomes, support));
    }

    @Bean
    public SuiteFileReader suiteFileReader(CsvRows csv) {
        return new SuiteFileReader(csv);
    }
}
