package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.OutcomeOption;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.ScenarioList;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.service.dtr.DtrCodes;
import org.p32h.interop.epa.pave.service.dtr.DtrNextQuestionRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrResponseRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The DTR endpoints: the questionnaire package, one step of the adaptive loop, and the
 * questionnaire response. They drive the server-side FHIR operations. There is no SMART launch
 * behind any of them, and every result says so.
 *
 * <p>A completed call answers 200 even when the service returned a fault, because the tester needs
 * to see the request that caused it: the fault is described in the body.
 *
 * <p>The session's production unlock is read here and handed to the service. It is never a request
 * parameter or part of the posted body, so nothing a browser sends can stand in for it. The
 * response step takes none, because it sends nothing.
 */
@RestController
@RequestMapping("/api/dtr")
public class DtrController {

    private final DtrService service;
    private final ScenarioRepository scenarios;
    private final EpaProperties epa;

    public DtrController(DtrService service, ScenarioRepository scenarios, EpaProperties epa) {
        this.service = service;
        this.scenarios = scenarios;
        this.epa = epa;
    }

    @PostMapping("/package")
    public ResponseEntity<RunEnvelope<DtrDetail>> questionnairePackage(@RequestBody DtrPackageRequest request, HttpServletRequest http) {
        return RunResponses.of(service.runPackage(request, RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/package/preview")
    public RequestPreview previewPackage(@RequestBody DtrPackageRequest request, HttpServletRequest http) {
        return service.previewPackage(request, SessionUnlock.from(http));
    }

    @PostMapping("/next-question")
    public ResponseEntity<RunEnvelope<DtrDetail>> nextQuestion(@RequestBody DtrNextQuestionRequest request, HttpServletRequest http) {
        return RunResponses.of(service.runNextQuestion(request, RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/next-question/preview")
    public RequestPreview previewNextQuestion(@RequestBody DtrNextQuestionRequest request, HttpServletRequest http) {
        return service.previewNextQuestion(request, SessionUnlock.from(http));
    }

    @PostMapping("/response")
    public ResponseEntity<RunEnvelope<DtrDetail>> response(@RequestBody DtrResponseRequest request) {
        return RunResponses.of(service.runResponse(request, RunOrigin.manual()));
    }

    /**
     * On mock, the scenarios a tester can choose from. On live the list is empty and says why: a
     * scenario forced against the real service would produce a result that did not come from it.
     * On both, the outcomes and element names a tester can declare as an expectation.
     */
    @GetMapping("/scenarios")
    public ScenarioList scenarios() {
        List<OutcomeOption> outcomes = OutcomeOption.listOf(DtrOutcome.values(), RunOutcome.values());
        if (!epa.mock()) {
            return new ScenarioList(false, "Scenarios exist on the mock profile only. On live every answer comes from the service.",
                    List.of(), List.of(), outcomes, DtrCodes.EXPECTATION_ELEMENTS);
        }
        ScenarioRepository.Loaded loaded = scenarios.load(MockOnyxDtrClient.SCENARIOS);
        // The notes name what the scenario directory holds and the tool did not read as a scenario, so a QA author learns of it here.
        return new ScenarioList(true, null, loaded.scenarios().stream().map(Scenario::summary).toList(), loaded.problems(), outcomes,
                DtrCodes.EXPECTATION_ELEMENTS, null, null, loaded.notes());
    }
}
