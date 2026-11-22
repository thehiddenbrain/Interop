package org.p32h.interop.epa.pave.web;

import java.util.function.Supplier;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.pas.PasGuide;
import org.p32h.interop.epa.pave.service.pas.PasOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Service card's code lists, the attachment kinds and limits, and the PAS guide Submit
 * follows for the active environment. A controller of its own, so the screens can read the lists
 * without the PAS services, and the lists stay one source for the page and for the request.
 */
@RestController
public class PasOptionsController {

    private final Supplier<RequestShapes> shapes;

    @Autowired
    public PasOptionsController(OperationGateway gateway) {
        this(gateway::activeShapes);
    }

    /** @param shapes the active environment's request shapes, read on every request so a changed setting shows at once */
    PasOptionsController(Supplier<RequestShapes> shapes) {
        this.shapes = shapes;
    }

    @GetMapping("/api/pas/options")
    public PasOptions options() {
        return PasOptions.forGuide(PasGuide.of(shapes.get()));
    }
}
