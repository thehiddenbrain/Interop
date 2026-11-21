package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the request bundle for a PAS submission and for an inquiry from the form, in the shape
 * PAS 2.0.1 gives it: PASRequestBundle with a PASClaim, PASInquiryRequestBundle with a
 * PASClaimInquiry, and every resource either claim refers to, as {@link PasResources} builds them.
 *
 * <p>What this class adds is the Claim that ties them together, the order behind each item, the
 * questionnaire answers an earlier step handed on, the documents the tester attached, and the full
 * url of every entry.
 *
 * <p>Nothing is filled in that was not supplied. Where an element needs a value this tool does not
 * have, the element is left out and a note says so; where a PAS 2.0.1 profile requires the element,
 * it is also counted as left out, so the result can say so first and carry a finding. Where the
 * tool had to choose a value, such as the claim type, the note says which value and how to change it.
 *
 * <p>How an attachment travels follows the configured attachment mode, described in
 * {@link OnyxPasConventions}. Neither mode is the guide's way of sending documentation after the
 * payer has asked for it.
 */
public final class PasRequestBuilder {

    /** Placeholder names the claim reads beyond the ones the resources read. Each is optional. */
    public static final String VAR_CLAIM_ID_SYSTEM = "claimIdentifierSystem";
    public static final String VAR_CLAIM_TYPE = "claimType";
    public static final String VAR_CLAIM_PRIORITY = "claimPriority";
    public static final String VAR_ATTACHMENT_NUMBER_SYSTEM = "attachmentControlNumberSystem";
    /** The X12 1365 service type code of every item: Claim.item.category, which PASClaim requires. */
    public static final String VAR_SERVICE_TYPE = "pasServiceType";
    /** The X12 1525 request type code of every item, such as HS: the itemRequestType extension. */
    public static final String VAR_REQUEST_TYPE = "pasRequestType";

    /** The identifier system for a value that is itself a uri, which is how FHIR writes a UUID as an identifier. */
    private static final String URI_SYSTEM = "urn:ietf:rfc:3986";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * The request body, what was left out of it, and what it carried.
     *
     * @param json           the request as pretty JSON
     * @param notes          one plain sentence for each thing left out or worked out. When something
     *                       a PAS 2.0.1 profile requires was left out, the first note says so with the count
     * @param sent           the claim identifier and each item sent, so a decision can be tied to its code
     * @param attachmentMode how the attachments of the request travel, or null when it has none
     * @param leftOut        each element a PAS 2.0.1 profile requires that the request went out without,
     *                       named by its profile and path. Empty when nothing was
     */
    public record Built(String json, List<String> notes, SentClaim sent, String attachmentMode, List<String> leftOut) {

        public Built {
            notes = notes == null ? List.of() : List.copyOf(notes);
            leftOut = leftOut == null ? List.of() : List.copyOf(leftOut);
        }

        /** Redacted, because the body carries member data. */
        @Override
        public String toString() {
            return "Built[items=" + sent.items().size() + ", attachmentMode=" + attachmentMode + ", leftOut=" + leftOut.size() + "]";
        }
    }

    private final PasResources resources;
    private final String attachmentMode;
    private final Clock clock;
    private final Supplier<UUID> uuids;

    /**
     * @param codeList       consulted for a code's system when the tester did not state one. May be null
     * @param attachmentMode fhir or x12-275, as configured and checked at startup
     */
    public PasRequestBuilder(ProcedureCodeDirectory codeList, String attachmentMode, Clock clock) {
        this(codeList, attachmentMode, clock, UUID::randomUUID);
    }

    /** @param uuids the source of the guids of the entries' full urls, which PAS 2.0.1 requires to be unique across submissions */
    PasRequestBuilder(ProcedureCodeDirectory codeList, String attachmentMode, Clock clock, Supplier<UUID> uuids) {
        this.resources = new PasResources(codeList);
        this.attachmentMode = attachmentMode;
        this.clock = clock;
        this.uuids = uuids;
    }

