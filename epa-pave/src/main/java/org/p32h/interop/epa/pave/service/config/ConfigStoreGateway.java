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
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.PostmanHeader;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.UrlForms;
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
        Function<String, Resolution> resolve = plan.resolver();
        return new IssuedCall(intent.operation(), plan.method(), plan.url(), plan.resolvedHeaders(), plan.environment(),
                plan.target().requestName(), plan.target().scripted(), snapshot.epoch(), template -> resolve.apply(template).text());
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
     * Locks production writes again, for every session. Every unlock is forgotten, and the epoch
     * moves on so that a call prepared while writes were unlocked can no longer be sent.
     */
    public void relock() {
        boolean any;
        synchronized (minted) {
            forgetEarlierEpochs(store.epoch());
            any = !minted.isEmpty();
            minted.clear();
        }
        // With no unlock held no prepared call can rest on one, so there is nothing to invalidate
        // and no reason to make every open tab reload.
        if (any) {
            store.advanceEpoch();
            log.info("Production writes were locked again");
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

        if (target.configured()) {
            String template = target.template();
            String toResolve = target.resolved();
            List<VariableLayer> layers = layers(snapshot, intent.runValues(), target.collection(), Map.of());
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
                    urlVariables = resolver.resolve(toResolve, layers(snapshot, intent.runValues(), target.collection(), encoded));
                    skeleton = UrlForms.skeleton(urlVariables);
                    UrlForms.Segment after = skeleton.segmentAfter("Subscription");
                    if (after == null || !bound.containsKey(after.placeholder())) {
                        throw noPlaceFor(ID, template, target);
                    }
                }
                filled = skeleton.fill(intent.urlValues());
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
        List<VariableLayer> callLayers = layers(snapshot, intent.runValues(), target.collection(), bound);
        Function<String, Resolution> callResolver = text -> resolver.resolve(text, callLayers);
        List<CallPlan.Header> headers = new ArrayList<>();
        for (PostmanHeader header : target.headers()) {
            String template = Texts.orElse(header.value(), "");
            Resolution how = callResolver.apply(template);
            // A header that still holds a placeholder is carried as unresolved. It is neither
            // dropped nor sent empty: the sending layer refuses the call and names what is missing.
            headers.add(new CallPlan.Header(how.complete() ? new ResolvedHeader(header.name(), shownValue(header.name(), how), how.send())
                    : ResolvedHeader.unresolved(header.name(), how.unresolved()), template, how));
        }

        String because = writeBecause(intent, target, method, url, filled, headers);
        boolean write = operation.write() || because != null;
        Refusal refusal = write ? guard(snapshot, intent, target, because, filled) : null;
        return new CallPlan(snapshot, intent, target, method, url, filled, tokens, missingPlaceholders, urlVariables, reasons, headers, write,
                because, refusal, callResolver);
    }

    // ------------------------------------------------------------------------------------------
    // Where the call goes
    // ------------------------------------------------------------------------------------------

    private Target target(ConfigSnapshot snapshot, CallIntent intent) {
        EpaOperation operation = intent.operation();
        RequestRef named = intent.request();
        if (named != null) {
            StoredCollection collection = snapshot.collection(named.collectionId());
            if (collection == null) {
                throw new NotFoundException("The collection this request was chosen from is not uploaded. Reload and choose again.");
            }
            StoredCollection.Mapped request = collection.request(named.requestId());
            if (request == null) {
                throw new NotFoundException("The collection \"" + collection.name() + "\" has no request with the id that was chosen. "
                        + "Reload and choose again.");
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
        if (target.source() == Source.REQUEST && filled != null && filled.complete() && EpaOperation.UNMAPPED.writesWith(method)
                && OperationDetector.signals(null, method, url.template()).isEmpty()
                && OperationDetector.signals(null, method, filled.probe()).isEmpty()) {
            return "only the name of the request \"" + name + "\" says what it does, its url says nothing, and it would be sent with "
                    + method;
        }
        return null;
    }

    private Refusal guard(ConfigSnapshot snapshot, CallIntent intent, Target target, String because, UrlForms.Filled filled) {
        StoredEnvironment active = snapshot.active();
        String what = intent.operation().label() + (target.requestName() == null ? "" : " with the request \"" + target.requestName() + "\"");
        String why = because == null ? "" : " It counts as a write because " + because + ".";
        if (active == null) {
            // On the mock profile nothing is sent, so the out-of-the-box install can run a write
            // with nothing configured. On live there is no kind to go by, and a fallback url cannot
            // be told from production.
            return epa.mock() ? null : new Refusal(what + " is blocked: no environment is active, so this tool cannot tell whether "
                    + "the target is production." + why, "Upload the environment for this target on the Config tab, state its kind and "
                    + "activate it. A write is sent only to an environment whose kind is known.");
        }
        if (active.kind() != EnvironmentKind.PROD) {
            // The kind belongs to the environment, and the url can come from somewhere else: a
            // value typed for this one run, or a literal in application.yml. The upload asks about
            // an environment that reads as production. This asks the same about the url a write
            // is about to go to, so that a production url cannot ride in under a sandbox's name.
            if (!active.acknowledgedNotProd() && filled != null && filled.complete() && ProductionSigns.saysProd(filled.send())) {
                return new Refusal(what + " is blocked: the url it would go to contains \"prod\", and the active environment \""
                        + active.name() + "\" is declared as " + active.kind().wire() + " and has not been confirmed as not production. "
                        + "This tool cannot tell whether that url is production." + why,
                        "If the target is production, upload its environment with the kind prod, activate it and unlock writes "
                                + "there. If it is not, upload the environment \"" + active.name() + "\" again with the confirmation "
                                + "that it is not production, then send again. A url typed for one run, or set in application.yml, is "
                                + "not enough to send a write to something that reads as production.");
            }
            return null;
        }
        if (!store.allowProdWrites()) {
            return new Refusal(what + " is blocked: \"" + active.name() + "\" is a production environment, and an owner has locked "
                    + "this installation against production writes." + why, "An owner has locked this installation against production "
                    + "writes (config-store.allow-prod-writes is false), so no unlock is possible. Only whoever owns the installation "
                    + "can change that. To test without production, switch to a sandbox or uat environment.");
        }
        if (holds(intent.unlock(), snapshot)) {
            return null;
        }
        String stale = intent.unlock() == null ? "" : " An earlier unlock no longer applies, because the configuration has changed "
                + "or writes were locked again since.";
        return new Refusal(what + " is blocked: \"" + active.name() + "\" is a production environment and production writes are "
                + "locked for this session." + stale + why, "To send it, unlock production writes on the Config tab by typing the "
                + "environment name. To test without production, switch to a sandbox or uat environment.");
    }
}
