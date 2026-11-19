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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the request bundle for a PAS submission and for an inquiry from the form.
 *
 * <p>The member, the coverage, the provider and each procedure code are described by
 * {@link CaseResources}, exactly as CRD and DTR describe them, so the payer sees one case from the
 * first step of a chain to the last. What this class adds is the Claim that ties them together,
 * the questionnaire answers an earlier step handed on, and the documents the tester attached.
 *
 * <p>Nothing is filled in that was not supplied. Where an element needs a value this tool does not
 * have, the element is left out and a note says so. Where the tool had to choose a value, such as
 * the claim type, the note says which value and how to change it. The notes are shown beside the
 * request, so a rejection caused by something the tool did has a visible cause.
 *
 * <p>How an attachment travels follows the configured attachment mode. Both shapes are assumptions
 * and are described in {@link OnyxPasConventions}.
 */
public final class PasRequestBuilder {

    /** Placeholder names the claim reads beyond the ones {@link CaseResources} reads. Each is optional. */
    public static final String VAR_CLAIM_ID_SYSTEM = "claimIdentifierSystem";
    public static final String VAR_CLAIM_TYPE = "claimType";
    public static final String VAR_CLAIM_PRIORITY = "claimPriority";
    public static final String VAR_ATTACHMENT_NUMBER_SYSTEM = "attachmentControlNumberSystem";

    /** The identifier system for a value that is itself a uri, which is how FHIR writes a UUID as an identifier. */
    private static final String URI_SYSTEM = "urn:ietf:rfc:3986";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * The request body, what was left out of it, and what it carried.
     *
     * @param json           the request as pretty JSON
     * @param notes          one plain sentence for each thing left out or worked out
     * @param sent           the claim identifier and each item sent, so a decision can be tied to its code
     * @param attachmentMode how the attachments of the request travel, or null when it has none
     */
    public record Built(String json, List<String> notes, SentClaim sent, String attachmentMode) {

        /** Redacted, because the body carries member data. */
        @Override
        public String toString() {
            return "Built[items=" + sent.items().size() + ", attachmentMode=" + attachmentMode + "]";
        }
    }

    private final CaseResources resources;
    private final String attachmentMode;
    private final Clock clock;

    /**
     * @param codeList       consulted for a code's system when the tester did not state one. May be null
     * @param attachmentMode fhir or x12-275, as configured and checked at startup
     */
    public PasRequestBuilder(ProcedureCodeDirectory codeList, String attachmentMode, Clock clock) {
        this.resources = new CaseResources(codeList);
        this.attachmentMode = attachmentMode;
        this.clock = clock;
    }