    /**
     * @param claimId   the identifier the claim carries
     * @param newClaim  true when the identifier is minted for this submission, which makes it an
     *                  initial request. False when the tester typed it or an earlier step handed it on
     * @param bundleId  the identifier of this one bundle, unique for each submission
     * @param variables resolves a placeholder name to its value, or to an unresolved result
     */
    public Built submit(PasSubmitRequest request, String claimId, boolean newClaim, String bundleId,
                        Function<String, ResolvedText> variables) {
        PasNotes notes = new PasNotes();
        List<ObjectNode> carried = new ArrayList<>();
        ArrayNode supportingInfo = JSON.arrayNode();

        int position = 1;
        for (String text : questionnaireResponses(request.carry())) {
            ObjectNode answers = (ObjectNode) JsonSafe.parse(text).node().deepCopy();
            String id = JsonSafe.presentText(answers, "id");
            if (id == null) {
                id = OnyxPasConventions.questionnaireResponseId(position);
                answers.put("id", id);
                notes.add("The questionnaire answers handed on from DTR carried no id, so they were given the id " + id
                        + " to let the claim refer to them.");
            }
            carried.add(answers);
            supportingInfo.add(supporting(supportingInfo.size() + 1, JSON.objectNode().put("reference", "QuestionnaireResponse/" + id)));
            notes.add("The questionnaire answers handed on from DTR travel in the bundle as QuestionnaireResponse/" + id
                    + " and are named in the claim's supporting information.");
            position++;
        }

        position = 1;
        for (PasAttachment attachment : request.attachments()) {
            String title = attachment.title().strip();
            if (StartupChecks.ATTACHMENT_X12_275.equals(attachmentMode)) {
                String number = OnyxPasConventions.attachmentControlNumber(claimId, position);
                ObjectNode reference = JSON.objectNode();
                ObjectNode identifier = reference.putObject("identifier");
                String system = CaseResources.value(variables, VAR_ATTACHMENT_NUMBER_SYSTEM);
                if (system != null) {
                    identifier.put("system", system);
                }
                identifier.put("value", number);
                reference.put("display", title);
                supportingInfo.add(supporting(supportingInfo.size() + 1, reference));
                notes.add("Attachment " + position + " (\"" + title + "\") was not put in the bundle. In the attachment mode "
                        + StartupChecks.ATTACHMENT_X12_275 + " the claim only names the document, by the attachment control number " + number
                        + ", for an X12 275 transaction that this tool does not send. The service receives no document from this tool. "
                        + "In PAS 2.0.1 a 275 is produced by the intermediary from the bundle, not sent by the client beside it."
                        + typeAndCategoryNotSent(attachment));
            } else {
                String id = OnyxPasConventions.documentReferenceId(position);
                boolean inline = Texts.present(attachment.text());
                carried.add(PasResources.documentReference(id, title, attachment, position, notes));
                supportingInfo.add(supporting(supportingInfo.size() + 1, JSON.objectNode().put("reference", "DocumentReference/" + id)));
                notes.add("Attachment " + position + " (\"" + title + "\") travels in the bundle as DocumentReference/" + id
                        + (inline ? ", with its content inline." : ", by its url."));
            }
            position++;
        }
        if (!request.attachments().isEmpty()) {
            notes.add("Neither attachment mode is the PAS 2.0.1 way of sending documentation after the payer has asked for it: that is a "
                    + "claim update that replaces the earlier claim, or the CDex operation $submit-attachment, and this tool builds neither.");
        }

        ArrayNode diagnosis = JSON.arrayNode();
        for (CodeInput code : request.diagnosisCodes()) {
            ObjectNode entry = diagnosis.addObject();
            entry.put("sequence", diagnosis.size());
            entry.set("diagnosisCodeableConcept", diagnosisConcept(code, notes));
        }
        if (diagnosis.isEmpty()) {
            notes.add("No diagnosis was sent, because none was supplied.");
        }

        List<SentClaim.Item> items = new ArrayList<>();
        Case built = caseResources(true, claimId, newClaim, request.member(), request.provider(), request.procedureCodes(),
                request.dateOfService(), supportingInfo, diagnosis, variables, notes, items);
        List<ObjectNode> bundled = new ArrayList<>(built.resources());
        bundled.addAll(carried);
        String json = bundle(bundleId, "PASRequestBundle", bundled, variables, notes);
        return new Built(json, notes.notes(), new SentClaim(claimId, items), request.attachments().isEmpty() ? null : attachmentMode,
                notes.leftOut());
    }

