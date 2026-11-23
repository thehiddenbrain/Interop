package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.run.BeforeSending;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a Workbench step says before it is sent (D-321). Nothing is sent and nothing is recorded:
 * the request the step would follow is read from the stored collection, by presence only.
 */
@RestController
public class BeforeSendingController {

    private final OperationGateway gateway;

    public BeforeSendingController(OperationGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/api/workbench/before-sending")
    public BeforeSending.Said beforeSending(@RequestBody BeforeSending.Ask ask) {
        return BeforeSending.ask(gateway, ask);
    }
}
