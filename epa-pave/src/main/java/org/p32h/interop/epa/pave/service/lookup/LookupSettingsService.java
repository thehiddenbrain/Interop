package org.p32h.interop.epa.pave.service.lookup;

import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.HeldSecretsSource;
import org.p32h.interop.epa.pave.service.config.LookupSettingsStore;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings.Header;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings.SignIn;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSaved;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSettingsInput;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupsView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The lookups saved on screen (R-11): read, saved and removed for the Members and Providers pages,
 * handed to the live lookup clients, and held as secrets.
 *
 * <p>Every secret of a saved lookup is held ({@link HeldSecretsSource}), so a service that repeats
 * one, in a lookup's answer or in any other call's, is masked in the wire view, whatever call it
 * answers. Nothing here logs a setting.
 */
@Service
public class LookupSettingsService implements HeldSecretsSource, SavedLookups {

    private static final Logger log = LoggerFactory.getLogger(LookupSettingsService.class);

    private final LookupSettingsStore store;
    private final LookupProperties lookup;
    private final java.util.function.Supplier<OperationGateway> gateway;

    /** The gateway is asked lazily: it asks this bean for held secrets, so neither may need the other built first. */
    @org.springframework.beans.factory.annotation.Autowired
    public LookupSettingsService(LookupSettingsStore store, LookupProperties lookup, ObjectProvider<OperationGateway> gateway) {
        this.store = store;
        this.lookup = lookup;
        this.gateway = gateway::getIfAvailable;
    }

    /** For tests that hold the gateway already. */
    public LookupSettingsService(LookupSettingsStore store, LookupProperties lookup, OperationGateway gateway) {
        this.store = store;
        this.lookup = lookup;
        this.gateway = () -> gateway;
    }

    /** Both lookups as the pages show them. */
    public LookupsView views() {
        LookupSettingsStore.Loaded loaded = store.read();
        return new LookupsView(view(LookupKind.MEMBER, loaded), view(LookupKind.PROVIDER, loaded));
    }

    private LookupSettingsViews.LookupSettingsView view(LookupKind kind, LookupSettingsStore.Loaded loaded) {
        LookupSettings saved = usable(kind, loaded);
        String problem = loaded.problem();
        if (problem == null && stored(kind, loaded) != null && saved == null) {
            problem = brokenRule(kind);
        }
        return LookupSettingsRules.view(kind, saved, problem, endpoint(kind));
    }

    /** Saves one lookup in place of application.yml's. Field errors are a 422 that names each field. */
    public LookupSaved save(LookupKind kind, LookupSettingsInput input) {
        if (input == null) {
            throw new ValidationException("There is nothing to save. Nothing was changed.", List.of(new FieldError("method",
                    "Give the method and the address.")));
        }
        LookupSettingsStore.Loaded loaded = store.read();
        LookupSettings before = stored(kind, loaded);
        List<FieldError> errors = LookupSettingsRules.check(kind, input, before);
        if (!errors.isEmpty()) {
            throw new ValidationException("The " + kind.word() + " lookup was not saved. Correct the fields named.", errors);
        }
        LookupSettings merged = LookupSettingsRules.merge(kind, input, before);
        store.write(kind == LookupKind.MEMBER ? merged : loaded.member(), kind == LookupKind.PROVIDER ? merged : loaded.provider());
        // The kind only: the settings can hold a key.
        log.info("The {} lookup was saved on screen", kind.word());
        return new LookupSaved(view(kind, store.read()), LookupSettingsRules.plainSecretWarnings(kind, input, store.file()));
    }

    /** Removes the saved lookup, so application.yml's applies again. */
    public LookupSaved remove(LookupKind kind) {
        LookupSettingsStore.Loaded loaded = store.read();
        if (stored(kind, loaded) != null || !loaded.readable()) {
            store.write(kind == LookupKind.MEMBER ? null : loaded.member(), kind == LookupKind.PROVIDER ? null : loaded.provider());
            log.info("The {} lookup saved on screen was removed", kind.word());
        }
        return new LookupSaved(view(kind, store.read()), List.of());
    }

    /**
     * The lookup saved for this kind, or null when none is, when lookups.json cannot be read, or
     * when what it holds breaks a rule a save keeps: in each case application.yml's lookup applies.
     */
    @Override
    public LookupSettings saved(LookupKind kind) {
        return usable(kind, store.read());
    }

    private LookupSettings usable(LookupKind kind, LookupSettingsStore.Loaded loaded) {
        LookupSettings stored = stored(kind, loaded);
        return stored != null && LookupSettingsRules.keepsTheRules(kind, stored) ? stored : null;
    }

    private static LookupSettings stored(LookupKind kind, LookupSettingsStore.Loaded loaded) {
        return kind == LookupKind.MEMBER ? loaded.member() : loaded.provider();
    }

    private String brokenRule(LookupKind kind) {
        return "The " + kind.word() + " lookup in " + store.file() + " breaks a rule this page keeps, so it is not used and application.yml's "
                + "lookup applies. Save it again on this page.";
    }

    private LookupProperties.Endpoint endpoint(LookupKind kind) {
        return kind == LookupKind.MEMBER ? lookup.member() : lookup.provider();
    }

    /**
     * Every secret of the saved lookups, each a value as typed or the value its reference resolves
     * to now. A reference that does not resolve holds nothing yet; it is asked again next time.
     */
    @Override
    public SentSecrets held() {
        LookupSettingsStore.Loaded loaded = store.read();
        SentSecrets.Builder found = SentSecrets.builder();
        for (LookupKind kind : LookupKind.values()) {
            LookupSettings saved = stored(kind, loaded);
            if (saved == null) {
                continue;
            }
            SignIn signIn = saved.signIn();
            if (signIn.apiKey() != null) {
                found.header(Texts.orElse(signIn.headerName(), "API key"), value(signIn.apiKey()));
            }
            if (signIn.bearerToken() != null) {
                found.header("Authorization", value(signIn.bearerToken()));
            }
            if (signIn.clientSecret() != null) {
                found.variable(kind.word() + "LookupClientSecret", value(signIn.clientSecret()));
            }
            for (Header header : saved.headers()) {
                found.header(header.name(), value(header.value()));
            }
        }
        return found.build();
    }

    private String value(String stored) {
        if (stored == null || !stored.contains("{{")) {
            return stored;
        }
        OperationGateway resolver = gateway.get();
        if (resolver == null) {
            return null;
        }
        ResolvedText resolved = resolver.resolve(stored, Map.of());
        return resolved != null && resolved.resolved() ? resolved.value() : null;
    }
}