    /**
     * The bundle an inquiry sends: a PASClaimInquiry that names the request by its identifier,
     * with the member, the coverage, the insurer and the provider, and one item for each code of
     * the submission it asks about, in the order the submission sent them, so that each decision
     * of the answer can be tied to its code.
     *
     * @param codes     the codes of the submission, in its order. Empty when none is known
     * @param codesFrom where those codes came from, in a few words, for the note, such as "given on the form"
     */
    public Built inquire(PasInquireRequest request, String claimId, List<CodeInput> codes, String codesFrom, String bundleId,
                         Function<String, ResolvedText> variables) {
        PasNotes notes = new PasNotes();
        if (codes.isEmpty()) {
            notes.missing("PASClaimInquiry item", "The inquiry lists no item: no procedure code was given on the form, handed on from the "
                    + "submission or found on its ledger line. PAS 2.0.1 requires at least one item on an inquiry; the 2.2.0 ballot does "
                    + "not, and reads an inquiry with no item as one about the request as a whole.");
        } else {
            notes.add("The inquiry names the request by its claim identifier and lists the procedure codes " + codesFrom + " as items, in "
                    + "that order. That has to be the order the submission sent them in for each decision to be tied to its code.");
        }
        List<SentClaim.Item> items = new ArrayList<>();
        Case built = caseResources(false, claimId, false, request.member(), request.provider(), codes, request.dateOfService(),
                JSON.arrayNode(), JSON.arrayNode(), variables, notes, items);
        String json = bundle(bundleId, "PASInquiryRequestBundle", built.resources(), variables, notes);
        return new Built(json, notes.notes(), new SentClaim(claimId, items), null, notes.leftOut());
    }

    /**
     * What is wrong with the questionnaire answers an earlier step handed on, or null when they can
     * be included. They must be a QuestionnaireResponse, because dropping them without a word, or
     * sending something else in their place, would make the submission mean something the tester
     * did not intend.
     */
    public static String questionnaireProblem(Map<String, List<String>> carry) {
        for (String text : questionnaireResponses(carry)) {
            JsonSafe.Parsed parsed = JsonSafe.parse(text);
            if (!parsed.ok() || !parsed.node().isObject() || !"QuestionnaireResponse".equals(JsonSafe.text(parsed.node(), "resourceType"))) {
                return "The questionnaire answers handed on from DTR are not a QuestionnaireResponse, so they cannot be included. "
                        + "Complete the questionnaire again, or submit without them.";
            }
        }
        return null;
    }

    private static List<String> questionnaireResponses(Map<String, List<String>> carry) {
        List<String> values = carry == null ? null : carry.get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        return values == null ? List.of() : values.stream().filter(Texts::present).toList();
    }

    /** The resources of one case, in the order they go into the bundle, the Claim first. */
    private record Case(List<ObjectNode> resources) {
    }

