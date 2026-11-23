package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
import java.util.Locale;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds a CDS Hooks request for order-sign or order-select from the form.
 *
 * <p>The mapping the specification fixes: the member becomes a Patient, the NPI becomes a
 * PractitionerRole and a Practitioner, and each procedure code becomes the code of its own
 * ServiceRequest in the draft orders bundle. The context carries that bundle, the patient id and
 * the user id. The resources themselves come from {@link CaseResources}, which DTR and PAS build
 * from as well, so the three protocols describe one case the same way.
 */
public final class CrdRequestBuilder {

    /**
     * The request body, what was left out of it, and the orders it carries.
     *
     * @param json   the request as pretty JSON
     * @param notes  one plain sentence for each thing left out or worked out
     * @param orders each order sent, with the procedure code on it, so an answer can be tied to its code
     * @param incomplete each element a CRD 2.0.1 profile requires that the request was sent without,
     *                   because nobody supplied it. Empty when nothing is missing
     */
    public record Built(String json, List<String> notes, List<SentOrder> orders, List<CaseResources.Required> incomplete) {

        public Built {
            notes = notes == null ? List.of() : List.copyOf(notes);
            orders = orders == null ? List.of() : List.copyOf(orders);
            incomplete = incomplete == null ? List.of() : List.copyOf(incomplete);
        }
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final CaseResources resources;

    /** @param codeList consulted for a code's system when the tester did not state one. May be null */
    public CrdRequestBuilder(ProcedureCodeDirectory codeList) {
        this(codeList, Clock.systemUTC());
    }

    /** @param clock when each order is written: CRD 2.0.1 requires authoredOn and nobody supplies it */
    public CrdRequestBuilder(ProcedureCodeDirectory codeList, Clock clock) {
        this.resources = new CaseResources(codeList, clock);
    }

    /**
     * @param hookInstance the unique id of this hook invocation
     * @param variables    resolves a placeholder name to its value, or to an unresolved result
     */
    public Built build(CrdRunRequest request, String hookInstance, Function<String, ResolvedText> variables) {
        return build(request, hookInstance, variables, RequestShapes.DEFAULTS);
    }

    /**
     * The request in the shape the vendor's sandbox accepted (R-37): the prefetch as five named
     * Bundles of type collection, a fullUrl on every entry, userId and every requester the
     * Practitioner, and context.draftOrders with or without its type as the shapes say.
     *
     * @param shapes the request shapes of the call, each with where its value came from
     */
    public Built build(CrdRunRequest request, String hookInstance, Function<String, ResolvedText> variables, RequestShapes shapes) {
        return build(request, hookInstance, variables, shapes, MemberIdRule.PACKED);
    }

    /**
     * As {@link #build(CrdRunRequest, String, Function, RequestShapes)}, with the member ID in the
     * form the call's member ID rule gives (R-10).
     *
     * @param rule the call's member ID rule; null reads as packed
     */
    public Built build(CrdRunRequest request, String hookInstance, Function<String, ResolvedText> variables, RequestShapes shapes,
                       MemberIdRule rule) {
        return build(request, hookInstance, variables, shapes, rule, null);
    }

