package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.chain.ChainService;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The chain endpoints as a browser sees them. What a chain says is tested with the chain service. */
class ChainControllerTest {

    private final InMemoryLedger ledger = new InMemoryLedger();

    /** Registered through an annotated subclass, for the reason given in CrdControllerTest. */
    @RestController
    static class Wired extends ChainController {
        Wired(ChainService service) {
            super(service);
        }
    }

    private MockMvc mvc() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return TestMvc.standalone(new Wired(new ChainService(beans.getBeanProvider(RunLedger.class),
                new OutcomeCatalogue().register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS))));
    }

    private String chain(String outcome, NextAction next) {
        String chainId = RunStamps.newChainId();
        ledger.record(RunRecord.builder().runId(RunStamps.newRunId()).timestamp("2026-03-10T10:00:00.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(outcome).result(TestResult.PASS).expected(outcome).httpStatus(200)
                .elapsedMillis(120).chainId(chainId).nextAction(next).build());
        return chainId;
    }

    private static JsonNode json(MockMvc mvc, String path, int expectedStatus) throws Exception {
        return JsonSafe.parse(mvc.perform(get(path)).andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString()).node();
    }

    @Test
    void aChainIsReadByItsId() throws Exception {
        String chainId = chain("NO_AUTH_REQUIRED", NextAction.FLOW_ENDS);
        JsonNode view = json(mvc(), "/api/chains/" + chainId, 200);
        assertThat(view.get("chainId").stringValue()).isEqualTo(chainId);
        assertThat(view.get("ended").booleanValue()).isTrue();
        assertThat(view.get("nextAction").stringValue()).isEqualTo("FLOW_ENDS");
        assertThat(view.get("headline").stringValue()).endsWith("The journey ends here.");
        assertThat(view.get("stages")).hasSize(3);
        JsonNode crd = view.get("stages").get(0);
        assertThat(crd.get("protocol").stringValue()).isEqualTo("CRD");
        assertThat(crd.get("state").stringValue()).isEqualTo("DONE");
        // The outcome and the test result are two fields of the run.
        assertThat(crd.get("latest").get("outcome").stringValue()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(crd.get("latest").get("outcomeLabel").stringValue()).isEqualTo("Prior authorization is not required");
        assertThat(crd.get("latest").get("result").stringValue()).isEqualTo("pass");
        assertThat(view.get("stages").get(1).get("state").stringValue()).isEqualTo("NOT_NEEDED");
        assertThat(view.get("stages").get(1).has("latest")).isFalse();
    }

    @Test
    void theListAnswersWithOneRowPerChain() throws Exception {
        String first = chain("NO_AUTH_REQUIRED", NextAction.FLOW_ENDS);
        String second = chain("AUTH_REQUIRED", NextAction.START_PAS);
        JsonNode list = json(mvc(), "/api/chains?from=2026-03-10&to=2026-03-10&limit=10", 200);
        assertThat(list.get("chains")).hasSize(2);
        assertThat(list.get("chains").get(0).get("chainId").stringValue()).isEqualTo(second);
        assertThat(list.get("chains").get(1).get("chainId").stringValue()).isEqualTo(first);
        assertThat(list.get("chains").get(0).get("nextStage").stringValue()).isEqualTo("PAS");
        assertThat(json(mvc(), "/api/chains?from=2026-03-11", 200).get("chains")).isEmpty();
    }

    @Test
    void anUnknownChainIsA404AndABadParameterIsA422NamingIt() throws Exception {
        JsonNode missing = json(mvc(), "/api/chains/" + RunStamps.newChainId(), 404);
        assertThat(missing.get("kind").stringValue()).isEqualTo("NOT_FOUND");
        assertThat(json(mvc(), "/api/chains/not-a-chain", 404).get("kind").stringValue()).isEqualTo("NOT_FOUND");
        for (String[] bad : new String[][] {{"from=yesterday", "from"}, {"to=2026-02-30", "to"}, {"limit=many", "limit"},
                {"limit=0", "limit"}, {"from=2026-03-11&to=2026-03-10", "from"}}) {
            JsonNode refused = json(mvc(), "/api/chains?" + bad[0], 422);
            assertThat(refused.get("kind").stringValue()).as(bad[0]).isEqualTo("VALIDATION");
            assertThat(refused.get("fieldErrors").get(0).get("field").stringValue()).as(bad[0]).isEqualTo(bad[1]);
        }
    }
}
