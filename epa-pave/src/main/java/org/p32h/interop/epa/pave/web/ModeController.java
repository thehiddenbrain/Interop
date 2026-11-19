package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Says what this tool is and what it is pointed at: name, version, port, mode, target, the active
 * environment, the configuration epoch, and whether a write would be sent for this browser
 * session. Every tab polls it, so it reads state and does nothing else.
 *
 * <p>This class is not a Spring bean until the integrator registers it, for the reason given on
 * {@link CrdController}.
 */
@RequestMapping("/api/mode")
public class ModeController {

    private final EpaProperties epa;
    private final OperationGateway gateway;
    private final Environment environment;

    public ModeController(EpaProperties epa, OperationGateway gateway, Environment environment) {
        this.epa = epa;
        this.gateway = gateway;
        this.environment = environment;
    }

    @GetMapping
    public ModeView mode(HttpServletRequest http) {
        return new ModeView(epa.name(), epa.version(), port(), epa.mode(), epa.target(), gateway.activeEnvironment(), gateway.epoch(),
                gateway.writeAccess(SessionUnlock.from(http)));
    }

    /** The port the server is listening on, which differs from the configured one when that is 0. */
    private int port() {
        Integer listening = environment.getProperty("local.server.port", Integer.class);
        return listening != null ? listening : environment.getProperty("server.port", Integer.class, 8057);
    }
}
