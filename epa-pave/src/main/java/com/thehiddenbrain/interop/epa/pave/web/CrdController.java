package com.thehiddenbrain.interop.epa.pave.web;

import com.thehiddenbrain.interop.epa.pave.client.crd.MockOnyxCrdClient;
import com.thehiddenbrain.interop.epa.pave.config.EpaProperties;
import com.thehiddenbrain.interop.epa.pave.model.RunEnvelope;
import com.thehiddenbrain.interop.epa.pave.model.RunOrigin;
import com.thehiddenbrain.interop.epa.pave.model.ScenarioSummary;
import com.thehiddenbrain.interop.epa.pave.model.crd.CrdDetail;
import com.thehiddenbrain.interop.epa.pave.service.crd.CrdDiscovery;
import com.thehiddenbrain.interop.epa.pave.service.crd.CrdPreview;
import com.thehiddenbrain.interop.epa.pave.service.crd.CrdRunRequest;
import com.thehiddenbrain.interop.epa.pave.service.crd.CrdService;
import com.thehiddenbrain.interop.epa.pave.service.scenario.Scenario;
import com.thehiddenbrain.interop.epa.pave.service.scenario.ScenarioRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The CRD endpoints. A completed call answers 200 even when the service returned a fault, because
 * the tester needs to see the request that caused it: the fault is described in the body.
 */
@RequestMapping("/api/crd")
public class CrdController {

    /**
     * The scenario list.
     *
     * @param available false on the live profile, where there is no selector to offer
     * @param reason    why there is none, or null
     * @param problems  scenario files that could not be used, by name, with the reason
     */
    public record ScenarioList(boolean available, String reason, List<ScenarioSummary> scenarios, List<String> problems) {
    }

    private final CrdService service;
    private final ScenarioRepository scenarios;
    private final EpaProperties epa;

    public CrdController(CrdService service, ScenarioRepository scenarios, EpaProperties epa) {
        this.service = service;
        this.scenarios = scenarios;
        this.epa = epa;
    }

    @PostMapping
    public RunEnvelope<CrdDetail> run(@RequestBody CrdRunRequest request) {
        return service.run(request, RunOrigin.manual());
    }

    @PostMapping("/preview")
    public CrdPreview preview(@RequestBody CrdRunRequest request) {
        return service.preview(request);
    }

    @GetMapping("/discover")
    public CrdDiscovery discover() {
        return service.discover();
    }

    /**
     * On mock, the scenarios a tester can choose from. On live the list is empty and says why: a
     * scenario forced against the real service would produce a result that did not come from it.
     */
    @GetMapping("/scenarios")
    public ScenarioList scenarios() {
        if (!epa.mock()) {
            return new ScenarioList(false, "Scenarios exist on the mock profile only. On live every answer comes from the service.",
                    List.of(), List.of());
        }
        ScenarioRepository.Loaded loaded = scenarios.load(MockOnyxCrdClient.SCENARIOS);
        return new ScenarioList(true, null, loaded.scenarios().stream().map(Scenario::summary).toList(), loaded.problems());
    }
}