    /**
     * As {@link #build(CrdRunRequest, String, Function, RequestShapes, MemberIdRule)}, with the
     * prefetch arranged as the collection's sample writes it when the shape Prefetch says so (D-309).
     *
     * @param sample the arrangement of the sample the call comes from, or null when it has none
     */
    public Built build(CrdRunRequest request, String hookInstance, Function<String, ResolvedText> variables, RequestShapes shapes,
                       MemberIdRule rule, PrefetchLayout sample) {
        List<String> notes = new ArrayList<>();
        List<SentOrder> orders = new ArrayList<>();
        List<CaseResources.Required> missing = new ArrayList<>();
        String hook = hook(request);
        MemberDetail member = request.member();
        ProviderDetail provider = request.provider();
        RequestShapes shaped = shapes == null ? RequestShapes.DEFAULTS : shapes;
        RequestShapes.Choice prefetchChoice = shaped.choice(RequestShape.CRD_PREFETCH);
        boolean asSample = RequestShape.AS_SAMPLE.equals(prefetchChoice.value());
        // The diagnosis follows the sample only where the request is arranged as the sample is (D-310).
        PrefetchLayout.Reason reason = asSample && sample != null ? sample.reason() : null;

        ObjectNode root = JSON.objectNode();
        root.put("hook", hook);
        root.put("hookInstance", hookInstance);
        String fhirServer = CaseResources.value(variables, CaseResources.VAR_FHIR_SERVER);
        boolean prefetchSent = !Boolean.FALSE.equals(request.includePrefetch());
        if (fhirServer != null) {
            root.put("fhirServer", fhirServer);
        } else if (prefetchSent) {
            // Without prefetch either, the note below says both at once, so the two notes never
            // send the service to a prefetch that was not sent.
            notes.add("No fhirServer was sent: the variable " + CaseResources.VAR_FHIR_SERVER + " is not set. This tool is not an "
                    + "EHR, so the service must work from the prefetch it is given.");
        }

        ObjectNode context = root.putObject("context");
        context.put("userId", CaseResources.requesterRef(provider));
        context.put("patientId", CaseResources.patientId(member));
        notes.add(CaseResources.patientIdNote(member, true));
        String practitionerNote = CaseResources.practitionerIdNote(provider, true);
        if (practitionerNote != null) {
            notes.add(practitionerNote);
        }

        ObjectNode bundle = JSON.objectNode();
        bundle.put("resourceType", "Bundle");
        boolean typeLeftOut = shaped.leftOut(RequestShape.CRD_DRAFT_ORDERS_TYPE);
        if (!typeLeftOut) {
            bundle.put("type", "collection");
        }
        ArrayNode entries = bundle.putArray("entry");
        List<ObjectNode> sentOrders = new ArrayList<>();
        ArrayNode selections = JSON.arrayNode();
        String authoredOn = resources.now();
        String hcpcs = CaseResources.orderHcpcsSystem(variables);
        boolean hcpcsSent = false;
        int position = 1;
        for (CodeInput code : request.procedureCodes()) {
            String id = CaseResources.serviceRequestId(position);
            ObjectNode order = resources.serviceRequest(id, code, member, provider, request.dateOfService(), authoredOn, hcpcs, notes);
            if (reason != null) {
                diagnosis(order, request.diagnosisCodes(), reason);
            }
            hcpcsSent |= !Texts.present(code.system()) && hcpcs.equals(JsonSafe.text(order.at("/code/coding/0/system")));
            CaseResources.entry(entries, order, fhirServer);
            sentOrders.add(order);
            selections.add(CaseResources.serviceRequestRef(position));
            orders.add(new SentOrder(CaseResources.serviceRequestRef(position), code.code().strip()));
            position++;
        }
        if (!orders.isEmpty()) {
            notes.add(CaseResources.authoredOnNote(authoredOn));
        }
        if (reason != null) {
            notes.add(diagnosisNote(request.diagnosisCodes(), reason));
        }
        if (hcpcsSent) {
            notes.add(CaseResources.hcpcsNote(hcpcs));
        }
        String quantities = CaseResources.quantityNote(request.procedureCodes());
        if (quantities != null) {
            notes.add(quantities);
        }
        if (CrdRunRequest.ORDER_SELECT.equals(hook) && !selections.isEmpty()) {
            context.set("selections", selections);
        }
        context.set("draftOrders", bundle);
        notes.add(draftOrdersNote(shaped.choice(RequestShape.CRD_DRAFT_ORDERS_TYPE)));

        if (prefetchSent && asSample && sample != null) {
            Pieces pieces = new Pieces(member, provider, rule, variables, notes, missing, sentOrders, sample);
            samplePrefetch(root.putObject("prefetch"), sample, pieces, fhirServer, prefetchChoice, notes);
        } else if (prefetchSent) {
            if (asSample) {
                notes.add("Prefetch: As the collection's sample, " + from(prefetchChoice) + ", but the request this call comes from "
                        + "writes no prefetch to follow, so the named Bundles below were sent.");
            } else if (RequestShapes.FROM_ENVIRONMENT.equals(prefetchChoice.from())) {
                notes.add("Prefetch: PAVE's named Bundles, from this environment.");
            }
            ObjectNode prefetch = root.putObject("prefetch");
            prefetch.set("patientBundle", collection(fhirServer, resources.crdPatient(member, rule, variables, notes, missing)));
            prefetch.set("coverageBundle", collection(fhirServer, resources.crdCoverage(member, rule, variables, notes, missing)));
            CaseResources.Provider named = CaseResources.crdProvider(provider, notes, missing);
            if (named.practitioner() != null) {
                prefetch.set("practitionerBundle", collection(fhirServer, named.practitioner()));
            } else {
                prefetch.set("organizationBundle", collection(fhirServer, named.organization()));
            }
            prefetch.set("practitionerRoleBundle", collection(fhirServer, named.role()));
            ObjectNode serviceRequests = collection(fhirServer);
            for (ObjectNode order : sentOrders) {
                CaseResources.entry((ArrayNode) serviceRequests.get("entry"), order.deepCopy(), fhirServer);
            }
            if (sentOrders.isEmpty()) {
                // No empty array leaves this tool: the vendor's validator refused one each time.
                serviceRequests.remove("entry");
            }
            prefetch.set("serviceRequestBundle", serviceRequests);
            notes.add(named.practitioner() != null
                    ? "The prefetch was sent as the named Bundles patientBundle, coverageBundle, practitionerBundle, practitionerRoleBundle "
                            + "and serviceRequestBundle, each of type collection, as the vendor's sandbox accepted them. The keys the live "
                            + "service declares are in its discovery response."
                    : "The prefetch was sent as the named Bundles patientBundle, coverageBundle, organizationBundle, practitionerRoleBundle "
                            + "and serviceRequestBundle, each of type collection. organizationBundle stands in place of practitionerBundle, "
                            + "because the provider is known by an organization name only. The keys the live service declares are in its "
                            + "discovery response.");
        } else if (fhirServer != null) {
            notes.add("No prefetch was sent, because the form said to leave it out. The Patient, Coverage and provider resources, and "
                    + "what " + CaseResources.CRD_VERSION + " requires of them, are then the service's to fetch from the fhirServer sent.");
        } else {
            notes.add("No prefetch was sent, because the form said to leave it out, and no fhirServer was sent either: the variable "
                    + CaseResources.VAR_FHIR_SERVER + " is not set. This request gives the service no way to read the Patient, the "
                    + "Coverage or the provider.");
        }
        notes.add(CaseResources.fullUrlNote(fhirServer));
        if (!missing.isEmpty()) {
            // First, so a refusal by the service is read beside it and not taken for a fault of the service.
            notes.add(0, "This request leaves out " + missing.size() + (missing.size() == 1 ? " element" : " elements") + " that the "
                    + CaseResources.CRD_VERSION + " profiles require, because nobody supplied " + (missing.size() == 1 ? "it" : "them")
                    + ". It goes all the same. The notes below say which, and what would supply each.");
        }
        return new Built(JsonSafe.pretty(root), notes, orders, missing);
    }

