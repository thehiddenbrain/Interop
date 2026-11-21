package org.p32h.interop.epa.pave.testsupport;

import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A hand-written stand-in for the gateway, for tests of clients and protocol services.
 *
 * <p>It is not a model of the real gateway's rules: the guardrail, target selection and the
 * layered resolution order are tested against the real implementation. This fake does only what a
 * caller of the gateway needs: hand back a call for a configured url, substitute url tokens and
 * simple placeholders, record what it was asked, and fail on demand.
 */
public final class FakeGateway implements OperationGateway {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern TOKEN = Pattern.compile("(?<!\\{)\\{([A-Za-z][A-Za-z0-9_]*)}(?!})");

    private final Map<EpaOperation, String> urls = new EnumMap<>(EpaOperation.class);
    private final Map<EpaOperation, List<ResolvedHeader>> headers = new EnumMap<>(EpaOperation.class);
    private final Map<String, String> variables = new LinkedHashMap<>();
    private final List<CallIntent> intents = new ArrayList<>();
    private EnvironmentRef environment = new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX);
    private RuntimeException failure;
    private long epoch;
    private String requestName;
    private boolean scripted;

    /** A gateway with nothing configured, which is the out-of-the-box state on mock. */
    public static FakeGateway unconfigured() {
        FakeGateway gateway = new FakeGateway();
        gateway.environment = EnvironmentRef.none();
        return gateway;
    }

    public FakeGateway url(EpaOperation operation, String template) {
        urls.put(operation, template);
        return this;
    }

    public FakeGateway headers(EpaOperation operation, List<ResolvedHeader> value) {
        headers.put(operation, value);
        return this;
    }

    public FakeGateway variable(String name, String value) {
        variables.put(name, value);
        return this;
    }

    public FakeGateway environment(String name, EnvironmentKind kind) {
        this.environment = new EnvironmentRef(name, kind);
        return this;
    }

    /** Makes every later prepare throw the given exception, as the real gateway does when it refuses. */
    public FakeGateway failing(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    public FakeGateway fromRequest(String name, boolean hasScript) {
        this.requestName = name;
        this.scripted = hasScript;
        return this;
    }

    /** Moves the epoch on, as any change to the stored configuration does. */
    public FakeGateway changeConfiguration() {
        epoch++;
        return this;
    }

    public List<CallIntent> intents() {
        return intents;
    }

    public CallIntent lastIntent() {
        return intents.get(intents.size() - 1);
    }

    @Override
    public PreparedCall prepare(CallIntent intent) {
        intents.add(intent);
        if (failure != null) {
            throw failure;
        }
        if (intent.expectedEpoch() != null && intent.expectedEpoch() != epoch) {
            throw ConfigurationChangedException.sinceTheScreenWasDrawn();
        }
        String template = urls.get(intent.operation());
        ResolvedUrl url;
        if (template == null) {
            url = ResolvedUrl.notConfigured("not configured");
        } else {
            ResolvedText text = resolve(template, intent.runValues());
            List<String> unresolved = new ArrayList<>(text.unresolved());
            String value = text.value();
            String display = text.display();
            if (value != null) {
                value = substituteTokens(value, intent.urlValues(), unresolved);
                display = substituteTokens(display, intent.urlValues(), new ArrayList<>());
            }
            boolean complete = unresolved.isEmpty();
            url = new ResolvedUrl(template, complete ? display : null, complete ? value : null, text.variables(), unresolved, "test");
        }
        Map<String, String> runValues = intent.runValues();
        return TestPreparedCalls.at(epoch, body -> resolve(body, runValues), intent.operation(),
                intent.method() != null ? intent.method() : intent.operation().defaultMethod(), url,
                headers.getOrDefault(intent.operation(), List.of()), environment, requestName, scripted);
    }

    private static String substituteTokens(String text, Map<String, String> urlValues, List<String> unresolved) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = urlValues.get(matcher.group(1));
            if (value == null) {
                unresolved.add(matcher.group(1));
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Override
    public ResolvedText resolve(String template, Map<String, String> runValues) {
        if (template == null) {
            return null;
        }
        List<VariableUse> uses = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        StringBuilder display = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int last = 0;
        while (matcher.find()) {
            String literal = template.substring(last, matcher.start());
            value.append(literal);
            display.append(literal);
            String name = matcher.group(1);
            String found = runValues != null && runValues.get(name) != null ? runValues.get(name) : variables.get(name);
            VariableSource source = runValues != null && runValues.get(name) != null ? VariableSource.RUN_VALUE : VariableSource.ENVIRONMENT;
            if (found == null || found.isBlank()) {
                unresolved.add(name);
                uses.add(new VariableUse(name, VariableSource.UNRESOLVED, Secrets.looksSecret(name), null));
            } else {
                boolean secret = Secrets.looksSecret(name);
                value.append(found);
                display.append(secret ? Secrets.mask(name) : found);
                uses.add(new VariableUse(name, source, secret, secret ? Secrets.mask(name) : found));
            }
            last = matcher.end();
        }
        value.append(template.substring(last));
        display.append(template.substring(last));
        boolean complete = unresolved.isEmpty();
        return new ResolvedText(template, complete ? display.toString() : null, complete ? value.toString() : null, uses, unresolved);
    }

    @Override
    public EnvironmentRef activeEnvironment() {
        return environment;
    }

    @Override
    public WriteAccess writeAccess(ProdUnlock unlock) {
        boolean production = environment.production();
        return new WriteAccess(production, !production, false, production ? "Production writes are locked." : "Writes are allowed.");
    }

    @Override
    public long epoch() {
        return epoch;
    }
}
