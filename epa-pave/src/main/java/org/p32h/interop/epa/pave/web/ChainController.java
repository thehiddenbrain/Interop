package org.p32h.interop.epa.pave.web;

import java.util.List;
import org.p32h.interop.epa.pave.model.chain.ChainView;
import org.p32h.interop.epa.pave.service.chain.ChainService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Chains read back from the ledger. A chain is started by sending a run with the chain id "new"
 * and continued by sending the id that run returned, so there is nothing to create here.
 */
@RestController
@RequestMapping("/api/chains")
public class ChainController {

    private final ChainService service;

    public ChainController(ChainService service) {
        this.service = service;
    }

    /** The chains with a run in the date range, most recent first, without their runs. */
    public record ChainList(List<ChainView> chains) {
    }

    @GetMapping
    public ChainList list(@RequestParam(name = "from", required = false) String from,
                          @RequestParam(name = "to", required = false) String to,
                          @RequestParam(name = "limit", required = false) String limit) {
        return new ChainList(service.list(Params.date("from", from), Params.date("to", to), Params.integer("limit", limit)));
    }

    @GetMapping("/{chainId}")
    public ChainView view(@PathVariable("chainId") String chainId) {
        return service.view(chainId);
    }
}