    /**
     * Each diagnosis code of the case as a reasonCode of the order, with one coding: the system the
     * tester stated, else the one the sample's reasonCode uses, and the code as typed. Placed before
     * insurance, where FHIR's order of elements puts it, so the order reads as the sample's does.
     */
    private static void diagnosis(ObjectNode order, List<CodeInput> codes, PrefetchLayout.Reason reason) {
        if (codes.isEmpty()) {
            return;
        }
        JsonNode insurance = order.remove("insurance");
        ArrayNode reasonCode = order.putArray("reasonCode");
        for (CodeInput code : codes) {
            ObjectNode coding = reasonCode.addObject().putArray("coding").addObject();
            String system = Texts.present(code.system()) ? code.system().strip() : reason.system();
            if (system != null) {
                coding.put("system", system);
            }
            coding.put("code", code.code().strip());
        }
        if (insurance != null) {
            order.set("insurance", insurance);
        }
    }

    /** What the request says of the diagnosis when the sample's order carries one: where it went, or that the case states none. */
    static String diagnosisNote(List<CodeInput> codes, PrefetchLayout.Reason reason) {
        if (codes.isEmpty()) {
            return "The sample's order carries a diagnosis (reasonCode). This case states none: add Diagnosis codes on the Service card.";
        }
        // A count and a system, never the codes: a diagnosis beside a member is the member's own data.
        String count = codes.size() == 1 ? "the case's diagnosis code" : "the case's " + codes.size() + " diagnosis codes";
        boolean ownSystem = codes.stream().allMatch(code -> Texts.present(code.system()));
        return "Each order carries " + count + " as reasonCode, as the sample's order carries a diagnosis there"
                + (ownSystem ? ", with the system typed for each." : reason.system() == null
                ? ". The sample's reasonCode states no coding system, so none was sent with the code."
                : ", with the system " + reason.system() + " that the sample's reasonCode uses.");
    }

