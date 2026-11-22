package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Says what this tool is and what it is pointed at: name, version, port, mode, target, the active
 * environment, the configuration epoch, whether a write would be sent for this browser session,
 * where it listens, and which build runs. Every tab polls it, so it reads state and does nothing else.
 */
@RestController
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
                gateway.writeAccess(SessionUnlock.from(http)), bindAddress(), bindNote(bindAddress()), build());
    }

    /** The running build, read from what it runs from on every poll, so a rebuild under it is said within one look. */
    private ModeView.Build build() {
        java.time.Instant built = ClassesOnDisk.builtAt();
        return new ModeView.Build(epa.version(), built == null ? null : built.toString(), ClassesOnDisk.changedSinceStart());
    }

    /** The address the server listens on as configured, or null when none is, and it listens on every address. */
    private String bindAddress() {
        String configured = environment.getProperty("server.address");
        return Texts.present(configured) ? configured.strip() : null;
    }

    /**
     * Whether a service on another machine can call the notification receiver, as far as this tool
     * can know it: it knows where it listens, and nothing of the network between it and a vendor.
     * The address is read as written and never looked up, so a name costs no network call.
     */
    static String bindNote(String address) {
        if (address == null || WILDCARD.contains(address.toLowerCase(Locale.ROOT))) {
            return "This tool listens on every network address of this machine. Whether a service on another machine, a vendor's "
                    + "included, can reach its notification receiver depends on the network between them, which this tool cannot see.";
        }
        if (loopback(address)) {
            return "This tool listens on this machine only (" + address + "), so nothing on another machine, a vendor's service "
                    + "included, can call its notification receiver. To let a service call it, set server.address to an address that "
                    + "service can reach, and read the README section on the receiver first.";
        }
        return "This tool listens on the address " + address + " only. Whether a service on another machine, a vendor's included, can "
                + "reach its notification receiver there depends on the network between them, which this tool cannot see.";
    }

    private static final Set<String> WILDCARD = Set.of("0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0");

    private static boolean loopback(String address) {
        String lower = address.toLowerCase(Locale.ROOT);
        return lower.equals("localhost") || lower.startsWith("127.") || lower.equals("::1") || lower.equals("[::1]")
                || lower.equals("0:0:0:0:0:0:0:1");
    }

    /** The port the server is listening on, which differs from the configured one when that is 0. */
    private int port() {
        Integer listening = environment.getProperty("local.server.port", Integer.class);
        return listening != null ? listening : environment.getProperty("server.port", Integer.class, 8057);
    }
}