    /**
     * @param submission true for a PASClaim, false for a PASClaimInquiry
     */
    private Case caseResources(boolean submission, String claimId, boolean newClaim, MemberDetail member, ProviderDetail provider,
                               List<CodeInput> codes, String dateOfService, ArrayNode supportingInfo, ArrayNode diagnosis,
                               Function<String, ResolvedText> variables, PasNotes notes, List<SentClaim.Item> items) {
        String claimProfile = submission ? "PASClaim" : "PASClaimInquiry";
        ObjectNode insurer = PasResources.insurer(variables, claimProfile, notes);
        ObjectNode patient = resources.patient(member, variables, notes);
        ObjectNode coverage = resources.coverage(member, variables, insurer != null, notes);
        String requester = PasResources.requestorReference(provider);
        List<ObjectNode> requestor = PasResources.requestor(provider, variables, notes);

        ObjectNode claim = JSON.objectNode();
        claim.put("resourceType", "Claim");
        claim.put("id", OnyxPasConventions.CLAIM_ID);
        ObjectNode identifier = claim.putArray("identifier").addObject();
        String identifierSystem = CaseResources.value(variables, VAR_CLAIM_ID_SYSTEM);
        if (identifierSystem != null) {
            identifier.put("system", identifierSystem);
        } else {
            notes.add("The claim identifier was sent without an identifier system: the variable " + VAR_CLAIM_ID_SYSTEM + " is not set. "
                    + "PAS 2.0.1 does not require one; the 2.2.0 ballot does.");
        }
        identifier.put("value", claimId);
        claim.put("status", "active");
        claim.putObject("type").putArray("coding").addObject().put("system", OnyxPasConventions.CLAIM_TYPES)
                .put("code", chosen(variables, VAR_CLAIM_TYPE, OnyxPasConventions.DEFAULT_CLAIM_TYPE, "claim type", notes));
        claim.put("use", "preauthorization");
        claim.putObject("patient").put("reference", CaseResources.PATIENT_REF);
        claim.put("created", RunStamps.timestamp(clock));
        if (insurer != null) {
            claim.putObject("insurer").put("reference", PasResources.INSURER_REF);
        }
        claim.putObject("provider").put("reference", requester);
        claim.putObject("priority").putArray("coding").addObject().put("system", OnyxPasConventions.PROCESS_PRIORITIES)
                .put("code", chosen(variables, VAR_CLAIM_PRIORITY, OnyxPasConventions.DEFAULT_CLAIM_PRIORITY, "claim priority", notes));
        if (!supportingInfo.isEmpty()) {
            claim.set("supportingInfo", supportingInfo);
        }
        if (!diagnosis.isEmpty()) {
            claim.set("diagnosis", diagnosis);
        }
        ObjectNode insurance = claim.putArray("insurance").addObject();
        insurance.put("sequence", 1);
        insurance.put("focal", true);
        insurance.putObject("coverage").put("reference", CaseResources.COVERAGE_REF);

        List<ObjectNode> orders = new ArrayList<>();
        if (!codes.isEmpty()) {
            String serviceType = CaseResources.value(variables, VAR_SERVICE_TYPE);
            String requestType = CaseResources.value(variables, VAR_REQUEST_TYPE);
            ArrayNode itemArray = claim.putArray("item");
            int sequence = 1;
            for (CodeInput code : codes) {
                ObjectNode item = itemArray.addObject();
                ArrayNode extensions = item.putArray("extension");
                // The trace number is this tool's own: the claim identifier and the item's sequence,
                // so an inquiry about the same claim names each item by the same number.
                String traceNumber = claimId + "-" + sequence;
                extensions.addObject().put("url", OnyxPasConventions.ITEM_TRACE_NUMBER).putObject("valueIdentifier")
                        .put("value", traceNumber);
                if (requestType != null) {
                    extensions.addObject().put("url", OnyxPasConventions.ITEM_REQUEST_TYPE).set("valueCodeableConcept",
                            x12(OnyxPasConventions.X12_REQUEST_TYPES, requestType));
                }
                if (submission && newClaim) {
                    extensions.addObject().put("url", OnyxPasConventions.ITEM_CERTIFICATION_TYPE).set("valueCodeableConcept",
                            x12(OnyxPasConventions.X12_CERTIFICATION_TYPES, OnyxPasConventions.CERTIFICATION_INITIAL));
                }
                if (submission) {
                    extensions.addObject().put("url", OnyxPasConventions.ITEM_REQUESTED_SERVICE).putObject("valueReference")
                            .put("reference", CaseResources.serviceRequestRef(sequence));
                    orders.add(resources.serviceRequest(sequence, code, member, dateOfService, requester, variables, notes));
                }
                item.put("sequence", sequence);
                if (serviceType != null) {
                    item.set("category", x12(OnyxPasConventions.X12_SERVICE_TYPES, serviceType));
                }
                item.set("productOrService", resources.codeableConcept(code, member.lineOfBusiness(), variables, claimProfile, notes));
                if (Texts.present(dateOfService)) {
                    item.put("servicedDate", dateOfService.strip());
                }
                items.add(new SentClaim.Item(sequence, code.code().strip(), traceNumber));
                sequence++;
            }
            if (serviceType == null) {
                if (submission) {
                    notes.missing("PASClaim item.category", "No service type was sent on the items: the variable " + VAR_SERVICE_TYPE
                            + " is not set. PAS 2.0.1 PASClaim requires item.category, an X12 service type code such as 1 for medical care.");
                } else {
                    notes.add("No service type was sent on the items: the variable " + VAR_SERVICE_TYPE + " is not set. An inquiry does "
                            + "not require one.");
                }
            }
            if (requestType == null) {
                notes.add("No request type was sent on the items: the variable " + VAR_REQUEST_TYPE + " is not set. PAS 2.0.1 marks it "
                        + "must-support, and the 2.2.0 ballot requires it. It is an X12 request type code, such as HS for health services review.");
            }
            if (submission && !newClaim) {
                notes.add("No certification type was sent on the items: the claim identifier was typed or handed on, so whether this is "
                        + "an initial request or a revision of one is not known. PAS 2.0.1 marks it must-support, and the 2.2.0 ballot "
                        + "requires it.");
            } else if (submission) {
                notes.add("Each item was sent with the certification type I (initial), because the claim identifier was minted for "
                        + "this submission.");
            }
            if (submission) {
                notes.add("Each item names its order by the requestedService extension, and each order travels in the bundle as "
                        + "ServiceRequest/service-request-N, under the id CRD and DTR gave it.");
            }
            if (Texts.blank(dateOfService)) {
                notes.add("No date of service was sent on the items, because none was supplied.");
            }
        }

        List<ObjectNode> out = new ArrayList<>();
        out.add(claim);
        out.add(patient);
        out.add(coverage);
        if (insurer != null) {
            out.add(insurer);
        }
        out.addAll(requestor);
        out.addAll(orders);
        return new Case(out);
    }