    /** Where a shape's value came from, as a note says it after the value. */
    static String from(RequestShapes.Choice choice) {
        return switch (choice.from()) {
            case RequestShapes.FROM_ENVIRONMENT -> "from this environment";
            case RequestShapes.FROM_SAMPLE -> "from the collection's sample"
                    + (Texts.present(choice.requestName()) ? " (the request \"" + choice.requestName() + "\")" : "");
            default -> "default";
        };
    }

    /**
     * The resources of the case, each built the first time a key of the sample asks for it, so the
     * notes and the elements counted as missing are those of what was sent and nothing else.
     */
    private final class Pieces {

        private final MemberDetail member;
        private final ProviderDetail provider;
        private final MemberIdRule rule;
        private final Function<String, ResolvedText> variables;
        private final List<String> notes;
        private final List<CaseResources.Required> missing;
        private final List<ObjectNode> orders;
        private final PrefetchLayout sample;
        private ObjectNode patient;
        private ObjectNode coverage;
        private CaseResources.Provider named;

        Pieces(MemberDetail member, ProviderDetail provider, MemberIdRule rule, Function<String, ResolvedText> variables,
               List<String> notes, List<CaseResources.Required> missing, List<ObjectNode> orders, PrefetchLayout sample) {
            this.sample = sample;
            this.member = member;
            this.provider = provider;
            this.rule = rule;
            this.variables = variables;
            this.notes = notes;
            this.missing = missing;
            this.orders = orders;
        }

        private CaseResources.Provider named() {
            if (named == null) {
                named = CaseResources.crdProvider(provider, notes, missing);
                followPractitionerSystem(named.practitioner());
            }
            return named;
        }

        /**
         * The variables with the member ID's system the sample states where none is set: the
         * environment's value wins, and the value itself is always the case's (D-309).
         */
        private Function<String, ResolvedText> withSampleSystem(PrefetchLayout.Identity own, PrefetchLayout.Identity other, String what) {
            String set = CaseResources.value(variables, CaseResources.VAR_MEMBER_ID_SYSTEM);
            String stated = own != null && own.system() != null ? own.system() : other == null ? null : other.system();
            if (set != null || stated == null) {
                return variables;
            }
            notes.add("The member ID on the " + what + " went with the system " + stated + ", which the sample states: the value "
                    + "Member ID system (" + CaseResources.VAR_MEMBER_ID_SYSTEM + ") is not set, and a value set there wins.");
            String template = "{{" + CaseResources.VAR_MEMBER_ID_SYSTEM + "}}";
            return text -> template.equals(text) ? ResolvedText.plain(stated) : variables.apply(text);
        }

