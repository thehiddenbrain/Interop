package org.p32h.interop.epa.pave.config;

import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.cases.CrdCaseStep;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.cases.ProtocolCaseRunner;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.crd.CrdService;
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
        return new OutcomeCatalogue()
                .register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS);
    }

    @Bean
    public CaseRunner caseRunner(MemberLookup members, ProviderLookup providers, CrdService crd) {
        return new ProtocolCaseRunner(members, providers)
                .register(EpaOperation.CRD_ORDER_SIGN, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SIGN))
                .register(EpaOperation.CRD_ORDER_SELECT, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SELECT));
    }

    @Bean
    public SuiteFileReader suiteFileReader(CsvRows csv) {
        return new SuiteFileReader(csv);
    }
}