    private static ObjectNode x12(String system, String code) {
        ObjectNode concept = JSON.objectNode();
        concept.putArray("coding").addObject().put("system", system).put("code", code);
        return concept;
    }

    /**
     * A value the request must carry and the tester may state as a variable. When they have not,
     * the tool's own default is sent and the note says so, because a default nobody is told about
     * is a guess.
     */
    private static String chosen(Function<String, ResolvedText> variables, String variable, String fallback, String what, PasNotes notes) {
        String stated = CaseResources.value(variables, variable);
        if (stated != null) {
            return stated;
        }
        notes.add("The " + what + " was set to " + fallback + ", which is this tool's default and not something known about the "
                + "request. Set the variable " + variable + " to send another.");
        return fallback;
    }

    private static ObjectNode supporting(int sequence, ObjectNode reference) {
        ObjectNode info = JSON.objectNode();
        info.put("sequence", sequence);
        info.putObject("category").putArray("coding").addObject().put("system", OnyxPasConventions.SUPPORTING_INFO_TYPES)
                .put("code", OnyxPasConventions.ADDITIONAL_INFORMATION);
        info.set("valueReference", reference);
        return info;
    }

    /** What the x12-275 note adds when the attachment was given a type or a category, which only a DocumentReference carries. */
    private static String typeAndCategoryNotSent(PasAttachment attachment) {
        boolean type = attachment.type() != null && Texts.present(attachment.type().code());
        boolean category = attachment.category() != null && Texts.present(attachment.category().code());
        if (!type && !category) {
            return "";
        }
        String what = type && category ? "type " + attachment.type().code().strip() + " and category " + attachment.category().code().strip()
                : type ? "type " + attachment.type().code().strip() : "category " + attachment.category().code().strip();
        return " The document " + what + " given with it " + (type && category ? "were" : "was") + " not sent either, because only the "
                + "DocumentReference the bundle would carry has a place for " + (type && category ? "them" : "it") + ".";
    }

    /**
     * A diagnosis code as a CodeableConcept. Its coding system is the one the tester stated, else
     * ICD-10-CM when the code has that format, which is a rule about a published code format and
     * is still reported. A code of any other format is sent without a system, and the note says
     * that PAS 2.0.1 binds the element, required, to a value set the code then cannot be shown to be in.
     */
    private static ObjectNode diagnosisConcept(CodeInput code, PasNotes notes) {
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        String value = code.code().strip();
        if (Texts.present(code.system())) {
            coding.put("system", code.system().strip());
        } else if (value.toUpperCase(Locale.ROOT).matches("[A-TV-Z][0-9][0-9AB](\\.?[0-9A-TV-Z]{1,4})?")) {
            coding.put("system", OnyxPasConventions.ICD_10_CM);
            notes.add("The coding system for the diagnosis " + value + " was taken from its format: ICD-10-CM.");
        } else {
            notes.missing("PASClaim diagnosis.diagnosisCodeableConcept system (diagnosis " + value + ")", "No coding system could be "
                    + "worked out for the diagnosis " + value + ", so it was sent without one. PAS 2.0.1 binds the diagnosis, required, to "
                    + "the value set X12278DiagnosisCodes, so the service may refuse it. State its system with the code.");
        }
        coding.put("code", value);
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        return concept;
    }