        /** The member ID's identifier typed as the sample types it, where the sample states a type other than PAVE's MB. */
        private void followType(ObjectNode resource, PrefetchLayout.Identity own, String what) {
            if (own == null || !own.statesType()) {
                return;
            }
            JsonNode identifier = memberIdentifier(resource);
            if (identifier == null) {
                return;
            }
            ObjectNode coding = (ObjectNode) identifier.at("/type/coding/0");
            if (own.typeSystem().equals(JsonSafe.text(coding, "system")) && own.typeCode().equals(JsonSafe.text(coding, "code"))) {
                return;
            }
            coding.put("system", own.typeSystem());
            coding.put("code", own.typeCode());
            notes.add("The member ID on the " + what + " was typed " + own.typeCode() + " (" + own.typeSystem() + "), as the sample types "
                    + "it, in place of MB.");
        }

        /** The identifier PAVE's builder writes the member ID in: the first, typed MB. */
        private static JsonNode memberIdentifier(ObjectNode resource) {
            JsonNode first = resource.at("/identifier/0");
            return first.isObject() && first.at("/type/coding/0").isObject() ? first : null;
        }

        private void followPractitionerSystem(ObjectNode practitioner) {
            String stated = sample == null ? null : sample.practitionerSystem();
            JsonNode identifier = practitioner == null ? null : practitioner.at("/identifier/0");
            if (stated == null || identifier == null || !identifier.isObject() || stated.equals(JsonSafe.text(identifier, "system"))) {
                return;
            }
            ((ObjectNode) identifier).put("system", stated);
            notes.add("The practitioner's NPI went with the system " + stated + ", which the sample's Practitioner states, in place of "
                    + CaseResources.NPI_SYSTEM + ".");
        }

        /** The Patient with the member ID where the sample's Patient carries it: a Patient with no identifier in the sample carries none. */
        private ObjectNode patient() {
            PrefetchLayout.Identity own = sample == null ? null : sample.patient();
            PrefetchLayout.Identity other = sample == null ? null : sample.coverage();
            ObjectNode built = resources.crdPatient(member, rule, withSampleSystem(own, other, "Patient"), notes, missing);
            followType(built, own, "Patient");
            if (own != null && !own.hasIdentifier() && memberIdentifier(built) != null) {
                ((ArrayNode) built.get("identifier")).remove(0);
                if (built.get("identifier").isEmpty()) {
                    built.remove("identifier");
                }
                notes.add("The Patient was sent without the member ID, as the sample's Patient carries no identifier. The member ID "
                        + "still goes on the Coverage.");
            }
            return built;
        }

        /** The Coverage with the member ID where the sample's Coverage carries it: in subscriberId when that is its only place there. */
        private ObjectNode coverage() {
            PrefetchLayout.Identity own = sample == null ? null : sample.coverage();
            PrefetchLayout.Identity other = sample == null ? null : sample.patient();
            ObjectNode built = resources.crdCoverage(member, rule, withSampleSystem(own, other, "Coverage"), notes, missing);
            followType(built, own, "Coverage");
            if (own != null && own.subscriberId() && !built.has("subscriberId")) {
                built.put("subscriberId", org.p32h.interop.epa.pave.model.MemberIds.sent(member.memberId(), rule));
                notes.add("The Coverage carries the member ID as subscriberId too, as the sample's Coverage has one and no subscriber "
                        + "ID was given.");
            }
            if (own != null && !own.hasIdentifier() && own.subscriberId() && built.has("identifier")) {
                built.remove("identifier");
                notes.add("The Coverage was sent without an identifier, as the sample's Coverage carries the member ID in subscriberId "
                        + "only.");
            }
            return built;
        }