    /**
     * @param claimId   the identifier the claim carries
     * @param bundleId  the identifier of this one bundle, unique for each submission
     * @param variables resolves a placeholder name to its value, or to an unresolved result
     */
    public Built submit(PasSubmitRequest request, String claimId, String bundleId, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
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
                        + StartupChecks.ATTACHMENT_X12_275 + " a document travels separately in an X12 275 transaction, which this tool "
                        + "does not send. The claim names it by the attachment control number " + number + ".");
            } else {
                String id = OnyxPasConventions.documentReferenceId(position);
                boolean inline = Texts.present(attachment.text());
                carried.add(documentReference(id, title, attachment));
                supportingInfo.add(supporting(supportingInfo.size() + 1, JSON.objectNode().put("reference", "DocumentReference/" + id)));
                notes.add("Attachment " + position + " (\"" + title + "\") travels in the bundle as DocumentReference/" + id
                        + (inline ? ", with its content inline." : ", by its url.") + " No document type code was sent: this tool has none.");
            }
            position++;
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
        notes.add("The claim carries the elements base FHIR requires and the ones this tool has values for. Further elements the PAS "
                + "profile defines, such as the request type and the certification type of an item, were not sent: this tool has no "
                + "values for them.");

        List<SentClaim.Item> items = new ArrayList<>();
        List<ObjectNode> bundled = caseResources(claimId, request.member(), request.provider(), request.procedureCodes(),
                request.dateOfService(), supportingInfo, diagnosis, variables, notes, items);
        bundled.addAll(carried);
        return new Built(bundle(bundleId, bundled, variables, notes), List.copyOf(notes), new SentClaim(claimId, items),
                request.attachments().isEmpty() ? null : attachmentMode);
    }

    /**
     * The bundle an inquiry sends: a claim that names the request by its identifier, with the
     * member and the provider. When procedure codes are given they are listed as items in the
     * order given, which must be the order the submission sent them in, so that each decision of
     * the answer can be tied to its code.
     */
    public Built inquire(PasInquireRequest request, String claimId, String bundleId, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
        notes.add(request.procedureCodes().isEmpty()
                ? "The inquiry names the request by its claim identifier and lists no item, so it asks about the request as a whole."
                : "The inquiry names the request by its claim identifier and lists the procedure codes it was given as items, in the "
                + "order given. That has to be the order the submission sent them in for each decision to be tied to its code.");
        List<SentClaim.Item> items = new ArrayList<>();
        List<ObjectNode> bundled = caseResources(claimId, request.member(), request.provider(), request.procedureCodes(),
                request.dateOfService(), JSON.arrayNode(), JSON.arrayNode(), variables, notes, items);
        return new Built(bundle(bundleId, bundled, variables, notes), List.copyOf(notes), new SentClaim(claimId, items), null);
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

    /** The Claim and the resources it refers to, in the order they go into the bundle. */
    private List<ObjectNode> caseResources(String claimId, MemberDetail member, ProviderDetail provider, List<CodeInput> codes,
                                           String dateOfService, ArrayNode supportingInfo, ArrayNode diagnosis,
                                           Function<String, ResolvedText> variables, List<String> notes, List<SentClaim.Item> items) {
        ObjectNode patient = resources.patient(member, variables, notes);
        ObjectNode coverage = resources.coverage(member, variables, notes);
        ObjectNode insurer = CaseResources.insurer(variables, notes);

        ObjectNode claim = JSON.objectNode();
        claim.put("resourceType", "Claim");
        claim.put("id", OnyxPasConventions.CLAIM_ID);
        ObjectNode identifier = claim.putArray("identifier").addObject();
        String identifierSystem = CaseResources.value(variables, VAR_CLAIM_ID_SYSTEM);
        if (identifierSystem != null) {
            identifier.put("system", identifierSystem);
        } else {
            notes.add("The claim identifier was sent without an identifier system: the variable " + VAR_CLAIM_ID_SYSTEM + " is not set.");
        }
        identifier.put("value", claimId);
        claim.put("status", "active");
        claim.putObject("type").putArray("coding").addObject().put("system", OnyxPasConventions.CLAIM_TYPES)
                .put("code", chosen(variables, VAR_CLAIM_TYPE, OnyxPasConventions.DEFAULT_CLAIM_TYPE, "claim type", notes));
        claim.put("use", "preauthorization");
        claim.putObject("patient").put("reference", CaseResources.PATIENT_REF);
        claim.put("created", RunStamps.timestamp(clock));
        if (insurer != null) {
            claim.putObject("insurer").put("reference", CaseResources.INSURER_REF);
        }
        claim.putObject("provider").put("reference", CaseResources.PRACTITIONER_ROLE_REF);
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

        if (!codes.isEmpty()) {
            ArrayNode itemArray = claim.putArray("item");
            int sequence = 1;
            for (CodeInput code : codes) {
                ObjectNode item = itemArray.addObject();
                item.put("sequence", sequence);
                item.set("productOrService", resources.codeableConcept(code, member.lineOfBusiness(), notes));
                if (Texts.present(dateOfService)) {
                    item.put("servicedDate", dateOfService.strip());
                }
                items.add(new SentClaim.Item(sequence, code.code().strip()));
                sequence++;
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
        out.add(CaseResources.practitioner(provider));
        out.add(CaseResources.practitionerRole());
        return out;
    }

    /**
     * A value the request must carry and the tester may state as a variable. When they have not,
     * the tool's own default is sent and the note says so, because a default nobody is told about
     * is a guess.
     */
    private static String chosen(Function<String, ResolvedText> variables, String variable, String fallback, String what,
                                 List<String> notes) {
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

    private static ObjectNode documentReference(String id, String title, PasAttachment attachment) {
        ObjectNode document = JSON.objectNode();
        document.put("resourceType", "DocumentReference");
        document.put("id", id);
        document.put("status", OnyxPasConventions.DOCUMENT_STATUS);
        document.putObject("subject").put("reference", CaseResources.PATIENT_REF);
        document.put("description", title);
        ObjectNode content = document.putArray("content").addObject().putObject("attachment");
        content.put("contentType", attachment.contentType().strip());
        content.put("title", title);
        if (Texts.present(attachment.text())) {
            content.put("data", Base64.getEncoder().encodeToString(attachment.text().getBytes(StandardCharsets.UTF_8)));
        } else {
            content.put("url", attachment.url().strip());
        }
        return document;
    }

    /**
     * A diagnosis code as a CodeableConcept. Its coding system is the one the tester stated, else
     * ICD-10-CM when the code has that format, which is a rule about a published code format and
     * is still reported. A code of any other format is sent without a system, and the note says so.
     */
    private static ObjectNode diagnosisConcept(CodeInput code, List<String> notes) {
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        String value = code.code().strip();
        if (Texts.present(code.system())) {
            coding.put("system", code.system().strip());
        } else if (value.toUpperCase(Locale.ROOT).matches("[A-TV-Z][0-9][0-9AB](\\.?[0-9A-TV-Z]{1,4})?")) {
            coding.put("system", OnyxPasConventions.ICD_10_CM);
            notes.add("The coding system for the diagnosis " + value + " was taken from its format: ICD-10-CM.");
        } else {
            notes.add("No coding system could be worked out for the diagnosis " + value + ", so it was sent without one.");
        }
        coding.put("code", value);
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        return concept;
    }

    /**
     * The bundle. Each entry gets a full url only when the tester states where the resources live,
     * because inventing a base address would put a url on the wire that resolves to nothing.
     */
    private String bundle(String bundleId, List<ObjectNode> bundled, Function<String, ResolvedText> variables, List<String> notes) {
        ObjectNode bundle = JSON.objectNode();
        bundle.put("resourceType", "Bundle");
        bundle.putObject("identifier").put("system", URI_SYSTEM).put("value", bundleId);
        bundle.put("type", "collection");
        bundle.put("timestamp", RunStamps.timestamp(clock));
        ArrayNode entries = bundle.putArray("entry");
        String base = CaseResources.value(variables, CaseResources.VAR_FHIR_SERVER);
        for (JsonNode resource : bundled) {
            ObjectNode entry = entries.addObject();
            if (base != null) {
                entry.put("fullUrl", base.replaceAll("/+$", "") + "/" + JsonSafe.text(resource, "resourceType") + "/"
                        + JsonSafe.text(resource, "id"));
            }
            entry.set("resource", resource);
        }
        if (base == null) {
            notes.add("The bundle entries were sent without a fullUrl: the variable " + CaseResources.VAR_FHIR_SERVER + " is not set. The "
                    + "references between the resources are relative, such as " + CaseResources.PATIENT_REF + ".");
        }
        return JsonSafe.pretty(bundle);
    }
}