    /**
     * The bundle. PAS 2.0.1 requires a full url on every entry: the address the resource has on
     * the client's server, when the tester states that base, else a urn:uuid. With urn:uuid full
     * urls a relative reference resolves to nothing, so every reference inside the bundle that
     * names an entry is rewritten to that entry's full url.
     */
    private String bundle(String bundleId, String bundleProfile, List<ObjectNode> bundled, Function<String, ResolvedText> variables,
                          PasNotes notes) {
        ObjectNode bundle = JSON.objectNode();
        bundle.put("resourceType", "Bundle");
        bundle.putObject("identifier").put("system", URI_SYSTEM).put("value", bundleId);
        bundle.put("type", "collection");
        bundle.put("timestamp", RunStamps.timestamp(clock));
        ArrayNode entries = bundle.putArray("entry");
        String base = CaseResources.value(variables, CaseResources.VAR_FHIR_SERVER);
        Map<String, String> fullUrls = new HashMap<>();
        for (JsonNode resource : bundled) {
            String local = JsonSafe.text(resource, "resourceType") + "/" + JsonSafe.text(resource, "id");
            fullUrls.put(local, base != null ? Texts.withoutTrailing(base, "/") + "/" + local : "urn:uuid:" + uuids.get());
        }
        Set<String> unresolved = new LinkedHashSet<>();
        for (ObjectNode resource : bundled) {
            if (base == null) {
                repoint(resource, fullUrls, unresolved);
            } else {
                unresolvedIn(resource, fullUrls, unresolved);
            }
            ObjectNode entry = entries.addObject();
            entry.put("fullUrl", fullUrls.get(JsonSafe.text(resource, "resourceType") + "/" + JsonSafe.text(resource, "id")));
            entry.set("resource", resource);
        }
        if (base == null) {
            notes.add("Every entry carries a full url of the form urn:uuid:, as PAS 2.0.1 allows when the resources have no address of "
                    + "their own (the variable " + CaseResources.VAR_FHIR_SERVER + " is not set), and every reference between the "
                    + "resources names one of those full urls.");
        }
        if (!unresolved.isEmpty()) {
            for (String reference : unresolved) {
                notes.missing(bundleProfile + " entry for " + reference, null);
            }
            notes.add("These references inside the bundle name a resource the bundle does not carry: " + String.join(", ", unresolved)
                    + ". PAS 2.0.1 requires (SHALL) that the bundle carry every resource the claim refers to, and every resource those "
                    + "refer to in turn.");
        }
        return JsonSafe.pretty(bundle);
    }

    /** Rewrites each reference that names an entry of the bundle to that entry's full url, and collects the ones that name none. */
    private static void repoint(JsonNode node, Map<String, String> fullUrls, Set<String> unresolved) {
        if (node instanceof ObjectNode object) {
            JsonNode reference = object.get("reference");
            if (reference != null && reference.isString()) {
                String target = fullUrls.get(reference.asString());
                if (target != null) {
                    object.put("reference", target);
                } else if (!reference.asString().startsWith("#")) {
                    unresolved.add(reference.asString());
                }
            }
            for (String field : object.properties().stream().map(Map.Entry::getKey).toList()) {
                if (!"reference".equals(field)) {
                    repoint(object.get(field), fullUrls, unresolved);
                }
            }
        } else if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                repoint(element, fullUrls, unresolved);
            }
        }
    }

    /** Collects the relative references that name no entry, for a bundle whose references stay relative. */
    private static void unresolvedIn(JsonNode node, Map<String, String> fullUrls, Set<String> unresolved) {
        if (node instanceof ObjectNode object) {
            JsonNode reference = object.get("reference");
            if (reference != null && reference.isString() && !fullUrls.containsKey(reference.asString())
                    && !reference.asString().startsWith("#") && !reference.asString().contains("://")) {
                unresolved.add(reference.asString());
            }
            for (String field : object.properties().stream().map(Map.Entry::getKey).toList()) {
                if (!"reference".equals(field)) {
                    unresolvedIn(object.get(field), fullUrls, unresolved);
                }
            }
        } else if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                unresolvedIn(element, fullUrls, unresolved);
            }
        }
    }
}