        /**
         * The resources of a type built from the case, each a copy, so one resource sent under two
         * keys is two trees: every order for ServiceRequest, else one or none.
         */
        List<ObjectNode> of(String type) {
            List<ObjectNode> out = new ArrayList<>();
            switch (type) {
                case "Patient" -> {
                    if (patient == null) {
                        patient = patient();
                    }
                    out.add(patient.deepCopy());
                }
                case "Coverage" -> {
                    if (coverage == null) {
                        coverage = coverage();
                    }
                    out.add(coverage.deepCopy());
                }
                case "Practitioner" -> addIfBuilt(out, named().practitioner());
                case "PractitionerRole" -> addIfBuilt(out, named().role());
                case "Organization" -> addIfBuilt(out, named().organization());
                case "ServiceRequest" -> orders.forEach(order -> out.add(order.deepCopy()));
                default -> {
                    // A type PAVE does not build: nothing, and the caller says so.
                }
            }
            return out;
        }

        private void addIfBuilt(List<ObjectNode> out, ObjectNode resource) {
            if (resource != null) {
                out.add(resource.deepCopy());
            }
        }
    }

    /**
     * The prefetch under the sample's keys, each holding what the sample holds there, built from
     * the case (D-309). A key or an entry that the case cannot fill is left out and said.
     */
    private static void samplePrefetch(ObjectNode prefetch, PrefetchLayout sample, Pieces pieces, String fhirServer,
                                       RequestShapes.Choice choice, List<String> notes) {
        List<String> sent = new ArrayList<>();
        List<String> said = new ArrayList<>(sample.leftOutLines());
        java.util.Set<String> held = new java.util.HashSet<>();
        for (PrefetchLayout.Key key : sample.keys()) {
            switch (key.kind()) {
                case NULL -> {
                    // CDS Hooks reads null as "could not be fetched"; the sample sends it so, and so does PAVE.
                    prefetch.putNull(key.name());
                    sent.add(key.shownName() + " as null, as the sample writes it");
                }
                case RESOURCE -> {
                    if (!PrefetchLayout.BUILT.contains(key.resourceType())) {
                        continue;
                    }
                    held.add(key.resourceType());
                    List<ObjectNode> built = pieces.of(key.resourceType());
                    if (built.isEmpty()) {
                        said.add(unbuilt(key.resourceType(), "under " + key.shownName()));
                        continue;
                    }
                    prefetch.set(key.name(), built.get(0));
                    sent.add(key.shownName() + " (" + PrefetchLayout.article(key.resourceType()) + " " + key.resourceType() + ")");
                    if (built.size() > 1) {
                        said.add("The sample holds a single ServiceRequest under " + key.shownName() + ", so the first order was sent "
                                + "there; every order is in context.draftOrders.");
                    }
                }
                case BUNDLE -> {
                    ObjectNode bundle = JSON.objectNode();
                    bundle.put("resourceType", "Bundle");
                    if (key.bundleType() != null) {
                        bundle.put("type", key.bundleType());
                    } else {
                        said.add("The sample's Bundle under " + key.shownName() + " states no type, so none was sent: FHIR requires "
                                + "Bundle.type, and a service that follows FHIR may refuse it.");
                    }
                    ArrayNode entries = JSON.arrayNode();
                    List<String> types = new ArrayList<>();
                    for (String type : key.entryTypes()) {
                        if (!PrefetchLayout.BUILT.contains(type)) {
                            continue;
                        }
                        held.add(type);
                        List<ObjectNode> built = pieces.of(type);
                        if (built.isEmpty()) {
                            if (!"ServiceRequest".equals(type)) {
                                said.add(unbuilt(type, "in the Bundle under " + key.shownName()));
                            }
                            continue;
                        }
                        types.add(type);
                        String mode = key.searchMode(type);
                        for (ObjectNode resource : built) {
                            CaseResources.entry(entries, resource, fhirServer);
                            if (mode != null) {
                                ((ObjectNode) entries.get(entries.size() - 1)).putObject("search").put("mode", mode);
                            }
                        }
                    }
                    if (entries.isEmpty() && !key.entryTypes().isEmpty()) {
                        if (key.entryTypes().stream().anyMatch(PrefetchLayout.BUILT::contains)) {
                            said.add("Nothing the case gives fills the Bundle under " + key.shownName() + ", so the key was not sent.");
                        }
                        continue;
                    }
                    if (key.total()) {
                        bundle.put("total", entries.size());
                    }
                    if (!entries.isEmpty()) {
                        // No empty array leaves this tool: the vendor's validator refused one each time.
                        bundle.set("entry", entries);
                    }
                    prefetch.set(key.name(), bundle);
                    sent.add(key.shownName() + " (a Bundle" + (key.bundleType() == null ? " with no type" : " of type " + key.bundleType())
                            + (types.isEmpty() ? " with no entries" : " holding " + PrefetchLayout.words(types)) + ")");
                }
                default -> {
                    // A placeholder or something that is no resource: leftOutLines has said it.
                }
            }
        }
        notes.add("Prefetch: As the collection's sample, " + from(choice) + ". The prefetch was sent under the sample's keys, each built "
                + "from the case: " + (sent.isEmpty() ? "none could be filled" : PrefetchLayout.words(sent)) + ".");
        notes.addAll(said);
        notes.addAll(sample.contextLines());
        List<String> notHeld = new ArrayList<>();
        for (String type : List.of("Patient", "Coverage", "Practitioner", "PractitionerRole")) {
            if (!held.contains(type)) {
                notHeld.add(type);
            }
        }
        if (!notHeld.isEmpty()) {
            notes.add("The sample's prefetch holds no " + PrefetchLayout.words(notHeld).replace(" and ", " or ") + ", so "
                    + (notHeld.size() == 1 ? "it was" : "they were") + " not sent in the prefetch.");
        }
    }

