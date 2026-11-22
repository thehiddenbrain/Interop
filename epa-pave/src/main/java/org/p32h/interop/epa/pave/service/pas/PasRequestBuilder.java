package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Builds the request bundle for a PAS submission and for an inquiry from the form, in the shape the
 * PAS guide in force gives it (2.0.1 unless the environment chooses 2.2.1, {@link PasGuide}): PASRequestBundle with a PASClaim, PASInquiryRequestBundle with a
 * PASClaimInquiry, and every resource either claim refers to, as {@link PasResources} builds them.
 *
 * <p>What this class adds is the Claim that ties them together, the order behind each item, the
 * questionnaire answers an earlier step handed on, the documents the tester attached, the
 * servicing provider on the care team, and the full url of every entry.
 *
 * <p>Nothing is filled in that was not supplied. A value of the Service card comes from the card
 * first, else from the environment's values or variables, else from the one rule this tool has
 * (certification I for a claim identifier minted here), else the element is left out. Where a
 * profile of the guide in force requires an element that went out without its value, it is counted as left out, so
 * the result can say so first and carry a finding. A value given for an element the guide in force
 * does not define is not sent, and a note names it by its label. Nothing blocks sending: an incomplete request
 * is a negative test, and the notes say what it lacks and where to add it.
 *
 * <p>Instants are written in whole seconds in UTC: the vendor's sandbox refused a timestamp with
 * seven fractional digits and a local offset. They are written here rather than through the
 * ledger's stamps, so that the ledger keeps its own form.
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
    /** The X12 1525 request type code of every item, such as HS: the serviceItemRequestType extension, which PASClaim requires. */
    public static final String VAR_REQUEST_TYPE = "pasRequestType";
    /** The X12 1322 certification type code of every item, such as I: the certificationType extension, which PASClaim requires. */
    public static final String VAR_CERTIFICATION_TYPE = "pasCertificationType";
    /** The X12 PWK01 report type code of every additional information entry: the documentInformation extension. */
    public static final String VAR_REPORT_TYPE = "pasReportType";
    /** The identifier system of the item trace numbers, which PAS 2.2.1 PASIdentifier requires and 2.0.1's allows. */
    public static final String VAR_ITEM_TRACE_SYSTEM = "itemTraceNumberSystem";

    /** The identifier system for a value that is itself a uri, which is how FHIR writes a UUID as an identifier. */
    private static final String URI_SYSTEM = "urn:ietf:rfc:3986";

    /** Where a tester adds a value the request lacks, in the words of the screens. */
    private static final String ON_THE_CARD = "choose it under Service details";
    private static final String UNDER_VALUES = " under Values in Configuration > Environments";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * The request body, what was left out of it, and what it carried.
     *
     * @param json           the request as pretty JSON
     * @param notes          one plain sentence for each thing left out or worked out. When something
     *                       a profile of the guide in force requires was left out, the first note says so with the count
     * @param sent           the claim identifier and each item sent, so a decision can be tied to its code
     * @param attachmentMode how the attachments of the request travel, or null when it has none
     * @param leftOut        each element a profile of the guide in force requires that the request went out without,
     *                       named by its profile and path. Empty when nothing was
     * @param toComplete     the gaps a tester can close before sending, each with the field that
     *                       closes it, for "To complete before sending". Empty when there are none
     */
    public record Built(String json, List<String> notes, SentClaim sent, String attachmentMode, List<String> leftOut,
                        List<RequestPreview.ToComplete> toComplete) {

        public Built {
            notes = notes == null ? List.of() : List.copyOf(notes);
            leftOut = leftOut == null ? List.of() : List.copyOf(leftOut);
            toComplete = toComplete == null ? List.of() : List.copyOf(toComplete);
        }

        public Built(String json, List<String> notes, SentClaim sent, String attachmentMode, List<String> leftOut) {
            this(json, notes, sent, attachmentMode, leftOut, List.of());
        }

        /** Redacted, because the body carries member data. */
        @Override
        public String toString() {
            return "Built[items=" + sent.items().size() + ", attachmentMode=" + attachmentMode + ", leftOut=" + leftOut.size() + "]";
        }
    }

    /** What the Service card states about the whole request. An inquiry states none of it. */
    private record Card(String placeOfService, String levelOfService, String certificationType, String requestType, String serviceType,
                        ProviderDetail servicingProvider) {

        static final Card NONE = new Card(null, null, null, null, null, null);

        static Card of(PasSubmitRequest request) {
            return new Card(Texts.trimToNull(request.placeOfService()), Texts.trimToNull(request.levelOfService()),
                    Texts.trimToNull(request.certificationType()), Texts.trimToNull(request.requestType()),
                    Texts.trimToNull(request.serviceType()), request.servicingProvider());
        }
    }

    private final PasResources resources;
    private final String attachmentMode;
    private final Clock clock;
    private final Supplier<UUID> uuids;
    private final PasGuide guide;

    /**
     * @param codeList       consulted for a code's system when the tester did not state one. May be null
     * @param attachmentMode fhir or x12-275, as configured and checked at startup
     */
    public PasRequestBuilder(ProcedureCodeDirectory codeList, String attachmentMode, Clock clock) {
        this(codeList, attachmentMode, clock, UUID::randomUUID);
    }

    /** @param uuids the source of the guids of the entries' full urls, which PAS 2.0.1 and 2.2.1 require to be unique across submissions */
    PasRequestBuilder(ProcedureCodeDirectory codeList, String attachmentMode, Clock clock, Supplier<UUID> uuids) {
        this(new PasResources(codeList), attachmentMode, clock, uuids, PasGuide.V201);
    }

    private PasRequestBuilder(PasResources resources, String attachmentMode, Clock clock, Supplier<UUID> uuids, PasGuide guide) {
        this.resources = resources;
        this.attachmentMode = attachmentMode;
        this.clock = clock;
        this.uuids = uuids;
        this.guide = guide;
    }

    /** This builder held against another guide by the calls that name none; a call that names one keeps its own. */
    PasRequestBuilder under(PasGuide guide) {
        return new PasRequestBuilder(resources, attachmentMode, clock, uuids, guide == null ? PasGuide.V201 : guide);
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
        return submit(request, claimId, newClaim, bundleId, variables, MemberIdRule.PACKED);
    }

    public Built submit(PasSubmitRequest request, String claimId, boolean newClaim, String bundleId,
                        Function<String, ResolvedText> variables, MemberIdRule memberIdRule) {
        return submit(request, claimId, newClaim, bundleId, variables, memberIdRule, guide);
    }

    /**
     * @param memberIdRule how the environment the call goes to writes a member ID; null is read as packed
     * @param guide        the PAS version the request is held against; null is read as 2.0.1
     */
    public Built submit(PasSubmitRequest request, String claimId, boolean newClaim, String bundleId,
                        Function<String, ResolvedText> variables, MemberIdRule memberIdRule, PasGuide guide) {
        PasNotes notes = new PasNotes(guide);
        notes.add(guideNote(notes));
        List<ObjectNode> carried = new ArrayList<>();
        ArrayNode supportingInfo = JSON.arrayNode();
        String statedReportType = CaseResources.value(variables, VAR_REPORT_TYPE);
        // PAS 2.0.1 defines no documentInformation extension, so a report type has nowhere to go.
        String reportType = notes.guide().is221() ? statedReportType : null;

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
            supportingInfo.add(supporting(supportingInfo.size() + 1, JSON.objectNode().put("reference", "QuestionnaireResponse/" + id),
                    reportType, null));
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
                supportingInfo.add(supporting(supportingInfo.size() + 1, reference, reportType, number));
                notes.add("Attachment " + position + " (\"" + title + "\") was not put in the bundle. In the attachment mode "
                        + StartupChecks.ATTACHMENT_X12_275 + " the claim only names the document, by the attachment control number " + number
                        + ", for an X12 275 transaction that this tool does not send. The service receives no document from this tool. "
                        + "In " + notes.pas() + " a 275 is produced by the intermediary from the bundle, not sent by the client beside it."
                        + typeAndCategoryNotSent(attachment));
            } else {
                String id = OnyxPasConventions.documentReferenceId(position);
                boolean inline = Texts.present(attachment.data()) || Texts.present(attachment.text());
                carried.add(PasResources.documentReference(id, title, attachment, position, PasResources.patientRef(request.member()), notes));
                supportingInfo.add(supporting(supportingInfo.size() + 1, JSON.objectNode().put("reference", "DocumentReference/" + id),
                        reportType, null));
                notes.add("Attachment " + position + " (\"" + title + "\") travels in the bundle as DocumentReference/" + id
                        + (inline ? ", with its content inline." : ", by its url."));
            }
            position++;
        }
        if (!request.attachments().isEmpty()) {
            notes.add("Neither attachment mode is the " + notes.pas() + " way of sending documentation after the payer has asked for it: that is a "
                    + "claim update that replaces the earlier claim, or the CDex operation $submit-attachment, and this tool builds neither.");
        }
        if (!supportingInfo.isEmpty() && !notes.guide().is221()) {
            if (statedReportType != null) {
                notes.notSent("PAS report type");
            }
        } else if (!supportingInfo.isEmpty()) {
            if (reportType != null) {
                notes.add("Each additional information entry carries the document information " + notes.pas() + " asks for: the report type "
                        + reportType + " (X12 PWK01, from the value " + VAR_REPORT_TYPE + ") and the transmission method "
                        + OnyxPasConventions.TRANSMISSION_ELECTRONIC + ", electronically.");
            } else {
                notes.toComplete("values.pasReportType", "Report type: none. " + notes.pas() + " requires document information on each additional "
                        + "information entry: add PAS report type" + UNDER_VALUES + ".");
                notes.missing("PASClaim supportingInfo[AdditionalInformation].extension[documentInformation]", "No document information was "
                        + "sent on the claim's supporting information: the value " + VAR_REPORT_TYPE + " is not set. " + notes.pas() + " PASClaim requires "
                        + "it, with an X12 PWK01 report type, on every additional information entry: add PAS report type" + UNDER_VALUES + ".");
            }
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
        Case built = caseResources(true, Card.of(request), claimId, newClaim, request.member(), memberIdRule, request.provider(),
                request.procedureCodes(), request.dateOfService(), supportingInfo, diagnosis, variables, notes, items);
        List<ObjectNode> bundled = new ArrayList<>(built.resources());
        bundled.addAll(carried);
        String json = bundle(bundleId, "PASRequestBundle", bundled, variables, notes);
        return new Built(json, notes.notes(), new SentClaim(claimId, items), request.attachments().isEmpty() ? null : attachmentMode,
                notes.leftOut(), notes.toComplete());
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
        return inquire(request, claimId, codes, codesFrom, bundleId, variables, MemberIdRule.PACKED);
    }

    public Built inquire(PasInquireRequest request, String claimId, List<CodeInput> codes, String codesFrom, String bundleId,
                         Function<String, ResolvedText> variables, MemberIdRule memberIdRule) {
        return inquire(request, claimId, codes, codesFrom, bundleId, variables, memberIdRule, guide);
    }

    /**
     * @param memberIdRule how the environment the call goes to writes a member ID; null is read as packed
     * @param guide        the PAS version the inquiry is held against; null is read as 2.0.1
     */
    public Built inquire(PasInquireRequest request, String claimId, List<CodeInput> codes, String codesFrom, String bundleId,
                         Function<String, ResolvedText> variables, MemberIdRule memberIdRule, PasGuide guide) {
        PasNotes notes = new PasNotes(guide);
        notes.add(guideNote(notes));
        if (codes.isEmpty() && !notes.guide().is221()) {
            // PAS 2.0.1 PASClaimBase, which PASClaimInquiry is built on, makes item 1..*.
            notes.missing("PASClaimInquiry item", "The inquiry lists no item: no procedure code was given on the form, handed on from the "
                    + "submission or found on its ledger line. PAS 2.0.1 requires at least one item on an inquiry: give the procedure code "
                    + "the submission sent.");
        } else if (codes.isEmpty()) {
            notes.add("The inquiry lists no item: no procedure code was given on the form, handed on from the submission or found on its "
                    + "ledger line. " + notes.pas() + " does not require an item on an inquiry.");
        } else {
            notes.add("The inquiry names the request by its claim identifier and lists the procedure codes " + codesFrom + " as items, in "
                    + "that order. That has to be the order the submission sent them in for each decision to be tied to its code.");
        }
        List<SentClaim.Item> items = new ArrayList<>();
        Case built = caseResources(false, Card.NONE, claimId, false, request.member(), memberIdRule, request.provider(), codes,
                request.dateOfService(), JSON.arrayNode(), JSON.arrayNode(), variables, notes, items);
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

    /** The moment the request is built, in whole seconds in UTC, as PAS Bundle.timestamp and Claim.created carry it. */
    private String instant() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * @param submission true for a PASClaim, false for a PASClaimInquiry
     * @param card       what the Service card states; {@link Card#NONE} for an inquiry
     */
    private Case caseResources(boolean submission, Card card, String claimId, boolean newClaim, MemberDetail member,
                               MemberIdRule memberIdRule, ProviderDetail provider, List<CodeInput> codes, String dateOfService, ArrayNode supportingInfo, ArrayNode diagnosis,
                               Function<String, ResolvedText> variables, PasNotes notes, List<SentClaim.Item> items) {
        String claimProfile = submission ? "PASClaim" : "PASClaimInquiry";
        ObjectNode insurer = PasResources.insurer(variables, claimProfile, notes);
        ObjectNode patient = resources.patient(member, memberIdRule, variables, notes);
        ObjectNode coverage = resources.coverage(member, memberIdRule, variables, insurer != null, notes);
        String requester = PasResources.requestorReference(provider);
        List<ObjectNode> requestor = PasResources.requestor(provider, variables, notes);
        PasResources.Servicing servicing = submission ? PasResources.servicing(card.servicingProvider(), provider, notes) : null;

        ObjectNode claim = JSON.objectNode();
        claim.put("resourceType", "Claim");
        claim.put("id", OnyxPasConventions.CLAIM_ID);
        if (!submission) {
            // The vendor's sandbox refused an inquiry whose Claim did not declare this profile.
            claim.putObject("meta").putArray("profile").add(OnyxPasConventions.CLAIM_INQUIRY_PROFILE);
        }
        String statedCertification = submission ? Texts.firstPresent(card.certificationType(),
                CaseResources.value(variables, VAR_CERTIFICATION_TYPE)) : null;
        // The one rule this tool has: an identifier minted for this submission makes it an initial request.
        boolean certificationByRule = submission && statedCertification == null && newClaim;
        String certification = certificationByRule ? OnyxPasConventions.CERTIFICATION_INITIAL : statedCertification;
        if (submission) {
            levelOfService(claim, card, certification, notes);
        }
        ObjectNode identifier = claim.putArray("identifier").addObject();
        String identifierSystem = CaseResources.value(variables, VAR_CLAIM_ID_SYSTEM);
        if (identifierSystem != null) {
            identifier.put("system", identifierSystem);
        } else if (!notes.guide().is221()) {
            notes.add("The claim identifier was sent without an identifier system: the variable " + VAR_CLAIM_ID_SYSTEM + " is not set. "
                    + "PAS 2.0.1 does not require one.");
        } else {
            notes.missing(claimProfile + " identifier.system", "The claim identifier was sent without an identifier system: the variable "
                    + VAR_CLAIM_ID_SYSTEM + " is not set. " + notes.pas() + " gives the claim identifier the profile PASIdentifier, which requires "
                    + "a system: add " + VAR_CLAIM_ID_SYSTEM + UNDER_VALUES + ".");
        }
        identifier.put("value", claimId);
        claim.put("status", "active");
        claim.putObject("type").putArray("coding").addObject().put("system", OnyxPasConventions.CLAIM_TYPES)
                .put("code", chosen(variables, VAR_CLAIM_TYPE, OnyxPasConventions.DEFAULT_CLAIM_TYPE, "claim type", notes));
        claim.put("use", "preauthorization");
        claim.putObject("patient").put("reference", PasResources.patientRef(member));
        claim.put("created", instant());
        if (insurer != null) {
            claim.putObject("insurer").put("reference", PasResources.INSURER_REF);
        }
        claim.putObject("provider").put("reference", requester);
        claim.putObject("priority").putArray("coding").addObject().put("system", OnyxPasConventions.PROCESS_PRIORITIES)
                .put("code", chosen(variables, VAR_CLAIM_PRIORITY, OnyxPasConventions.DEFAULT_CLAIM_PRIORITY, "claim priority", notes));
        if (servicing != null) {
            ObjectNode careTeamMember = claim.putArray("careTeam").addObject();
            careTeamMember.putArray("extension").addObject().put("url", OnyxPasConventions.CARE_TEAM_CLAIM_SCOPE).put("valueBoolean", true);
            careTeamMember.put("sequence", 1);
            careTeamMember.putObject("provider").put("reference", servicing.reference());
            careTeamMember.set("role", x12(OnyxPasConventions.X12_ENTITY_TYPES, OnyxPasConventions.SERVICING_PROVIDER_ROLE));
            notes.add("The servicing provider is the claim's care team member 1, for the whole claim (careTeamClaimScope true), in the "
                    + "role " + OnyxPasConventions.SERVICING_PROVIDER_ROLE + ", service provider (X12 98).");
        }
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
            String serviceType = Texts.firstPresent(card.serviceType(), CaseResources.value(variables, VAR_SERVICE_TYPE));
            String requestType = Texts.firstPresent(card.requestType(), CaseResources.value(variables, VAR_REQUEST_TYPE));
            String traceSystem = CaseResources.value(variables, VAR_ITEM_TRACE_SYSTEM);
            ArrayNode itemArray = claim.putArray("item");
            int sequence = 1;
            for (CodeInput code : codes) {
                ObjectNode item = itemArray.addObject();
                ArrayNode extensions = item.putArray("extension");
                // The trace number is this tool's own: the claim identifier and the item's sequence,
                // so an inquiry about the same claim names each item by the same number.
                String traceNumber = claimId + "-" + sequence;
                ObjectNode trace = extensions.addObject().put("url", OnyxPasConventions.ITEM_TRACE_NUMBER).putObject("valueIdentifier");
                if (traceSystem != null) {
                    trace.put("system", traceSystem);
                }
                trace.put("value", traceNumber);
                if (requestType != null) {
                    extensions.addObject().put("url", OnyxPasConventions.ITEM_REQUEST_TYPE).set("valueCodeableConcept",
                            x12(OnyxPasConventions.X12_REQUEST_TYPES, requestType));
                }
                if (certification != null) {
                    extensions.addObject().put("url", OnyxPasConventions.ITEM_CERTIFICATION_TYPE).set("valueCodeableConcept",
                            x12(OnyxPasConventions.X12_CERTIFICATION_TYPES, certification));
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
                if (submission && card.placeOfService() != null) {
                    item.set("locationCodeableConcept", coding(OnyxPasConventions.PLACE_OF_SERVICE, card.placeOfService()));
                }
                if (submission) {
                    quantity(item, code, notes);
                }
                items.add(new SentClaim.Item(sequence, code.code().strip(), traceNumber));
                sequence++;
            }
            if (traceSystem == null && notes.guide().is221()) {
                notes.missing(claimProfile + " item.extension[itemTraceNumber] system", "The item trace numbers were sent without an "
                        + "identifier system: the variable " + VAR_ITEM_TRACE_SYSTEM + " is not set. " + notes.pas() + " gives an item trace number "
                        + "the profile PASIdentifier, which requires a system: add " + VAR_ITEM_TRACE_SYSTEM + UNDER_VALUES + ".");
            }
            itemNotes(submission, card, certification, certificationByRule, serviceType, requestType, dateOfService, notes);
        }

        List<ObjectNode> out = new ArrayList<>();
        out.add(claim);
        out.add(patient);
        out.add(coverage);
        if (insurer != null) {
            out.add(insurer);
        }
        out.addAll(requestor);
        if (servicing != null) {
            out.addAll(servicing.resources());
        }
        out.addAll(orders);
        return new Case(out);
    }

    /** The one note that names the guide the request is held against, and where a tester changes it. */
    private static String guideNote(PasNotes notes) {
        return "This request follows " + notes.guide().fullName() + ", the PAS guide of the environment (Configuration > "
                + "Environments, Request shapes).";
    }

    /** What the items went out with and without, said once for all of them. */
    private static void itemNotes(boolean submission, Card card, String certification, boolean certificationByRule, String serviceType,
                                  String requestType, String dateOfService, PasNotes notes) {
        if (serviceType == null) {
            if (submission) {
                notes.toComplete("serviceType", "Service type: none. " + notes.pas() + " requires one on every item: " + ON_THE_CARD
                        + ", or add PAS service type" + UNDER_VALUES + ".");
                notes.missing("PASClaim item.category", "No service type was sent on the items: none was chosen on the Service card and the "
                        + "value " + VAR_SERVICE_TYPE + " is not set. " + notes.pas() + " PASClaim requires item.category, an X12 service type code "
                        + "such as 1 for medical care: " + ON_THE_CARD + ", or add PAS service type" + UNDER_VALUES + ".");
            } else {
                notes.add("No service type was sent on the items: the variable " + VAR_SERVICE_TYPE + " is not set. An inquiry does "
                        + "not require one.");
            }
        }
        if (requestType == null) {
            if (submission && !notes.guide().is221()) {
                notes.add("No request type was sent on the items: none was chosen on the Service card and the value " + VAR_REQUEST_TYPE
                        + " is not set. PAS 2.0.1 PASClaim marks it must-support; it is an X12 request type code, such as HS for health "
                        + "services review: " + ON_THE_CARD + ".");
            } else if (submission) {
                notes.toComplete("requestType", "Request type: none. " + notes.pas() + " requires one on every item: " + ON_THE_CARD
                        + ", or add PAS request type" + UNDER_VALUES + ".");
                notes.missing("PASClaim item.extension[requestType]", "No request type was sent on the items: none was chosen on the Service "
                        + "card and the value " + VAR_REQUEST_TYPE + " is not set. " + notes.pas() + " PASClaim requires it on every item, an X12 "
                        + "request type code such as HS for health services review: " + ON_THE_CARD + ", or add PAS request type"
                        + UNDER_VALUES + ".");
            } else {
                notes.add("No request type was sent on the items: the variable " + VAR_REQUEST_TYPE + " is not set. An inquiry does not "
                        + "require one.");
            }
        }
        if (!submission) {
            return;
        }
        if (certification == null && !notes.guide().is221()) {
            notes.add("No certification type was sent on the items: none was chosen on the Service card, the value "
                    + VAR_CERTIFICATION_TYPE + " is not set, and the claim identifier was typed or handed on, so whether this is an "
                    + "initial request or a revision of one is not known. PAS 2.0.1 PASClaim marks it must-support: " + ON_THE_CARD + ".");
        } else if (certification == null) {
            notes.toComplete("certificationType", "Certification type: none. " + notes.pas() + " requires one on every item, and a typed claim id "
                    + "does not say whether the request is initial: " + ON_THE_CARD + ", or add PAS certification type" + UNDER_VALUES + ".");
            notes.missing("PASClaim item.extension[certificationType]", "No certification type was sent on the items: none was chosen on "
                    + "the Service card, the value " + VAR_CERTIFICATION_TYPE + " is not set, and the claim identifier was typed or handed "
                    + "on, so whether this is an initial request or a revision of one is not known. " + notes.pas() + " PASClaim requires it on "
                    + "every item: " + ON_THE_CARD + ", or add PAS certification type" + UNDER_VALUES + ".");
        } else if (certificationByRule) {
            notes.add("Each item was sent with the certification type I (initial), because the claim identifier was minted for "
                    + "this submission.");
        }
        if (card.placeOfService() == null && !notes.guide().is221()) {
            notes.add("No place of service was sent on the items: none was chosen on the Service card. PAS 2.0.1 PASClaim marks "
                    + "item.location[x] must-support.");
        } else if (card.placeOfService() == null) {
            notes.toComplete("placeOfService", "Place of service: none. " + notes.pas() + " requires one on every item: " + ON_THE_CARD + ".");
            notes.missing("PASClaim item.location[x]", "No place of service was sent on the items: none was chosen on the Service card. "
                    + notes.pas() + " PASClaim requires one on every item, a CMS place of service code such as 11 for office: "
                    + ON_THE_CARD + ".");
        }
        notes.add("Each item names its order by the requestedService extension, and each order travels in the bundle as "
                + "ServiceRequest/service-request-N, under the id CRD and DTR gave it.");
        if (Texts.blank(dateOfService)) {
            notes.add("No date of service was sent on the items, because none was supplied.");
        }
    }

    /**
     * The level of service on the Claim, when the Service card states one. PAS 2.2.1 rule
     * ImmediateLevelOfCare requires it when the certification type is 1, an immediate appeal; PAS
     * 2.0.1 has no such rule and marks the extension must-support only.
     */
    private static void levelOfService(ObjectNode claim, Card card, String certification, PasNotes notes) {
        if (card.levelOfService() != null) {
            claim.putArray("extension").addObject().put("url", OnyxPasConventions.LEVEL_OF_SERVICE).set("valueCodeableConcept",
                    x12(OnyxPasConventions.X12_LEVELS_OF_SERVICE, card.levelOfService()));
            return;
        }
        if ("1".equals(certification) && notes.guide().is221()) {
            notes.missing("PASClaim extension[levelOfServiceType] (rule ImmediateLevelOfCare)", "No level of service was sent, and the "
                    + "certification type is 1, an immediate appeal. " + notes.pas() + " rule ImmediateLevelOfCare requires a level of service "
                    + "then: " + ON_THE_CARD + ".");
        } else {
            notes.add("No level of service was sent: none was chosen on the Service card. " + notes.pas() + " PASClaim marks it must-support.");
        }
    }

    /** The item's quantity (PASQuantity under 2.2.1, a FHIR Quantity under 2.0.1, which has no profile for it), and a note when a unit came without a quantity or a quantity without a unit. */
    private static void quantity(ObjectNode item, CodeInput code, PasNotes notes) {
        ObjectNode quantity = PasResources.quantity(code);
        String value = code.code().strip();
        if (quantity != null) {
            item.set("quantity", quantity);
            if (Texts.blank(code.unit())) {
                notes.add("The quantity of the code " + value + " was sent with no unit, because none was chosen.");
            }
        } else if (Texts.present(code.unit())) {
            notes.add("The unit " + code.unit().strip() + " given with the code " + value + " was not sent, because no quantity came with "
                    + "it. " + (notes.guide().is221() ? "PAS 2.2.1 PASQuantity requires a value." : "A quantity without a value says nothing."));
        }
    }

    private static ObjectNode x12(String system, String code) {
        return coding(system, code);
    }

    private static ObjectNode coding(String system, String code) {
        ObjectNode concept = JSON.objectNode();
        concept.putArray("coding").addObject().put("system", system).put("code", code.strip());
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

    /**
     * One additionalInformation entry of the claim's supporting information. Its documentInformation
     * extension is written only when a report type is stated and the guide is 2.2.1 (PAS 2.0.1 has no
     * such extension), because PAS 2.2.1 requires a report
     * type inside it and none is assumed; the caller counts its absence once for all entries.
     *
     * @param reportType    the X12 PWK01 report type code, or null when none is stated
     * @param controlNumber the attachment control number a 275 would carry, or null when the document travels in the bundle
     */
    private static ObjectNode supporting(int sequence, ObjectNode reference, String reportType, String controlNumber) {
        ObjectNode info = JSON.objectNode();
        if (reportType != null) {
            ObjectNode documentInformation = info.putArray("extension").addObject();
            documentInformation.put("url", OnyxPasConventions.DOCUMENT_INFORMATION);
            ArrayNode parts = documentInformation.putArray("extension");
            parts.addObject().put("url", "reportTypeCode").set("valueCodeableConcept", x12(OnyxPasConventions.X12_REPORT_TYPES, reportType));
            parts.addObject().put("url", "transmissionMethod").set("valueCodeableConcept",
                    x12(OnyxPasConventions.X12_TRANSMISSION_METHODS, OnyxPasConventions.TRANSMISSION_ELECTRONIC));
            if (controlNumber != null) {
                parts.addObject().put("url", "controlNumber").put("valueString", controlNumber);
            }
        }
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
     * that PAS binds the element, required, to a value set the code then cannot be shown to be in.
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
                    + "worked out for the diagnosis " + value + ", so it was sent without one. " + notes.pas() + " binds the diagnosis, required, to "
                    + "the value set X12278DiagnosisCodes, so the service may refuse it. State its system with the code.");
        }
        coding.put("code", value);
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        return concept;
    }

    /**
     * The bundle. PAS 2.0.1 and 2.2.1 require a full url on every entry: the address the resource has on
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
        bundle.put("timestamp", instant());
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
            notes.add("Every entry carries a full url of the form urn:uuid:, as " + notes.pas() + " allows when the resources have no address of "
                    + "their own (the variable " + CaseResources.VAR_FHIR_SERVER + " is not set), and every reference between the "
                    + "resources names one of those full urls.");
        }
        if (!unresolved.isEmpty()) {
            for (String reference : unresolved) {
                notes.missing(bundleProfile + " entry for " + reference, null);
            }
            notes.add("These references inside the bundle name a resource the bundle does not carry: " + String.join(", ", unresolved)
                    + ". " + notes.pas() + " requires (SHALL) that the bundle carry every resource the claim refers to, and every resource those "
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
