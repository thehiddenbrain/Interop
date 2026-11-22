package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.PaveStart;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.ModeSwitch;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Says what this tool is and what it is pointed at: name, version, port, mode, target, the active
 * environment, the configuration epoch, whether a write would be sent for this browser session,
 * where it listens, and which build runs. Every tab polls it, so its GET reads state and does
 * nothing else.
 *
 * <p>Its POST is the switch between mock and live made on screen: PAVE restarts itself in place on
 * the other profile ({@link ModeSwitch}). It sits behind the same guards as every other change,
 * which {@link ApiGuardFilter} applies to every request whatever its address.
 */
@RestController
@RequestMapping("/api/mode")
public class ModeController {

    private final EpaProperties epa;
    private final OperationGateway gateway;
    private final Environment environment;
    /** Present on live only: on mock nothing signs in, and the answer then carries no sign-in state. */
    private final ObjectProvider<OnyxTokenService> tokens;
    /** Null where the switch is not wired, as in a test of the GET alone: a POST is then refused. */
    private final ModeSwitch modeSwitch;
    private final JsonMapper mapper;
    /**
     * When this start of PAVE began, read once: a start by hand and a switch made on screen each make
     * a new context and so a new controller, and nothing else moves it (I-13).
     */
    private final String startedAt = PaveStart.startedAt().toString();

    /** Without a token service, as on mock. */
    public ModeController(EpaProperties epa, OperationGateway gateway, Environment environment) {
        this(epa, gateway, environment, null, null, null);
    }

    /** Without the switch. */
    public ModeController(EpaProperties epa, OperationGateway gateway, Environment environment, ObjectProvider<OnyxTokenService> tokens) {
        this(epa, gateway, environment, tokens, null, null);
    }

    @Autowired
    public ModeController(EpaProperties epa, OperationGateway gateway, Environment environment, ObjectProvider<OnyxTokenService> tokens,
                          ModeSwitch modeSwitch, JsonMapper mapper) {
        this.epa = epa;
        this.gateway = gateway;
        this.environment = environment;
        this.tokens = tokens;
        this.modeSwitch = modeSwitch;
        this.mapper = mapper;
    }

    @GetMapping
    public ModeView mode(HttpServletRequest http) {
        return new ModeView(epa.name(), epa.version(), port(), epa.mode(), epa.target(), gateway.activeEnvironment(), gateway.epoch(),
                gateway.writeAccess(SessionUnlock.from(http)), bindAddress(), bindNote(bindAddress()), build(), signIn(), startedAt,
                held(http), modeSwitch == null ? null : modeSwitch.switchingTo());
    }

    /**
     * What {@code POST /api/mode} takes.
     *
     * @param mode mock or live
     */
    public record ModeChange(String mode) {
    }

    /**
     * Switches PAVE to the other mode: answers 202 with what {@code GET /api/mode} answers now and
     * the mode it goes to, then closes and starts again on that mode. The answer is written and
     * sent before the restart begins, so the page has it whatever the close does afterwards. A
     * refusal (422 for a mode that is neither, 409 for anything in the way) changes nothing.
     */
    @PostMapping
    public void change(@RequestBody ModeChange body, HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (modeSwitch == null || mapper == null) {
            throw new org.p32h.interop.epa.pave.service.error.BusyException("This copy of PAVE cannot switch itself.",
                    "Stop it and start it on the other profile.");
        }
        String to = modeSwitch.claim(body == null ? null : body.mode());
        byte[] answer;
        try {
            answer = mapper.writeValueAsBytes(mode(http).switching(to));
        } catch (RuntimeException | Error e) {
            modeSwitch.release(to);
            throw e;
        }
        response.setStatus(HttpStatus.ACCEPTED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(answer.length);
        try {
            response.getOutputStream().write(answer);
            response.flushBuffer();
        } catch (IOException | RuntimeException e) {
            // The page that asked went away before it heard: the switch is not made behind its back.
            modeSwitch.release(to);
            throw e;
        }
        modeSwitch.begin(to);
    }

    /** What this browser session holds that a switch drops. Reads the session without making one. */
    private ModeView.Held held(HttpServletRequest http) {
        OnyxTokenService service = tokens == null ? null : tokens.getIfAvailable();
        return new ModeView.Held(SessionUnlock.from(http) != null, service != null && service.pastedHeld());
    }

    /**
     * The sign-in state under the active environment's own sign-in, read from the store on every
     * poll rather than from the last call, so a save shows at once. Asks no token endpoint: every
     * tab polls this. Null on mock.
     */
    private ModeView.SignIn signIn() {
        OnyxTokenService service = tokens == null ? null : tokens.getIfAvailable();
        if (service == null) {
            return null;
        }
        return ModeView.SignIn.of(service.state(gateway.activeSignIn()));
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