    /** The line for a type PAVE builds that this case gives none of, such as a Practitioner for a provider known as an organization. */
    private static String unbuilt(String type, String where) {
        return "The sample's prefetch holds " + PrefetchLayout.article(type) + " " + type + " " + where + ". This case gives none: "
                + ("Practitioner".equals(type) ? "the provider is known by an organization name only."
                : "Organization".equals(type) ? "the provider is a person, and PAVE builds an Organization only for a provider known by "
                        + "an organization name." : "nothing builds it.") + " It was not sent.";
    }

    /** A Bundle of type collection holding the resources given, each with its fullUrl. */
    private static ObjectNode collection(String fhirServer, ObjectNode... resources) {
        ObjectNode bundle = JSON.objectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "collection");
        ArrayNode entries = bundle.putArray("entry");
        for (ObjectNode resource : resources) {
            CaseResources.entry(entries, resource, fhirServer);
        }
        return bundle;
    }

    /**
     * The request note that says whether context.draftOrders went with its type, and where that
     * choice came from, so a refusal for it is read beside the setting that decided it.
     */
    static String draftOrdersNote(RequestShapes.Choice choice) {
        boolean leftOut = RequestShape.LEFT_OUT.equals(choice.value());
        String from = from(choice);
        if (leftOut) {
            return "draftOrders type: Left out, " + from + ". context.draftOrders was sent without type, as the vendor's sandbox accepted "
                    + "it; FHIR requires Bundle.type, so a service that follows FHIR may refuse it.";
        }
        return "draftOrders type: Sent, " + from + ". context.draftOrders was sent with type collection, as FHIR requires. The vendor's "
                + "sandbox refused context.draftOrders.type with HTTP 400; Left out is set for an environment in Configuration > "
                + "Environments.";
    }

    /** The hook name this request is for. Blank means order-sign. */
    public static String hook(CrdRunRequest request) {
        return Texts.present(request.hook()) ? request.hook().strip().toLowerCase(Locale.ROOT) : CrdRunRequest.ORDER_SIGN;
    }
}
