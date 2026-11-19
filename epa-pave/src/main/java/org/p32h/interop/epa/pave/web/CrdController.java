package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.OutcomeOption;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.ScenarioList;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.crd.CrdDiscovery;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The CRD endpoints. A completed call answers 200 even when the service returned a fault, because
 * the tester needs to see the request that caused it: the fault is described in the body.
 *
 * <p>The session's production unlock is read here and handed to the service. It is never a
 * request parameter or part of the posted body, so nothing a browser sends can stand in for it.
 *
 * <p>This class is not a Spring bean yet. The gateway, the ledger and the code list it depends on
 * arrive with the first wave, and the integrator registers it once they are merged. Until then it
 * carries no stereotype annotation, so the application context does not try to build it. Its test
 * registers it through a small annotated subclass and drives it with a standalone MockMvc.
 */
@RequestMapping("/api/crd")
public class CrdController {

    private final CrdService service;
    private final ScenarioRepository scenarios;
    private final EpaProperties epa;

    public CrdController(CrdService service, ScenarioRepository scenarios, EpaProperties epa) {
        this.service = service;
        this.scenarios = scenarios;
        this.epa = epa;
    }

    @PostMapping
    public ResponseEntity<RunEnvelope<CrdDetail>> run(@RequestBody CrdRunRequest request, HttpServletRequest http) {
        return RunResponses.of(service.run(request, RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/preview")
    public RequestPreview preview(@RequestBody CrdRunRequest request, HttpServletRequest http) {
        return service.preview(request, SessionUnlock.from(http));
    }

    @GetMapping("/discover")
    public CrdDiscovery discover() {
        return service.discover();
    }

    /**
     * On mock, the scenarios a tester can choose from. On live the list is empty and says why: a
     * scenario forced against the real service would produce a result that did not come from it.
     * On both, the outcomes and element names a tester can declare as an expectation.
     */
    @GetMapping("/scenarios")
    public ScenarioList scenarios() {
        List<OutcomeOption> outcomes = OutcomeOption.listOf(CrdOutcome.values(), RunOutcome.values());
        if (!epa.mock()) {
            return new ScenarioList(false, "Scenarios exist on the mock profile only. On live every answer comes from the service.",
                    List.of(), List.of(), outcomes, CrdCodes.EXPECTATION_ELEMENTS);
        }
        ScenarioRepository.Loaded loaded = scenarios.load(MockOnyxCrdClient.SCENARIOS);
        return new ScenarioList(true, null, loaded.scenarios().stream().map(Scenario::summary).toList(), loaded.problems(), outcomes,
                CrdCodes.EXPECTATION_ELEMENTS);
    }
}
