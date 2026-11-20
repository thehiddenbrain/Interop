package org.p32h.interop.epa.pave.service.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.PostmanHeader;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.UrlForms;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.config.CallPlan.Refusal;
import org.p32h.interop.epa.pave.service.config.CallPlan.Source;
import org.p32h.interop.epa.pave.service.config.CallPlan.Target;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The gateway: where each operation goes under the stored configuration, and whether it may go.
 *
 * <p>It is the one bean of its kind on both profiles, and the only production code that mints a
 * {@link PreparedCall} or a {@link ProdUnlock}. Everything a call needs is worked out against one
 * {@link ConfigSnapshot}, as a {@link CallPlan}: the target, the method, the url, the headers and
 * the guardrail decision. {@link #prepare} then turns the plan into a call or into the exception
 * that fits, and the Config tab shows the same plan, so a screen can never promise something a
 * send would refuse.
 *
 * <h4>The guardrail</h4>
 * It is decided on what would actually be sent. A call is a write when any of these is a write for
 * the method that would go out: the operation the caller named, the operation the collection
 * request is mapped to, and every operation the request's own name or its url reads as, the url
 * being read both as a template and as resolved. A write goes to a production environment only
 * when the configuration floor allows it and the caller holds an unlock this gateway minted for
 * that environment in the current epoch. The unlock is honoured by identity: an object that merely
 * carries the right name and epoch is not one.
 *
 * <p>Nothing here logs a url, a header, a variable value or an identifier.
 */
@Component
public class ConfigStoreGateway implements OperationGateway {

    private static final Logger log = LoggerFactory.getLogger(ConfigStoreGateway.class);

    /** The operations that address one subscription, so an id the caller names must end up in the url. */
    private static final Set<EpaOperation> SUBSCRIPTION_BY_ID = EnumSet.of(EpaOperation.SUBSCRIPTION_GET,
            EpaOperation.SUBSCRIPTION_UPDATE, EpaOperation.SUBSCRIPTION_DELETE);

    /** Headers by which a request asks the far side to act with a method other than the one it is sent with. */
    private static final Set<String> METHOD_OVERRIDES = Set.of("x-http-method-override", "x-http-method", "x-method-override");

    private static final String ID = "id";
    private static final String ID_TOKEN = "/{" + ID + "}";
    private static final String DISCOVERY_MARKER = "/cds-services";

    /** A collection request together with the collection it belongs to. */
    private record Located(StoredCollection collection, StoredCollection.Mapped request) {
    }

    private final ConfigStore store;
    private final OnyxProperties onyx;
    private final LookupProperties lookup;
    private final EpaProperties epa;
    private final Clock clock;
    private final VariableResolver resolver;

    /** The unlocks minted in {@link #mintedEpoch}, held by identity. Guarded by itself. */
    private final Set<ProdUnlock> minted = Collections.newSetFromMap(new IdentityHashMap<>());
    private long mintedEpoch = Long.MIN_VALUE;

    @Autowired
    public ConfigStoreGateway(ConfigStore store, OnyxProperties onyx, LookupProperties lookup, EpaProperties epa, Clock clock) {
        this(store, onyx, lookup, epa, clock, VariableResolver.forProcess(clock));
    }

    /** For tests, which pass a resolver that does not read the real operating system environment. */
    public ConfigStoreGateway(ConfigStore store, OnyxProperties onyx, LookupProperties lookup, EpaProperties epa, Clock clock,
                              VariableResolver resolver) {
        this.store = store;
        this.onyx = onyx;
        this.lookup = lookup;
        this.epa = epa;
        this.clock = clock;
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------------------------------
    // The contract
    // ------------------------------------------------------------------------------------------

    @Override
    public PreparedCall prepare(CallIntent intent) {
        if (!intent.operation().outbound()) {
            throw new IllegalArgumentException(intent.operation().name() + " never leaves the tool, so there is no call to prepare for it.");
        }
        ConfigSnapshot snapshot = store.snapshot();
        // Checked before anything else is decided: the caller's screen showed one configuration,
        // and whatever would be worked out below belongs to another.
        if (intent.expectedEpoch() != null && intent.expectedEpoch() != snapshot.epoch()) {
            throw ConfigurationChangedException.sinceTheScreenWasDrawn();
        }
        CallPlan plan = plan(snapshot, intent);
        // Whatever the environment and the method: a server in front of a service may decode an
        // encoded separator before it reads the path, and this tool cannot know whether it does.
        // Asked first, so that the refusal names the value that hid something.
        String encoded = encodedIn(plan);
        if (encoded != null) {
            throw encodedSeparator(plan, encoded);
        }
        if (plan.refusal() != null) {
            throw new ProdWriteBlockedException(intent.operation(), plan.environment(), plan.refusal().reason(), plan.refusal().action(),
                    plan.url(), plan.method(), plan.target().requestName());
        }
        // On the mock profile nothing is sent, so a target that is missing or incomplete is not an
        // error: the call says what was and was not resolved. Anything that is not mock is live.
        if (!epa.mock()) {
            if (!plan.url().configured()) {
                throw new EndpointNotConfiguredException(intent.operation(), plan.target().absent());
            }
            if (!plan.url().resolved()) {
                throw new UnresolvedVariablesException(intent.operation(), plan.url().unresolved(), plan.environment().name(),
                        plan.url().template(), plan.reasons(), plan.filled() == null ? List.of() : plan.filled().missingTokens());
            }
        }
        if (plan.originByRunValue()) {
            List<String> carriers = secretCarriers(plan);
            if (!carriers.isEmpty()) {
                throw secretElsewhere(plan, carriers);
            }
        }
        Function<String, Resolution> resolve = plan.originByRunValue() ? text -> {
            Resolution resolved = plan.resolver().apply(text);
            if (holdsSecret(resolved)) {
                throw secretElsewhere(plan, List.of("its body"));
            }
            return resolved;
        } : plan.resolver();
        long epoch = snapshot.epoch();
        // A write the unlock let through belongs to that unlock as well as to the configuration.
        // Once the unlock is no longer held the call answers an epoch that is never current, so the
        // sending layer refuses it, and a relock does not have to move the epoch everyone else is pinned to.
        ProdUnlock authorisedBy = plan.write() && holds(intent.unlock(), snapshot) ? intent.unlock() : null;
        LongSupplier belongsTo = authorisedBy == null ? () -> epoch : () -> {
            if (stillHeld(authorisedBy, epoch)) {
                return epoch;
            }
            // What changed is the lock, not the configuration, and the sending layer is told so in
            // those words. A change of configuration that also dropped the unlock is said as such.
            if (store.epoch() == epoch) {
                throw new ConfigurationChangedException("Production writes were locked again before this write was sent, so it was not "
                        + "sent. Unlock them on the Config tab by typing the environment name, then send it again.");
            }
            return Long.MIN_VALUE;
        };
        return new IssuedCall(intent.operation(), plan.method(), plan.url(), plan.resolvedHeaders(), plan.environment(),
                plan.target().requestName(), plan.target().scripted(), belongsTo, template -> shownAsWritten(resolve.apply(template).text(), template),
                plan.target().savedBody());
    }

    /**
     * What of the call carries a secret of the configuration, in words: a header whose value holds a
     * secret or is a credential by its own name, or the url. Empty when nothing does.
     */
    private static List<String> secretCarriers(CallPlan plan) {
        List<String> carriers = new ArrayList<>();
        for (CallPlan.Header header : plan.headers()) {
            String name = header.header().name();
            boolean credential = Secrets.credentialHeader(name) && Texts.present(header.header().value());
            if (credential || holdsSecret(header.how())) {
                carriers.add("its header " + ConfigStore.oneLine(name));
            }
        }
        if (holdsSecret(plan.urlVariables())) {
            carriers.add("its url");
        }
        return carriers;
    }

    private static boolean holdsSecret(Resolution resolution) {
        return resolution != null && resolution.pieces().stream().anyMatch(piece -> piece.kind() == Resolution.Kind.SECRET);
    }

    /** The refusal of a call that a value typed for the run points away from the configuration while it carries a secret. */
    private static BadInputException secretElsewhere(CallPlan plan, List<String> carriers) {
        // The scheme, host and port as shown, with any secret in them masked. Never the path or the query.
        UrlNormal.Destination destination = UrlNormal.destination(plan.url().display() == null ? plan.url().template() : plan.url().display());
        String where = destination == null ? "an address this tool cannot read"
                : new UrlNormal.Destination(destination.scheme(), destination.host(), destination.port(), "").shown();
        return new BadInputException(plan.intent().operation().label() + " was not sent: a value typed for this run points it at " + where
                + ", which is not the address the configuration names, and it carries a secret of the configuration in "
                + String.join(" and in ", carriers) + ". A secret is sent only to the address it was configured for.",
                "Nothing was sent. Send it without the value typed for this run that changes the address, or upload an environment "
                        + "whose values point there, with its own credentials, and activate it.");
    }

    /** The refusal of a call whose path holds an encoded separator. It names what supplied it, never the value. */
    private static BadInputException encodedSeparator(CallPlan plan, String encoded) {
        String what = encoded.startsWith("%") ? "an encoded separator (" + encoded + ")" : encoded;
        return new BadInputException(plan.intent().operation().label() + " was not sent: the path of its url holds " + what + ", which "
                + "came from " + encodedFrom(plan) + ". A server in front of the service can decode it before it reads the path, and "
                + "then the request addresses something other than what this tool judged it to be.",
                "Nothing was sent. Remove the encoded slash, backslash, semicolon or dot segment from that value. A value that needs "
                        + "such a character belongs in the query, after the question mark, where it is sent as it is.");
    }

    /**
     * The encoded separator in the path of a complete url as it would be sent, or null when there
     * is none. The url is in its normal form, which has already decoded an escaped dot segment and
     * judged it as the dot segment it is, so what is left is what a server could still decode.
     */
    private static String encodedIn(CallPlan plan) {
        return plan.filled() == null || !plan.filled().complete() ? null : UrlNormal.encodedSeparator(plan.filled().send());
    }

    /** What put the encoded separator into the path, in words: a variable with its layer, a url part, or the url as written. */
    private static String encodedFrom(CallPlan plan) {
        List<String> found = new ArrayList<>();
        Resolution resolution = plan.urlVariables();
        if (resolution != null) {
            Map<String, VariableSource> sources = sources(resolution);
            for (Resolution.Piece piece : resolution.pieces()) {
                if (piece.name() != null && piece.send() != null && UrlNormal.encodedSeparator("/" + withoutQuery(piece.send())) != null) {
                    VariableSource source = sources.get(piece.name());
                    String named = "the variable \"" + ConfigStore.oneLine(piece.name()) + "\""
                            + (source == null ? "" : " (" + source.label().toLowerCase(Locale.ROOT) + ")");
                    if (!found.contains(named)) {
                        found.add(named);
                    }
                }
            }
        }
        new TreeMap<>(plan.intent().urlValues()).forEach((name, value) -> {
            if (Texts.present(value) && UrlNormal.encodedSeparator("/" + UrlForms.encode(value)) != null) {
                found.add("the url part {" + name + "}");
            }
        });
        if (!found.isEmpty()) {
            return String.join(" and ", found);
        }
        return plan.target().described() == null ? "the url as it is written" : plan.target().described();
    }

    /** The text before a question mark or a hash. A value can carry a query of its own, which is not judged. */
    private static String withoutQuery(String text) {
        int end = firstOf(text, "?#");
        return end < 0 ? text : text.substring(0, end);
    }

    /** The resolved text with its template as written, any credential typed into it masked. */
    private static ResolvedText shownAsWritten(ResolvedText resolved, String template) {
        return new ResolvedText(LiteralCredentials.inBody(template).shown(), resolved.display(), resolved.value(), resolved.variables(),
                resolved.unresolved());
    }

    @Override
    public ResolvedText resolve(String template, Map<String, String> runValues) {
        if (template == null) {
            return null;
        }
        // No collection layer: the text belongs to no request, so it belongs to no collection.
        return resolver.resolve(template, layers(store.snapshot(), runValues, null, Map.of())).text();
    }

    @Override
    public EnvironmentRef activeEnvironment() {
        return store.snapshot().activeRef();
    }

    @Override
    public WriteAccess writeAccess(ProdUnlock unlock) {
        ConfigSnapshot snapshot = store.snapshot();
        StoredEnvironment active = snapshot.active();
        boolean floor = store.allowProdWrites();
        if (active == null) {
            return epa.mock()
                    ? new WriteAccess(false, true, floor, "No environment is active. On the mock profile nothing is sent, so writes are allowed.")
                    : new WriteAccess(false, false, floor, "No environment is active, so writes are refused: this tool cannot tell "
                    + "whether a target is production. Upload an environment, state its kind and activate it.");
        }
        if (active.kind() != EnvironmentKind.PROD) {
            // Worded so that it is true on the mock profile too, where nothing is sent at all.
            return new WriteAccess(false, true, floor, "\"" + active.name() + "\" is a " + active.kind().wire()
                    + " environment, so writes are not blocked.");
        }
        if (!floor) {
            return new WriteAccess(true, false, false, "\"" + active.name() + "\" is production, and an owner has locked this installation "
                    + "against production writes (config-store.allow-prod-writes is false). No unlock is possible.");
        }
        if (holds(unlock, snapshot)) {
            return new WriteAccess(true, true, true, "Production writes are unlocked for this session on \"" + active.name()
                    + "\". Any change to the configuration locks them again.");
        }
        return new WriteAccess(true, false, true, "\"" + active.name() + "\" is production and writes are locked for this session. "
                + "Unlock them by typing the environment name.");
    }

    @Override
    public long epoch() {
        return store.epoch();
    }

    /**
     * Every url the configuration in force names for an operation of these protocols by itself: the
     * one each operation uses when no request is chosen, and the url of every collection request
     * mapped to such an operation, since a tester may choose any of them with no value typed for
     * the run.
     */
    @Override
    public List<String> configuredUrls(Set<Protocol> protocols) {
        ConfigSnapshot snapshot = store.snapshot();
        Set<String> urls = new LinkedHashSet<>();
        List<CallIntent> intents = new ArrayList<>();
        for (EpaOperation operation : EpaOperation.values()) {
            if (operation.outbound() && protocols.contains(operation.protocol())) {
                intents.add(CallIntent.of(operation));
            }
        }
        for (StoredCollection collection : snapshot.collections()) {
            for (StoredCollection.Mapped request : collection.requests()) {
                EpaOperation operation = request.effective();
                if (operation.outbound() && protocols.contains(operation.protocol())) {
                    intents.add(CallIntent.of(operation).withRequest(new RequestRef(collection.id(), request.request().id())));
                }
            }
        }
        for (CallIntent intent : intents) {
            try {
                CallPlan plan = plan(snapshot, intent);
                if (plan.filled() != null && Texts.present(plan.filled().shown())) {
                    urls.add(plan.filled().shown());
                }
            } catch (RuntimeException e) {
                // A request that cannot be planned names no url. Nothing about it is logged.
            }
        }
        return new ArrayList<>(urls);
    }

    // ------------------------------------------------------------------------------------------
    // The production unlock
    // ------------------------------------------------------------------------------------------

    /**
     * Mints an unlock for the active production environment. This is the only way one comes to
     * exist.
     *
     * @param typedEnvironmentName what the tester typed. It must equal the name of the active
     *                             production environment exactly: same capitals, same spaces
     * @throws ProdWriteBlockedException when the configuration floor forbids production writes
     * @throws BadInputException         when there is nothing to unlock
     * @throws ValidationException       when the name typed is not the active environment's name
     */
    public ProdUnlock unlock(String typedEnvironmentName) {
        ConfigSnapshot snapshot = store.snapshot();
        StoredEnvironment active = snapshot.active();
        if (active == null) {
            throw new BadInputException("There is nothing to unlock: no environment is active.",
                    "Only a production environment locks writes. Activate an environment on the Config tab first.");
        }
        if (active.kind() != EnvironmentKind.PROD) {
            throw new BadInputException("There is nothing to unlock: \"" + active.name() + "\" is a " + active.kind().wire()
                    + " environment, and writes to it are not locked.", "Only a production environment locks writes.");
        }
        if (!store.allowProdWrites()) {
            // The outcome and the environment name only.
            log.info("An unlock of production writes was refused on \"{}\": the configuration floor forbids them", ConfigStore.oneLine(active.name()));
            throw new ProdWriteBlockedException(null, active.ref(), "Production writes cannot be unlocked: an owner has locked this "
                    + "installation against them.", "No unlock is possible from this screen. config-store.allow-prod-writes is false, and "
                    + "only whoever owns this installation can change that, with a restart. To test without production, switch to a "
                    + "sandbox or uat environment.");
        }
        if (typedEnvironmentName == null || !typedEnvironmentName.equals(active.name())) {
            // The outcome only. What was typed is not logged.
            log.info("An unlock of production writes was refused on \"{}\": the name typed did not match", ConfigStore.oneLine(active.name()));
            throw ValidationException.of("environment", "The name typed is not the name of the active production environment. Type it "
                    + "exactly as the environment strip shows it, with the same capitals and spaces.");
        }
        GrantedUnlock unlock = new GrantedUnlock(active.name(), snapshot.epoch(), clock.instant());
        synchronized (minted) {
            forgetEarlierEpochs(snapshot.epoch());
            if (mintedEpoch != snapshot.epoch()) {
                // The configuration changed while the name was being checked. The unlock would
                // belong to a configuration that is already gone.
                throw new ConfigurationChangedException();
            }
            minted.add(unlock);
        }
        // The environment name only. This line is the record of when production was opened for writing.
        log.warn("Production writes were unlocked for one browser session on \"{}\"", ConfigStore.oneLine(active.name()));
        return unlock;
    }

    /**
     * Locks production writes again, for every session. Every unlock is forgotten. A call prepared
     * while writes were unlocked rests on its unlock, so it can no longer be sent. The epoch does not
     * move: nothing stored changed, and a suite or a screen pinned to the configuration has no
     * reason to stop or reload because a session locked writes.
     */
    public void relock() {
        boolean any;
        synchronized (minted) {
            forgetEarlierEpochs(store.epoch());
            any = !minted.isEmpty();
            minted.clear();
        }
        if (any) {
            // The outcome only.
            log.info("Production writes were locked again");
        }
    }

    /** True while the unlock is still one this gateway holds for the epoch it was used in. */
    private boolean stillHeld(ProdUnlock unlock, long epoch) {
        synchronized (minted) {
            forgetEarlierEpochs(store.epoch());
            return mintedEpoch == epoch && minted.contains(unlock);
        }
    }

    /** True when the unlock is one this gateway minted for the snapshot's epoch and its active production environment. */
    private boolean holds(ProdUnlock unlock, ConfigSnapshot snapshot) {
        StoredEnvironment active = snapshot.active();
        if (unlock == null || active == null || active.kind() != EnvironmentKind.PROD) {
            return false;
        }
        synchronized (minted) {
            forgetEarlierEpochs(snapshot.epoch());
            return mintedEpoch == snapshot.epoch() && minted.contains(unlock) && unlock.epoch() == snapshot.epoch()
                    && active.name().equals(unlock.environmentName());
        }
    }

    /**
     * Empties the registry when the configuration has moved on since its unlocks were minted.
     * Every use of the registry passes through here first, so an unlock from an earlier epoch is
     * never looked up, whichever thread changed the configuration.
     */
    private void forgetEarlierEpochs(long epoch) {
        if (epoch > mintedEpoch) {
            minted.clear();
            mintedEpoch = epoch;
        }
    }

    // ------------------------------------------------------------------------------------------
    // The plan
    // ------------------------------------------------------------------------------------------

    /**
     * Works out one intended call against a snapshot of the configuration without sending or
     * minting anything. For the Config tab, which describes many calls against one snapshot. It
     * fails only for what is wrong with the request itself: a request that does not exist, one
     * mapped to another operation, a url value with nowhere to go.
     */
    CallPlan describe(ConfigSnapshot snapshot, CallIntent intent) {
        if (!intent.operation().outbound()) {
            throw new IllegalArgumentException(intent.operation().name() + " never leaves the tool, so there is nothing to describe for it.");
        }
        return plan(snapshot, intent);
    }

    /**
     * Whether sending this collection request as the operation it stands for would count as a
     * write under the given snapshot. It is the judgement {@link #prepare} would make, not a second
     * opinion: the list of requests on the Config tab must not call something a read that a send
     * would block. A request that stands for an operation which never leaves the tool can only be
     * sent raw, so it is judged as a raw send.
     */
    boolean countsAsWrite(ConfigSnapshot snapshot, StoredCollection collection, StoredCollection.Mapped request) {
        EpaOperation sentAs = request.effective().outbound() ? request.effective() : EpaOperation.UNMAPPED;
        return plan(snapshot, CallIntent.of(sentAs).withRequest(new RequestRef(collection.id(), request.request().id()))).write();
    }

    private CallPlan plan(ConfigSnapshot snapshot, CallIntent intent) {
        EpaOperation operation = intent.operation();
        Target target = target(snapshot, intent);

        Resolution urlVariables = null;
        UrlForms.Filled filled = null;
        Set<String> tokens = Set.of();
        List<String> missingPlaceholders = List.of();
        Map<String, String> reasons = new LinkedHashMap<>();
        Map<String, String> bound = Map.of();
        ResolvedUrl url = null;
        boolean climbed = false;
        RunStart runStart = null;
        boolean originByRunValue = false;

        if (target.configured()) {
            // A credential typed into the url is marked as a secret before anything is resolved,
            // and the template is shown with it masked, everywhere.
            LiteralCredentials.Marked typed = LiteralCredentials.inUrl(target.resolved());
            String template = LiteralCredentials.inUrl(target.template()).shown();
            String toResolve = typed.template();
            List<VariableLayer> layers = typed.before(layers(snapshot, intent.runValues(), target.collection(), Map.of()));
            urlVariables = resolver.resolve(toResolve, layers);
            UrlForms.Skeleton skeleton = UrlForms.skeleton(urlVariables);
            boolean cutInsideAVariable = false;
            if (target.source() == Source.DERIVED) {
                // The hook url is cut after /cds-services. When the marker is written in the
                // template the template itself was cut. Otherwise it arrived inside a variable, and
                // what resolved is cut.
                skeleton = skeleton.upTo(DISCOVERY_MARKER);
                cutInsideAVariable = UrlForms.endOfSegment(template, DISCOVERY_MARKER) != template.length();
                if (skeleton == null) {
                    target = Target.none("No collection request is mapped to " + operation.label() + ", onyx.crd.url is not set, and "
                            + "the url of the hook request \"" + target.request().request().qualifiedName() + "\" shows no "
                            + DISCOVERY_MARKER + " to derive it from.");
                }
            }
            if (skeleton != null) {
                bound = bridge(intent, target, skeleton, template);
                if (!bound.isEmpty()) {
                    // The id is bound ahead of every layer and percent-encoded, as any value that
                    // fills a place in a url is.
                    Map<String, String> encoded = new LinkedHashMap<>();
                    bound.forEach((name, value) -> encoded.put(name, UrlForms.encode(value)));
                    urlVariables = resolver.resolve(toResolve, typed.before(layers(snapshot, intent.runValues(), target.collection(), encoded)));
                    skeleton = UrlForms.skeleton(urlVariables);
                    UrlForms.Segment after = skeleton.segmentAfter("Subscription");
                    if (after == null || !bound.containsKey(after.placeholder())) {
                        throw noPlaceFor(ID, template, target);
                    }
                }
                filled = skeleton.fill(intent.urlValues());
                runStart = runStart(snapshot, intent, target, typed, bound);
                originByRunValue = runStart != null && !authorityWritten(runStart.before())
                        && !sameOrigin(filled.send(), sentWithoutRunValues(snapshot, intent, target, typed, bound));
                // Judged and sent in one normal form, so that what is judged is what a server acts on.
                climbed = UrlNormal.of(filled.probe()).climbed();
                filled = normalised(filled);
                tokens = skeleton.tokenNames();
                // A variable that only matters beyond the cut is not needed by the cut url.
                boolean nothingMissing = cutInsideAVariable && !skeleton.hasMissing();
                List<String> unresolved = new ArrayList<>(nothingMissing ? List.of() : urlVariables.unresolved());
                List<VariableUse> variables = nothingMissing
                        ? urlVariables.variables().stream().filter(use -> use.source() != VariableSource.UNRESOLVED).toList()
                        : urlVariables.variables();
                if (!nothingMissing) {
                    reasons.putAll(urlVariables.reasons());
                }
                missingPlaceholders = List.copyOf(unresolved);
                for (String token : filled.missingTokens()) {
                    if (!unresolved.contains(token)) {
                        unresolved.add(token);
                    }
                    reasons.putIfAbsent(token, "The url has a {" + token + "} part, which is filled from the identifier the call is "
                            + "for, and no identifier was given.");
                }
                url = new ResolvedUrl(template, filled.display(), filled.send(), variables, unresolved, target.described());
            }
        }
        if (url == null) {
            url = ResolvedUrl.notConfigured(target.absent());
            urlVariables = null;
        }
        String method = method(intent, target);

        // Text that belongs to the call is resolved with the id bound as it was given, not encoded.
        List<VariableLayer> plainLayers = layers(snapshot, intent.runValues(), target.collection(), bound);
        // The request's own body, as the Config tab hands it out, has a placeholder where a
        // credential was typed into it. That placeholder is supplied here, from the request.
        List<VariableLayer> callLayers = target.source() == Source.REQUEST
                ? LiteralCredentials.inBody(target.request().request().body()).before(plainLayers) : plainLayers;
        // A credential typed into a body is marked as a secret where the body is read, so the wire
        // view and every preview mask it as they mask a secret variable. A header is masked by its
        // own name instead, as it always was.
        Function<String, Resolution> callResolver = text -> {
            LiteralCredentials.Marked typed = LiteralCredentials.inBody(text);
            return resolver.resolve(typed.template(), typed.before(callLayers));
        };
        List<CallPlan.Header> headers = new ArrayList<>();
        for (PostmanHeader header : target.headers()) {
            String template = Texts.orElse(header.value(), "");
            Resolution how = resolver.resolve(template, plainLayers);
            // A header that still holds a placeholder is carried as unresolved. It is neither
            // dropped nor sent empty: the sending layer refuses the call and names what is missing.
            headers.add(new CallPlan.Header(how.complete() ? new ResolvedHeader(header.name(), shownValue(header.name(), how), how.send())
                    : ResolvedHeader.unresolved(header.name(), how.unresolved()), template, how));
        }

        String because = writeBecause(intent, target, method, url, filled, headers);
        boolean write = operation.write() || because != null;
        Refusal refusal = write ? guard(snapshot, intent, target, because, filled, new Origin(urlVariables, climbed, runStart)) : null;
        return new CallPlan(snapshot, intent, target, method, url, filled, tokens, missingPlaceholders, urlVariables, reasons, headers, write,
                because, refusal, callResolver, originByRunValue);
    }

    // ------------------------------------------------------------------------------------------
    // Where the call goes
    // ------------------------------------------------------------------------------------------

    /** The advice for a request chosen from a list that has changed since: nobody typed its id, so checking the id helps nobody. */
    private static final String CHOOSE_AGAIN = "Read the list of collections again, for example by reloading the page, and choose the request "
            + "from it as it is now.";

    private Target target(ConfigSnapshot snapshot, CallIntent intent) {
        EpaOperation operation = intent.operation();
        RequestRef named = intent.request();
        if (named != null) {
            StoredCollection collection = snapshot.collection(named.collectionId());
            if (collection == null) {
                throw new NotFoundException("The collection this request was chosen from is not uploaded. Reload and choose again.", CHOOSE_AGAIN);
            }
            StoredCollection.Mapped request = collection.request(named.requestId());
            if (request == null) {
                throw new NotFoundException("The collection \"" + collection.name() + "\" has no request with the id that was chosen. "
                        + "Reload and choose again.", CHOOSE_AGAIN);
            }
            // A read button can never be pointed at a write request: the request must stand for
            // the operation it is used for. Only a raw send may name any request.
            if (operation != EpaOperation.UNMAPPED && request.effective() != operation) {
                throw new BadInputException("The request \"" + request.request().name() + "\" is mapped to " + request.effective().label()
                        + ", so it cannot be sent as " + operation.label() + ".", "Choose a request that is mapped to "
                        + operation.label() + ", or correct the mapping on the Config tab.");
            }
            return requestTarget(new Located(collection, request));
        }
        if (operation != EpaOperation.UNMAPPED) {
            Located mapped = firstMapped(snapshot, operation, false);
            if (mapped != null) {
                return requestTarget(mapped);
            }
        }
        Target fallback = fallback(intent);
        if (fallback != null) {
            return fallback;
        }
        if (operation == EpaOperation.CRD_DISCOVERY) {
            Target derived = derivedDiscovery(snapshot);
            if (derived != null) {
                return derived;
            }
        }
        String property = fallbackProperty(operation);
        if (property == null) {
            return Target.none("An unmapped request has no target of its own. It is sent by naming the request.");
        }
        // A request that is a sample of a failure is mapped, and is not what a call uses unasked.
        String mapped = firstMapped(snapshot, operation, true) == null ? "No collection request is mapped to " + operation.label()
                : "The only collection requests mapped to " + operation.label() + " are samples of an error, which are used only "
                + "when one is chosen";
        return Target.none(operation == EpaOperation.CRD_DISCOVERY
                ? mapped + ", " + property + " is not set, and no CRD hook request is mapped to derive it from."
                : mapped + ", and " + property + " is not set.");
    }

    /**
     * The first request mapped to the operation, in collection order. A request whose name contains
     * "error" is a sample of a failure and is passed over unless asked for.
     */
    private static Located firstMapped(ConfigSnapshot snapshot, EpaOperation operation, boolean errorSamplesToo) {
        for (StoredCollection collection : snapshot.collections()) {
            for (StoredCollection.Mapped request : collection.requests()) {
                boolean errorSample = request.request().name().toLowerCase(Locale.ROOT).contains("error");
                if (request.effective() == operation && (errorSamplesToo || !errorSample)) {
                    return new Located(collection, request);
                }
            }
        }
        return null;
    }

    private static Target requestTarget(Located located) {
        PostmanRequest request = located.request().request();
        String described = "the request \"" + request.qualifiedName() + "\" of the collection \"" + located.collection().name() + "\"";
        if (Texts.blank(request.url())) {
            return new Target(Source.REQUEST, located.collection(), located.request(), null, null, request.sentHeaders(), described,
                    "The request \"" + request.qualifiedName() + "\" of the collection \"" + located.collection().name() + "\" has no url.");
        }
        return new Target(Source.REQUEST, located.collection(), located.request(), request.url(), request.url(), request.sentHeaders(),
                described, null);
    }

    /** The url from application.yml for an operation with no mapped request, or null when that is not set either. */
    private Target fallback(CallIntent intent) {
        EpaOperation operation = intent.operation();
        String property = fallbackProperty(operation);
        String base = property == null ? null : Texts.trimToNull(fallbackBase(operation));
        if (base == null) {
            return null;
        }
        if (operation.protocol() == Protocol.LOOKUP) {
            // A lookup: the configured url is the whole template, and its configured headers go with it.
            LookupProperties.Endpoint endpoint = operation == EpaOperation.MEMBER_LOOKUP ? lookup.member() : lookup.provider();
            List<PostmanHeader> headers = new ArrayList<>();
            // Sorted, so the order does not depend on how the properties happened to be bound.
            new TreeMap<>(endpoint.headers()).forEach((name, value) -> headers.add(new PostmanHeader(name, value, false)));
            return new Target(Source.FALLBACK, null, null, base, base, headers, "application.yml: " + property, null);
        }
        String template = base.replaceAll("/+$", "") + operation.fallbackPath();
        String described = "application.yml: " + property + " followed by " + operation.fallbackPath();
        if (operation == EpaOperation.SUBSCRIPTION_GET && !Texts.present(intent.urlValues().get(ID)) && template.endsWith(ID_TOKEN)) {
            // With no id this operation lists. The template still shows where an id would go.
            return new Target(Source.FALLBACK, null, null, template, template.substring(0, template.length() - ID_TOKEN.length()),
                    List.of(), described + ". No id was given, so the trailing " + ID_TOKEN + " is left off and this lists subscriptions",
                    null);
        }
        return new Target(Source.FALLBACK, null, null, template, template, List.of(), described, null);
    }

    private static String fallbackProperty(EpaOperation operation) {
        if (operation.fallbackPath() == null && operation.protocol() != Protocol.LOOKUP) {
            return null;
        }
        return switch (operation.protocol()) {
            case CRD -> "onyx.crd.url";
            case DTR -> "onyx.dtr.url";
            case PAS -> "onyx.pas.url";
            case SUBSCRIPTION -> "onyx.subscription.url";
            case LOOKUP -> operation == EpaOperation.MEMBER_LOOKUP ? "lookup.member.url" : "lookup.provider.url";
            case RAW -> null;
        };
    }

    private String fallbackBase(EpaOperation operation) {
        return switch (operation.protocol()) {
            case CRD -> onyx.crd().url();
            case DTR -> onyx.dtr().url();
            case PAS -> onyx.pas().url();
            case SUBSCRIPTION -> onyx.subscription().url();
            case LOOKUP -> operation == EpaOperation.MEMBER_LOOKUP ? lookup.member().url() : lookup.provider().url();
            case RAW -> null;
        };
    }

    /**
     * Discovery lives beside the hooks, so with nothing else configured its url is taken from a
     * mapped hook request, cut after {@code /cds-services}. None of that request's headers go with
     * it, and it is not reported as that request: the call is one this tool put together.
     */
    private static Target derivedDiscovery(ConfigSnapshot snapshot) {
        for (boolean errorSamplesToo : new boolean[] {false, true}) {
            for (EpaOperation hook : List.of(EpaOperation.CRD_ORDER_SIGN, EpaOperation.CRD_ORDER_SELECT)) {
                Located found = firstMapped(snapshot, hook, errorSamplesToo);
                if (found == null || Texts.blank(found.request().request().url())) {
                    continue;
                }
                PostmanRequest request = found.request().request();
                String template = request.url();
                int end = UrlForms.endOfSegment(template, DISCOVERY_MARKER);
                if (end >= 0) {
                    template = template.substring(0, end);
                }
                return new Target(Source.DERIVED, found.collection(), found.request(), template, template, List.of(),
                        "the url of the request \"" + request.qualifiedName() + "\" of the collection \"" + found.collection().name()
                                + "\", cut after " + DISCOVERY_MARKER, null);
            }
        }
        return null;
    }

    /**
     * The form in which a resolved header value is shown. A header that is a credential by its own
     * name shows its scheme at most, whatever its value was built from: masking by variable name
     * does not cover a credential typed literally into a collection or into application.yml.
     */
    private static String shownValue(String headerName, Resolution how) {
        return Secrets.credentialHeader(headerName) ? Secrets.redactCredential(headerName, how.send()) : how.display();
    }

    /**
     * The method of the collection request, or the operation's own method for a url this tool
     * built. The caller's method is honoured only for a raw send, so naming a read operation with a
     * method that changes state changes nothing.
     */
    private static String method(CallIntent intent, Target target) {
        if (intent.operation() == EpaOperation.UNMAPPED && Texts.present(intent.method())) {
            return intent.method().strip().toUpperCase(Locale.ROOT);
        }
        if (target.source() == Source.REQUEST) {
            return target.request().request().method();
        }
        return intent.operation().defaultMethod();
    }

    // ------------------------------------------------------------------------------------------
    // The url
    // ------------------------------------------------------------------------------------------

    private List<VariableLayer> layers(ConfigSnapshot snapshot, Map<String, String> runValues, StoredCollection collection,
                                       Map<String, String> bound) {
        List<VariableLayer> layers = new ArrayList<>();
        if (!bound.isEmpty()) {
            layers.add(VariableLayer.bound("the identifier this call names", bound));
        }
        layers.add(VariableLayer.runValues(runValues));
        StoredEnvironment active = snapshot.active();
        if (active != null) {
            layers.add(VariableLayer.environment(active.name(), active.content().inForce()));
        }
        // Only the collection the request came from. With two collections uploaded, a variable of
        // the same name in the other one must not be picked up.
        if (collection != null) {
            layers.add(VariableLayer.collection(collection.name(), collection.content().inForce()));
        }
        layers.add(VariableLayer.applicationYml(onyx.variables()));
        return layers;
    }

    /**
     * Makes sure no value the caller supplied for the url is ignored. A value with a token of its
     * name is fine. For the three operations that address one subscription, an id with no token is
     * bound to the placeholder that holds the id's place in the url. Anything else is refused,
     * because sending the call would address something other than what the caller named.
     *
     * @return the placeholder to bind and the id to bind to it, or an empty map
     */
    private static Map<String, String> bridge(CallIntent intent, Target target, UrlForms.Skeleton skeleton, String template) {
        Map<String, String> bound = new LinkedHashMap<>();
        Set<String> tokens = skeleton.tokenNames();
        for (Map.Entry<String, String> supplied : new TreeMap<>(intent.urlValues()).entrySet()) {
            String name = supplied.getKey();
            String value = supplied.getValue();
            if (!Texts.present(value) || tokens.contains(name)) {
                continue;
            }
            if (ID.equals(name) && SUBSCRIPTION_BY_ID.contains(intent.operation())) {
                UrlForms.Segment segment = skeleton.segmentAfter("Subscription");
                if (segment != null && segment.placeholder() != null) {
                    bound.put(segment.placeholder(), value);
                    continue;
                }
                if (segment != null && segment.readable()) {
                    if (segment.text().equals(value) || segment.text().equals(UrlForms.encode(value))) {
                        // The url already addresses exactly the subscription the caller named.
                        continue;
                    }
                    throw new BadInputException("The url of " + target.described() + " addresses the subscription \"" + segment.text()
                            + "\", and this call is for the subscription \"" + value + "\".", "Nothing was sent. Use a request whose "
                            + "url takes the id as {id}, as :id or as a placeholder, or correct the url in the collection and upload "
                            + "it again.");
                }
            }
            if (skeleton.hasMissing()) {
                // Part of the url has no value yet, and the place for this value may be in that part.
                continue;
            }
            throw noPlaceFor(name, template, target);
        }
        return bound;
    }

    private static BadInputException noPlaceFor(String name, String template, Target target) {
        // The name of the value and the url template. Never the value: for a lookup it is a member ID.
        return new BadInputException("This call supplies a value for \"" + name + "\", and the url it would go to has no {" + name
                + "} or :" + name + " to put it in. The url comes from " + target.described() + " and reads " + template + ".",
                "Nothing was sent. Sending it would address something other than what was asked for. Give the url a {" + name
                        + "} where the value belongs.");
    }

    // ------------------------------------------------------------------------------------------
    // The guardrail
    // ------------------------------------------------------------------------------------------

    /**
     * Why the call counts as a write although the operation the caller named is not one, or null
     * when nothing makes it one. Every route by which a write could be dressed as a read ends here.
     */
    private static String writeBecause(CallIntent intent, Target target, String method, ResolvedUrl url, UrlForms.Filled filled,
                                       List<CallPlan.Header> headers) {
        if (intent.operation().write()) {
            return null;
        }
        String because = judgedByMethod(intent, target, method, url, filled);
        if (because != null) {
            return because;
        }
        // A header that asks the far side to act with another method is part of what is sent, so
        // the call is judged again with the method it asks for. A GET that says "treat me as a
        // DELETE" is a delete to a server that honours the header.
        for (CallPlan.Header header : headers) {
            String name = header.header().name() == null ? "" : header.header().name().strip();
            if (!METHOD_OVERRIDES.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!header.header().resolved()) {
                return "its header " + name + " asks the far side to act with another method, and that method has no value yet";
            }
            String asked = header.header().value().strip().toUpperCase(Locale.ROOT);
            if (!asked.isEmpty() && judgedByMethod(intent, target, asked, url, filled) != null) {
                // The header's value is quoted only when it is a plain method name.
                return "its header " + name + " asks the far side to act on it as " + (asked.matches("[A-Z]{1,12}") ? asked : "another method");
            }
        }
        return null;
    }

    /** The judgement for one method: the one that would be sent, or one a header asks the far side to use in its place. */
    private static String judgedByMethod(CallIntent intent, Target target, String method, ResolvedUrl url, UrlForms.Filled filled) {
        EpaOperation operation = intent.operation();
        if (operation.writesWith(method)) {
            return operation == EpaOperation.UNMAPPED
                    ? "a request this tool cannot map may change something when it is sent with " + method
                    : "it would be sent with " + method + ", which is not the method of " + operation.label() + " and can change something";
        }
        String name = null;
        if (target.source() == Source.REQUEST) {
            StoredCollection.Mapped request = target.request();
            name = request.request().name();
            if (request.effective().writesWith(method)) {
                return "the request \"" + name + "\" is mapped to " + request.effective().label();
            }
        }
        // The request's own name, its url as written, and its url as it resolved. A url such as
        // {{baseUrl}}/Claim/{{op}} says nothing until its variables are filled in.
        Set<EpaOperation> signals = new LinkedHashSet<>(OperationDetector.signals(name, method, url.template()));
        signals.addAll(OperationDetector.signals(null, method, UrlNormal.text(url.template())));
        if (filled != null) {
            signals.addAll(OperationDetector.signals(null, method, filled.probe()));
        }
        String subject = name == null ? "the url" : "the name or the url of the request \"" + name + "\"";
        for (EpaOperation signal : signals) {
            if (signal.writesWith(method)) {
                return signal == EpaOperation.UNMAPPED || !signal.outbound()
                        ? subject + " reads as something this tool cannot vouch for, and it would be sent with " + method
                        : subject + " reads as " + signal.label();
            }
        }
        // A collection request that nothing can be read from is an unknown, and an operator's
        // correction is not evidence of what it does. It is judged as an unmapped request.
        if (target.source() == Source.REQUEST && signals.isEmpty() && EpaOperation.UNMAPPED.writesWith(method)) {
            return "nothing in " + subject + " says what it does, and it would be sent with " + method;
        }
        // A name is something its author typed, and says nothing about where the request goes. A
        // request called an inquiry and addressed to the base of a FHIR server is a transaction to
        // that server, whatever it is called. So when the method can change something, the url as
        // it will be sent has to read as the read it is named for. This is asked only of a url
        // that is complete: one with a placeholder still in it is never sent, and saying "blocked"
        // there would hide the real problem, which is the missing value.
        // The url as it will be sent is asked on its own as well, in its normal form: a value that
        // puts a question mark or dot segments into it can leave the read in the template while the
        // server is sent a create.
        if (filled != null && filled.complete() && EpaOperation.UNMAPPED.writesWith(method)
                && OperationDetector.signals(null, method, filled.probe()).isEmpty()) {
            if (target.source() == Source.REQUEST && OperationDetector.signals(null, method, url.template()).isEmpty()
                    && OperationDetector.signals(null, method, UrlNormal.text(url.template())).isEmpty()) {
                return "only the name of the request \"" + name + "\" says what it does, its url says nothing, and it would be sent with "
                        + method;
            }
            return "the url as it would be sent, with dot segments removed and the query set aside, does not read as "
                    + operation.label() + ", and it would be sent with " + method;
        }
        return null;
    }

    /**
     * What the gateway knows about where a url's text came from, for the guardrail.
     *
     * @param resolution how the placeholders of the url resolved, piece by piece. Null when there is no url
     * @param climbed    true when a {@code ..} in the url reached above the root of its path
     * @param runStart   where the first value typed for this run starts in the url, or null when none is in it
     */
    private record Origin(Resolution resolution, boolean climbed, RunStart runStart) {
    }

    /**
     * Where the first value typed for this run starts in a url.
     *
     * @param name   the variable the value was typed for
     * @param before the url as the configuration in force wrote it, up to that value. It can hold
     *               a secret, so it is compared and never shown
     */
    private record RunStart(String name, String before) {

        @Override
        public String toString() {
            return "RunStart[" + name + "]";
        }
    }

    /** Stands in for every value typed for the run, so that where the first one starts is known exactly. */
    private static final char RUN_MARK = '\u0001';

    /**
     * Resolves the url again with each value typed for this run replaced by a mark, and returns the
     * text the configuration in force wrote before the first mark. A value typed for the run that
     * sits inside an environment value, or a value typed for the run that refers to another
     * variable, is found the same way, because the mark replaces the value wholesale wherever the
     * resolver meets it. Null when no value typed for the run is in the url or the url is not
     * complete, which is judged elsewhere.
     */
    private RunStart runStart(ConfigSnapshot snapshot, CallIntent intent, Target target, LiteralCredentials.Marked typed,
                              Map<String, String> bound) {
        RunStart typedForTheRun = runValueStart(snapshot, intent, target, typed, bound);
        RunStart urlPart = urlPartStart(snapshot, intent, target, typed, bound);
        if (typedForTheRun == null || urlPart == null) {
            return typedForTheRun == null ? urlPart : typedForTheRun;
        }
        return urlPart.before().length() < typedForTheRun.before().length() ? urlPart : typedForTheRun;
    }

    /** Stands in for the value of one single-brace url part: unreserved characters only, so encoding leaves it as it is. */
    private static final String PART_MARK = "Zq7PaveUrlPartMarkZq7";

    /**
     * Where the first single-brace url part given a value for this call starts, cut back to the
     * slash before it. Such a value is encoded into its one place and cannot change the scheme,
     * host or port, but a value of {@code ..} would still climb out of the path the configuration
     * wrote before it, so it is held to the same rule as a value typed for the run. Null when no
     * part is given a value or the url is not complete.
     */
    private RunStart urlPartStart(ConfigSnapshot snapshot, CallIntent intent, Target target, LiteralCredentials.Marked typed,
                                  Map<String, String> bound) {
        Map<String, String> marks = new LinkedHashMap<>();
        new TreeMap<>(intent.urlValues()).forEach((name, value) -> {
            if (Texts.present(value)) {
                marks.put(name, PART_MARK + marks.size() + PART_MARK);
            }
        });
        if (marks.isEmpty()) {
            return null;
        }
        Map<String, String> encoded = new LinkedHashMap<>();
        bound.forEach((name, value) -> encoded.put(name, UrlForms.encode(value)));
        Resolution resolved = resolver.resolve(typed.template(), typed.before(layers(snapshot, intent.runValues(), target.collection(), encoded)));
        UrlForms.Skeleton skeleton = UrlForms.skeleton(resolved);
        if (target.source() == Source.DERIVED) {
            skeleton = skeleton.upTo(DISCOVERY_MARKER);
            if (skeleton == null) {
                return null;
            }
        }
        String send = skeleton.fill(marks).send();
        if (send == null) {
            return null;
        }
        String first = null;
        int at = -1;
        for (Map.Entry<String, String> mark : marks.entrySet()) {
            int found = send.indexOf(mark.getValue());
            if (found >= 0 && (at < 0 || found < at)) {
                at = found;
                first = mark.getKey();
            }
        }
        if (at < 0) {
            return null;
        }
        int slash = send.lastIndexOf('/', at);
        return new RunStart("the url part {" + first + "}", send.substring(0, slash < 0 ? at : slash + 1));
    }

    private RunStart runValueStart(ConfigSnapshot snapshot, CallIntent intent, Target target, LiteralCredentials.Marked typed,
                                   Map<String, String> bound) {
        Map<String, String> marks = new LinkedHashMap<>();
        Map<String, String> encoded = new LinkedHashMap<>();
        bound.forEach((name, value) -> encoded.put(name, UrlForms.encode(value)));
        List<VariableLayer> configuration = layers(snapshot, Map.of(), target.collection(), encoded);
        // A blank value is not a value: the resolver passes over it to the next layer, so it marks
        // nothing. A value that repeats what the configuration in force holds for that name changes
        // nothing either, so a screen that fills its fields from the environment moves no write.
        intent.runValues().forEach((name, value) -> {
            if (Texts.present(value) && !value.equals(resolver.resolve("{{" + name + "}}", configuration).send())) {
                marks.put(name, String.valueOf(RUN_MARK));
            }
        });
        if (marks.isEmpty()) {
            return null;
        }
        Resolution marked = resolver.resolve(typed.template(), typed.before(layers(snapshot, marks, target.collection(), encoded)));
        UrlForms.Skeleton skeleton = UrlForms.skeleton(marked);
        if (target.source() == Source.DERIVED) {
            skeleton = skeleton.upTo(DISCOVERY_MARKER);
            if (skeleton == null) {
                return null;
            }
        }
        String send = skeleton.fill(intent.urlValues()).send();
        int at = send == null ? -1 : send.indexOf(RUN_MARK);
        if (at < 0) {
            return null;
        }
        String name = marked.pieces().stream().filter(piece -> piece.send() != null && piece.send().indexOf(RUN_MARK) >= 0)
                .map(Resolution.Piece::name).filter(java.util.Objects::nonNull).findFirst().orElse("a value typed for this run");
        return new RunStart(name, send.substring(0, at));
    }

    /** The url as it would be sent with no value typed for this run, in its normal form, or null when it is not complete without them. */
    private String sentWithoutRunValues(ConfigSnapshot snapshot, CallIntent intent, Target target, LiteralCredentials.Marked typed,
                                        Map<String, String> bound) {
        Map<String, String> encoded = new LinkedHashMap<>();
        bound.forEach((name, value) -> encoded.put(name, UrlForms.encode(value)));
        Resolution resolved = resolver.resolve(typed.template(), typed.before(layers(snapshot, Map.of(), target.collection(), encoded)));
        UrlForms.Skeleton skeleton = UrlForms.skeleton(resolved);
        if (target.source() == Source.DERIVED) {
            skeleton = skeleton.upTo(DISCOVERY_MARKER);
            if (skeleton == null) {
                return null;
            }
        }
        return UrlNormal.text(skeleton.fill(intent.urlValues()).send());
    }

    /**
     * True when both urls go to the same scheme, host and port, written alike. A value typed for this
     * run chose the scheme, host or port when it starts before the configuration has written all
     * three and the url it gives is not the same in this way as the url the configuration gives by itself.
     */
    private static boolean sameOrigin(String sent, String withoutRunValues) {
        UrlNormal.Destination moved = UrlNormal.destination(sent);
        UrlNormal.Destination own = UrlNormal.destination(withoutRunValues);
        return moved != null && own != null && java.util.Objects.equals(moved.scheme(), own.scheme()) && moved.host().equals(own.host())
                && moved.port() == own.port();
    }

    /** True when the text holds a scheme, a host and the character that ends the authority. */
    private static boolean authorityWritten(String before) {
        int scheme = before.indexOf("://");
        if (scheme <= 0) {
            return false;
        }
        for (int at = scheme + 3; at < before.length(); at++) {
            if ("/?#".indexOf(before.charAt(at)) >= 0) {
                return at > scheme + 3;
            }
        }
        return false;
    }

    /** A url in the one normal form in which it is judged, shown and sent. */
    private static UrlForms.Filled normalised(UrlForms.Filled filled) {
        return new UrlForms.Filled(UrlNormal.text(filled.send()), UrlNormal.text(filled.display()), UrlNormal.text(filled.shown()),
                UrlNormal.text(filled.probe()), filled.missingTokens());
    }

    private Refusal guard(ConfigSnapshot snapshot, CallIntent intent, Target target, String because, UrlForms.Filled filled, Origin origin) {
        StoredEnvironment active = snapshot.active();
        // A request mapped to nothing is named as itself: "Unmapped request" is the label of no operation.
        String what = intent.operation() == EpaOperation.UNMAPPED && target.requestName() != null
                ? "The request \"" + target.requestName() + "\", sent as written,"
                : intent.operation().label() + (target.requestName() == null ? "" : " with the request \"" + target.requestName() + "\"");
        String why = because == null ? "" : " It counts as a write because " + because + ".";
        boolean complete = filled != null && filled.complete();

        if (complete && origin.climbed()) {
            return new Refusal("Blocked: its url climbs above the start of its path.", what + " is blocked: its url climbs above the start of its path with \"..\", so it would address "
                    + "something other than what it says." + why, "Nothing was sent. Correct the url, or the value that put \"..\" "
                    + "into it, so that it names the address directly.");
        }
        String movedBy = complete ? movedByRunValue(origin.runStart(), filled.send()) : null;
        if (movedBy != null) {
            // The name of the value. Never the value: it can be a member ID or a typed secret.
            return new Refusal("Blocked: a value typed for this run would change where it goes.", what + " is blocked: the value typed for this run for " + movedBy + " would change where it goes, "
                    + "its scheme, host, port or base path. A value typed for one run may fill a claim id, a topic, a path segment "
                    + "after the base or a query value, and never the address of a write." + why, "Nothing was sent. To send a write "
                    + "somewhere else, change the environment: upload one whose values point there, with its kind, and activate it.");
        }

        // The destination decides. An address that belongs to a production environment, stored
        // here or remembered after it was deleted, is production whatever is active and wherever
        // the url came from.
        UrlNormal.Destination destination = filled == null ? null : UrlNormal.destination(complete ? filled.send() : filled.probe());
        List<String> owners = snapshot.productionOwners(destination);
        boolean activeOwns = active != null && active.kind() == EnvironmentKind.PROD && owners.contains(active.name());
        // A write to the active production environment's own address is judged below, as every
        // write to the active production environment is.
        if (!owners.isEmpty() && !activeOwns) {
            String belongs = "the address it would go to belongs to the production environment" + (owners.size() > 1 ? "s " : " ")
                    + quotedAll(owners);
            if (!store.allowProdWrites()) {
                return new Refusal("Blocked: its address is a production address, and this installation is locked against production writes.", what + " is blocked: " + belongs + ", and an owner has locked this installation against production "
                        + "writes." + why, "An owner has locked this installation against production writes (config-store.allow-prod-writes "
                        + "is false), so no unlock is possible. Only whoever owns the installation can change that. To test without "
                        + "production, switch to a sandbox or uat environment.");
            }
            String activeNow = active == null ? "no environment is active" : "the active environment is \"" + active.name() + "\"";
            return new Refusal("Blocked: its address belongs to a production environment that is not the active one.", what + " is blocked: " + belongs + ", and " + activeNow + ". A write goes to a production address "
                    + "only from that production environment, active and unlocked." + why, "Nothing was sent. To write to "
                    + quotedAll(owners) + ", activate it on the Config tab and unlock writes there. A value typed for one run, "
                    + "application.yml or another environment cannot send a write to it.");
        }

        // An address this tool cannot read as the HTTP client reads it is never taken as "not
        // production": what the client would connect to is not known here.
        if (complete && (destination == null || !destination.readable())) {
            return unreadable(what, why, addressFrom(origin.resolution(), filled.send(), target));
        }

        if (active == null) {
            // On the mock profile nothing is sent, so the out-of-the-box install can run a write
            // with nothing configured. On live there is no kind to go by, and a fallback url cannot
            // be told from production.
            return epa.mock() ? null : new Refusal("Blocked: no environment is active, so this tool cannot tell whether the target is production.", what + " is blocked: no environment is active, so this tool cannot tell whether "
                    + "the target is production." + why, "Upload the environment for this target on the Config tab, state its kind and "
                    + "activate it. A write is sent only to an environment whose kind is known.");
        }
        if (active.kind() != EnvironmentKind.PROD) {
            // The kind belongs to the environment, and the url can come from somewhere else: a
            // value typed for this one run, or a literal in application.yml. The upload asks about
            // an environment that reads as production. This asks the same about the url a write
            // is about to go to, so that a production url cannot ride in under a sandbox's name.
            // The confirmation that the environment is not production covers its own addresses
            // only: a url application.yml contributed to is judged whole, and what a value typed
            // for this run put into it is judged on its own.
            if (!complete) {
                return null;
            }
            boolean ownAddress = active.acknowledgedNotProd() && target.source() == Source.REQUEST
                    && !contributed(origin.resolution(), VariableSource.APPLICATION_YML);
            boolean saysProd = ownAddress ? ProductionSigns.saysProd(UrlNormal.decodedForReading(runText(origin.resolution())))
                    : readsAsProduction(filled.send());
            if (saysProd) {
                String confirmed = active.acknowledgedNotProd()
                        ? ", and the confirmation that \"" + active.name() + "\" is not production covers that environment's own addresses "
                        + "only, while part of this url came from " + (ownAddress ? "a value typed for this run" : "application.yml") + "."
                        : ", and the active environment \"" + active.name() + "\" is declared as " + active.kind().wire() + " and has not "
                        + "been confirmed as not production.";
                return new Refusal("Blocked: its url contains \"prod\", and this tool cannot tell whether it is production.", what + " is blocked: the url it would go to contains \"prod\"" + confirmed + " This tool cannot tell "
                        + "whether that url is production." + why,
                        "If the target is production, upload its environment with the kind prod, activate it and unlock writes "
                                + "there. If it is not, upload the environment \"" + active.name() + "\" again with the confirmation "
                                + "that it is not production, with the url in its own variables, then send again. A url typed for one "
                                + "run, or set in application.yml, is not enough to send a write to something that reads as production.");
            }
            return null;
        }
        if (!store.allowProdWrites()) {
            return new Refusal("Blocked: this installation is locked against production writes.", what + " is blocked: \"" + active.name() + "\" is a production environment, and an owner has locked "
                    + "this installation against production writes." + why, "An owner has locked this installation against production "
                    + "writes (config-store.allow-prod-writes is false), so no unlock is possible. Only whoever owns the installation "
                    + "can change that. To test without production, switch to a sandbox or uat environment.");
        }
        if (holds(intent.unlock(), snapshot)) {
            return null;
        }
        return locked(snapshot, intent, active, what, why);
    }

    /** The refusal of a write whose address cannot be read. It names where the address came from and quotes none of it. */
    static Refusal unreadable(String what, String why, String from) {
        return new Refusal("Blocked: the address it would go to could not be read.", what + " is blocked: the scheme, host and port "
                + "it would go to could not be read the way the HTTP client reads them, so this tool cannot tell whether that address "
                + "is production. The address comes from " + from + "." + why, "Nothing was sent. Write the address as "
                + "scheme://host:port/path, with the host as a name or as four decimal numbers and a port of at most 65535, then send "
                + "it again.");
    }

    /**
     * Where the scheme, host and port of a url came from, in words: the variables whose values
     * form them, each with its layer, or the request or setting that wrote them literally. Names
     * and layers only, never a value.
     */
    static String addressFrom(Resolution resolution, String sent, Target target) {
        List<String> names = new ArrayList<>();
        if (resolution != null) {
            Map<String, VariableSource> sources = sources(resolution);
            String text = resolution.send() == null ? sent : resolution.send();
            int authorityEnd = authorityEnd(text == null ? "" : text);
            int at = 0;
            for (Resolution.Piece piece : resolution.pieces()) {
                if (at > authorityEnd) {
                    break;
                }
                if (piece.name() != null) {
                    VariableSource source = sources.get(piece.name());
                    String named = "the variable \"" + ConfigStore.oneLine(piece.name()) + "\""
                            + (source == null ? "" : " (" + source.label().toLowerCase(Locale.ROOT) + ")");
                    if (!names.contains(named)) {
                        names.add(named);
                    }
                }
                at += piece.send() == null ? 0 : piece.send().length();
            }
        }
        if (!names.isEmpty()) {
            return String.join(" and ", names);
        }
        return target == null || target.described() == null ? "the url as it is written" : target.described();
    }

    /** Where the scheme and the authority of a url end: the first slash, question mark or hash after {@code ://}, or the end. */
    private static int authorityEnd(String url) {
        int scheme = url.indexOf("://");
        int at = scheme < 0 ? 0 : scheme + 3;
        while (at < url.length() && "/?#".indexOf(url.charAt(at)) < 0) {
            at++;
        }
        return at;
    }

    /**
     * The refusal of a write to the active production environment by a call that holds no unlock
     * for it. It says what is true for the caller: a replay and a suite never carry the unlock, so
     * telling them to unlock would send the tester round in a circle.
     */
    private Refusal locked(ConfigSnapshot snapshot, CallIntent intent, StoredEnvironment active, String what, String why) {
        RunOrigin origin = intent.origin();
        String carrier = origin == null ? null : Texts.present(origin.replayOfRunId()) ? "a replay"
                : Texts.present(origin.suiteRunId()) ? "a suite" : null;
        if (carrier != null && intent.unlock() == null) {
            return new Refusal("Blocked: a replay or a suite never carries the production unlock.", what + " is blocked: \"" + active.name() + "\" is a production environment, and " + carrier + " never "
                    + "carries the production unlock, so its writes to production are always blocked." + why, "Nothing was sent, and "
                    + "unlocking does not change this. To send this write to production, send it on its own from " + tab(intent) + ", with "
                    + "writes unlocked there, or switch to an environment that is not production.");
        }
        if (intent.unlock() == null && unlockedElsewhere(snapshot, active)) {
            return new Refusal("Blocked: this session holds no production unlock.", what + " is blocked: \"" + active.name() + "\" is a production environment, and this call carries no "
                    + "production unlock. Writes are unlocked in a browser session, and a replay, a suite and every other browser "
                    + "session never carry that unlock." + why, "Nothing was sent. To send this write to production, send it on its own "
                    + "from " + tab(intent) + " of the browser session where writes are unlocked.");
        }
        if (intent.unlock() != null) {
            return new Refusal("Blocked: production writes are locked for this session.", what + " is blocked: \"" + active.name() + "\" is a production environment and production writes are "
                    + "locked for this session. An earlier unlock no longer applies, because the configuration has changed or writes were "
                    + "locked again since." + why, "To send it, unlock production writes on the Config tab by typing the environment name. "
                    + "To test without production, switch to a sandbox or uat environment.");
        }
        if (origin != null) {
            // Nobody holds an unlock, and the call said it came from a screen, so the advice for a
            // replay or a suite would only send a tester at a raw send looking for a PAS tab.
            return new Refusal("Blocked: production writes are locked for this session.", what + " is blocked: \"" + active.name()
                    + "\" is a production environment and production writes are locked for this session." + why, "To send it, unlock "
                    + "production writes on the Config tab by typing the environment name, then send it again from " + tab(intent)
                    + ". To test without production, switch to a sandbox or uat environment.");
        }
        // Nobody holds an unlock, so writes are locked for every session. Without an origin the
        // caller can be a screen, a replay or a suite, so the step named is the one that helps each.
        return new Refusal("Blocked: production writes are locked for this session.", what + " is blocked: \"" + active.name() + "\" is a production environment and production writes are "
                + "locked for this session." + why, "To send it from a screen, unlock production writes on the Config tab by typing the "
                + "environment name, then send it again. A replay or a suite never carries the unlock, so unlocking does not let its "
                + "writes to production through: send such a write on its own from " + tab(intent) + ". To test without production, switch to "
                + "a sandbox or uat environment.");
    }

    /**
     * Where a person sends this call by itself: the tab of its protocol, or Config, where a
     * collection request is sent as written. A noun phrase and nothing more, because each refusal
     * goes on after it ("of the browser session where ...", ", with writes unlocked there").
     */
    private static String tab(CallIntent intent) {
        Protocol protocol = intent.operation() == null ? null : intent.operation().protocol();
        if (protocol == null || protocol == Protocol.RAW) {
            return "the Config tab";
        }
        return "the " + (protocol == Protocol.SUBSCRIPTION ? Protocol.PAS : protocol).name() + " tab";
    }

    /** True when some session holds an unlock for the active production environment in the snapshot's epoch. */
    private boolean unlockedElsewhere(ConfigSnapshot snapshot, StoredEnvironment active) {
        synchronized (minted) {
            forgetEarlierEpochs(snapshot.epoch());
            return mintedEpoch == snapshot.epoch() && minted.stream().anyMatch(unlock -> active.name().equals(unlock.environmentName()));
        }
    }

    private static String quotedAll(List<String> names) {
        return String.join(" and ", names.stream().map(name -> "\"" + ConfigStore.oneLine(name) + "\"").toList());
    }

    /** True when the url reads as production: the word "prod" in it once escapes are decoded, or in its host. */
    static boolean readsAsProduction(String url) {
        UrlNormal.Destination destination = UrlNormal.destination(url);
        return ProductionSigns.saysProd(UrlNormal.decodedForReading(url))
                || (destination != null && ProductionSigns.saysProd(UrlNormal.decodedForReading(destination.host())));
    }

    /** The layer each variable of a resolution came from. */
    private static Map<String, VariableSource> sources(Resolution resolution) {
        Map<String, VariableSource> sources = new LinkedHashMap<>();
        if (resolution != null) {
            resolution.variables().forEach(use -> sources.putIfAbsent(use.name(), use.source()));
        }
        return sources;
    }

    private static boolean contributed(Resolution resolution, VariableSource source) {
        return resolution != null && resolution.variables().stream().anyMatch(use -> use.source() == source);
    }

    /** The text of the url that values typed for this run put there, each piece followed by a space. */
    private static String runText(Resolution resolution) {
        Map<String, VariableSource> sources = sources(resolution);
        StringBuilder text = new StringBuilder();
        if (resolution != null) {
            for (Resolution.Piece piece : resolution.pieces()) {
                if (piece.name() != null && sources.get(piece.name()) == VariableSource.RUN_VALUE && piece.send() != null) {
                    text.append(piece.send()).append(' ');
                }
            }
        }
        return text.toString();
    }

    /**
     * The name of the run value that would move a write, or null when none does. The scheme, host,
     * port and base path must be written by the configuration in force on its own: a run value may
     * start only after a slash that follows the host, or in the query or the fragment. The url as it
     * will be sent, in its normal form, must still start with what the configuration wrote, so a
     * value cannot climb back out of the place it was given with {@code ..}.
     */
    private static String movedByRunValue(RunStart start, String sent) {
        if (start == null) {
            return null;
        }
        String before = start.before();
        int scheme = before.indexOf("://");
        if (scheme <= 0) {
            return start.name();
        }
        int pathStart = scheme + 3;
        while (pathStart < before.length() && "/?#".indexOf(before.charAt(pathStart)) < 0) {
            pathStart++;
        }
        String rest = before.substring(pathStart);
        int query = firstOf(rest, "?#");
        if (query < 0 && !rest.endsWith("/")) {
            return start.name();
        }
        String fixed = query >= 0 ? before.substring(0, pathStart + query) : before;
        String base = UrlNormal.text(fixed);
        String normal = UrlNormal.text(sent);
        return normal != null && normal.startsWith(base) ? null : start.name();
    }

    private static int firstOf(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
