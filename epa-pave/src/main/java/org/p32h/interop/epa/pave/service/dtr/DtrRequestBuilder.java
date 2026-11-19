package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson.Item;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds what the DTR steps send, and the QuestionnaireResponse a response step puts together.
 *
 * <p>A package request names the coverage and the order. Both come from {@link CaseResources},
 * which CRD builds from as well, so the payer is sent the same coverage and the same orders under
 * the same ids it was sent by CRD. That is what lets it match the request to the answer it gave.
 *
 * <p>Nothing is filled in that was not supplied. Whatever is left out, or taken from what CRD
 * handed on, becomes a note shown beside the request.
 */
public final class DtrRequestBuilder {

    /**
     * The body of a package request and what is known about it.
     *
     * @param json       the request as pretty JSON
     * @param notes      one plain sentence for each thing left out, worked out or taken from CRD
     * @param canonicals the questionnaires the request asks for by canonical url. Empty when it names none
     * @param codes      the procedure codes the case is about, which the step after this one starts from
     * @param sentCodes  the procedure codes of the orders the request carries. Fewer than the
     *                   case's codes when CRD's answer applied to some of its orders only
     */
    public record Package(String json, List<String> notes, List<String> canonicals, List<String> codes, List<String> sentCodes) {

        public Package {
            notes = notes == null ? List.of() : List.copyOf(notes);
            canonicals = canonicals == null ? List.of() : List.copyOf(canonicals);
            codes = codes == null ? List.of() : List.copyOf(codes);
            sentCodes = sentCodes == null ? List.of() : List.copyOf(sentCodes);
        }

        /** Redacted, because the body carries member data. */
        @Override
        public String toString() {
            return "Package[questionnaires=" + canonicals.size() + ", orders=" + sentCodes.size() + "]";
        }
    }

    /**
     * What a step of the adaptive loop sends.
     *
     * @param response the QuestionnaireResponse so far, with the answers being added in it
     * @param json     the request body as pretty JSON
     */
    public record LoopBody(JsonNode response, String json, List<String> notes) {

        /** Redacted, because the response carries the tester's answers, which can be clinical. */
        @Override
        public String toString() {
            return "LoopBody[notes=" + (notes == null ? 0 : notes.size()) + "]";
        }
    }

    /**
     * Who is filling a response in, and for which case. A step that sends none of this as a resource
     * still needs it: the DTR guide has a client say on each answer a person gives who gave it, and
     * on a response which coverage and which order it is for.
     *
     * @param orders the references of the orders the case is about, such as
     *               {@code ServiceRequest/service-request-1}, under the ids this tool gives them in
     *               every request it builds
     */
    public record Filler(MemberDetail member, ProviderDetail provider, List<String> orders) {

        public Filler {
            orders = orders == null ? List.of() : List.copyOf(orders);
        }

        /** Nobody in particular: a response is still built, and says less. */
        public static Filler of(MemberDetail member) {
            return new Filler(member, null, List.of());
        }

        boolean namesAProvider() {
            return provider != null && Texts.present(provider.npi());
        }

        boolean namesAMember() {
            return member != null && Texts.present(member.memberId());
        }

        /** Redacted, because it holds the member and the provider. */
        @Override
        public String toString() {
            return "Filler[orders=" + orders.size() + "]";
        }
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final CaseResources resources;

    /** @param codeList consulted for a code's system when the tester did not state one. May be null */
    public DtrRequestBuilder(ProcedureCodeDirectory codeList) {
        this.resources = new CaseResources(codeList);
    }

    /** The procedure codes a package request is about: those on the form, else those CRD was asked about. */
    public static List<CodeInput> codes(DtrPackageRequest request) {
        if (!request.procedureCodes().isEmpty()) {
            return request.procedureCodes();
        }
        return carried(request.carry(), NextStepView.CARRY_PROCEDURE_CODES).stream().map(CodeInput::of).toList();
    }

    /** The questionnaires a package request asks for: those on the form, else those CRD named. */
    public static List<String> canonicals(DtrPackageRequest request) {
        List<String> typed = clean(request.questionnaires());
        return typed.isEmpty() ? carried(request.carry(), NextStepView.CARRY_QUESTIONNAIRE) : typed;
    }

    /**
     * @param variables resolves a placeholder name to its value, or to an unresolved result
     */
    public Package buildPackage(DtrPackageRequest request, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
        Map<String, List<String>> carry = request.carry();
        MemberDetail member = request.member();

        ObjectNode root = JSON.objectNode();
        root.put("resourceType", "Parameters");
        ArrayNode parameters = root.putArray("parameter");
        parameters.addObject().put("name", OnyxDtrConventions.PARAM_COVERAGE).set("resource", resources.coverage(member, variables, notes));

        List<CodeInput> codes = codes(request);
        if (request.procedureCodes().isEmpty() && !codes.isEmpty()) {
            notes.add("The procedure codes CRD was asked about were used, because the form names none: "
                    + String.join(", ", codes.stream().map(CodeInput::code).toList()) + ".");
        }
        // Each code gets the order id CRD gave it, so an order here is the order CRD answered about.
        List<String> carriedOrders = carried(carry, NextStepView.CARRY_ORDER);
        List<Integer> chosen = new ArrayList<>();
        for (int position = 1; position <= codes.size(); position++) {
            if (carriedOrders.contains(CaseResources.serviceRequestRef(position)) || carriedOrders.contains(CaseResources.serviceRequestId(position))) {
                chosen.add(position);
            }
        }
        if (!carriedOrders.isEmpty() && chosen.isEmpty()) {
            notes.add("CRD handed on the order " + String.join(", ", carriedOrders) + ", which is not one of the orders this tool builds "
                    + "from the procedure codes, so every order was sent.");
        } else if (!chosen.isEmpty() && chosen.size() < codes.size()) {
            notes.add("Only the " + (chosen.size() == 1 ? "order" : "orders") + " CRD's answer applied to "
                    + (chosen.size() == 1 ? "was" : "were") + " sent: "
                    + String.join(", ", chosen.stream().map(CaseResources::serviceRequestRef).toList()) + ".");
        } else if (!chosen.isEmpty()) {
            notes.add("The order CRD's answer applied to was sent, under the id CRD sent it with.");
        }
        List<String> sentCodes = new ArrayList<>();
        for (int position = 1; position <= codes.size(); position++) {
            if (chosen.isEmpty() || chosen.contains(position)) {
                parameters.addObject().put("name", OnyxDtrConventions.PARAM_ORDER).set("resource",
                        resources.serviceRequest(CaseResources.serviceRequestId(position), codes.get(position - 1), member,
                                request.dateOfService(), notes));
                sentCodes.add(codes.get(position - 1).code().strip());
            }
        }
        List<String> typed = clean(request.questionnaires());
        List<String> canonicals = canonicals(request);
        String typedAssertion = Texts.trimToNull(request.coverageAssertionId());
        List<String> carriedAssertions = carried(carry, NextStepView.CARRY_COVERAGE_ASSERTION_ID);
        String assertion = typedAssertion != null ? typedAssertion : carriedAssertions.isEmpty() ? null : carriedAssertions.get(0);
        if (codes.isEmpty()) {
            String askedBy = !canonicals.isEmpty()
                    ? "The service is asked for the questionnaires by their canonical url" + (assertion == null ? " alone." : ", with the context.")
                    : assertion != null ? "The service is asked by the context alone."
                    : "The request gives the service nothing but the coverage to choose a questionnaire by.";
            notes.add("No order was sent, because the request names no procedure code. " + askedBy);
        }
        // What the orders and the coverage refer to travels with them, so no reference is left pointing at nothing.
        parameters.addObject().put("name", OnyxDtrConventions.PARAM_REFERENCED).set("resource", resources.patient(member, variables, notes));
        parameters.addObject().put("name", OnyxDtrConventions.PARAM_REFERENCED).set("resource", CaseResources.practitionerRole());
        parameters.addObject().put("name", OnyxDtrConventions.PARAM_REFERENCED).set("resource", CaseResources.practitioner(request.provider()));

        for (String canonical : canonicals) {
            parameters.addObject().put("name", OnyxDtrConventions.PARAM_QUESTIONNAIRE).put("valueCanonical", canonical);
        }
        if (canonicals.isEmpty()) {
            notes.add("The request names no questionnaire, so the service chooses from the coverage"
                    + (!codes.isEmpty() ? " and the order." : assertion != null ? " and the context." : "."));
        } else {
            notes.add("The request asks for the " + (canonicals.size() == 1 ? "questionnaire" : "questionnaires") + " "
                    + (typed.isEmpty() ? "CRD named" : "given on the form") + ": " + String.join(", ", canonicals) + ".");
        }

        if (assertion == null) {
            notes.add("No context was sent: no coverage assertion id was handed on by CRD or given on the form.");
        } else {
            parameters.addObject().put("name", OnyxDtrConventions.PARAM_CONTEXT).put("valueString", assertion);
            notes.add(typedAssertion != null ? "The coverage assertion id given on the form was sent as the context."
                    : "The coverage assertion id CRD handed on was sent as the context."
                    + (carriedAssertions.size() > 1 ? " CRD handed on " + carriedAssertions.size()
                    + " of them. The first was sent, because the operation takes one." : ""));
        }
        if (!carried(carry, NextStepView.CARRY_DTR_LINK_URL).isEmpty() || !carried(carry, NextStepView.CARRY_DTR_APP_CONTEXT).isEmpty()) {
            notes.add("CRD handed on a SMART link for DTR. This tool does not launch it and does not read its appContext: it hosts no "
                    + "SMART launch. The questionnaire is asked for through the questionnaire package operation.");
        }
        return new Package(JsonSafe.pretty(root), notes, canonicals, codes.stream().map(code -> code.code().strip()).toList(), sentCodes);
    }

    /**
     * What a package request the tester supplied asks about, read from the body itself: the
     * questionnaires it names and the procedure codes on its orders. Both are empty when the body
     * is not JSON or is not a Parameters resource. Nothing is then said to have been asked for, so
     * nothing can be reported as not returned.
     */
    public static Package readPackage(String body) {
        List<String> canonicals = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        JsonSafe.Parsed parsed = JsonSafe.parse(body);
        if (parsed.ok() && QuestionnaireJson.isType(parsed.node(), "Parameters")) {
            for (JsonNode parameter : JsonSafe.elements(parsed.node(), "parameter")) {
                String name = JsonSafe.presentText(parameter, "name");
                if (OnyxDtrConventions.PARAM_QUESTIONNAIRE.equals(name)) {
                    String key = QuestionnaireJson.valueKey(parameter, "value");
                    String canonical = key == null ? null : JsonSafe.presentText(parameter, key);
                    if (canonical != null && !canonicals.contains(canonical)) {
                        canonicals.add(canonical);
                    }
                } else if (OnyxDtrConventions.PARAM_ORDER.equals(name)) {
                    for (String code : SentOrder.codes(JsonSafe.child(parameter, "resource"))) {
                        if (!codes.contains(code)) {
                            codes.add(code);
                        }
                    }
                }
            }
        }
        return new Package(body, List.of(), canonicals, codes, codes);
    }

    /**
     * The QuestionnaireResponse a step of the adaptive loop sends: the response so far with the
     * answers being added, or for a first step a new response that carries the questionnaire
     * inside itself, which is where the service puts the questions as it asks them.
     *
     * @param posted what the request carried, already checked and free of errors
     */
    public LoopBody buildNextQuestion(DtrAnswers.Posted posted, MemberDetail member, Clock clock) {
        return buildNextQuestion(posted, Filler.of(member), clock, null);
    }

    /**
     * @param filler          who is answering and for which case, so each answer added says who gave it
     * @param wrapInParameter blank to send the QuestionnaireResponse by itself, otherwise the name
     *                        of the parameter to send it under inside a Parameters resource
     */
    public LoopBody buildNextQuestion(DtrAnswers.Posted posted, Filler filler, Clock clock, String wrapInParameter) {
        List<String> notes = new ArrayList<>();
        ObjectNode response;
        if (posted.response() != null) {
            response = (ObjectNode) posted.response().deepCopy();
            if (QuestionnaireJson.containedQuestionnaire(response) == null) {
                contain(response, posted.questionnaire(), notes);
            }
            String status = JsonSafe.presentText(response, "status");
            if (!posted.given().isEmpty() && !OnyxDtrConventions.STATUS_IN_PROGRESS.equals(status)) {
                // A response the service called complete, with an answer changed, is no longer the response it called complete.
                notes.add(status == null ? "The response so far stated no status. It was sent as in-progress."
                        : "The response so far had the status " + status + ". An answer was added or changed, so it was sent as in-progress.");
                response.put("status", OnyxDtrConventions.STATUS_IN_PROGRESS);
            }
        } else {
            response = JSON.objectNode();
            response.put("resourceType", "QuestionnaireResponse");
            caseOf(response, filler, notes);
            contain(response, posted.questionnaire(), notes);
            response.put("status", OnyxDtrConventions.STATUS_IN_PROGRESS);
            subject(response, filler.member(), notes);
            response.put("authored", RunStamps.timestamp(clock));
        }
        JsonNode inside = QuestionnaireJson.containedQuestionnaire(response);
        Map<String, List<JsonNode>> answers = withOrigins(posted, filler, notes);
        // Every answer goes: in the adaptive loop the service decides which questions still apply.
        ArrayNode items = QuestionnaireJson.buildItems(QuestionnaireJson.items(inside), answers,
                QuestionnaireJson.responseItems(posted.response()), item -> true);
        if (items.isEmpty()) {
            response.remove("item");
        } else {
            response.set("item", items);
        }
        for (String linkId : withNoQuestion(QuestionnaireJson.items(inside), posted.merged())) {
            notes.add("The response so far holds an answer for " + linkId + ", which is not a question of its questionnaire. It was not "
                    + "sent: a response is built from the questionnaire it answers.");
        }
        if (Texts.present(wrapInParameter)) {
            notes.add("The QuestionnaireResponse was put inside a Parameters resource, under the parameter " + wrapInParameter.strip()
                    + ", as the request asked.");
        }
        return new LoopBody(response, JsonSafe.pretty(OnyxDtrConventions.nextQuestionBody(response, wrapInParameter)), List.copyOf(notes));
    }

    /**
     * The QuestionnaireResponse for a static questionnaire, put together from the answers given.
     * It starts from the response the package arrived with when there is one, so whatever the
     * payer put on it is kept. Only items the answers enable are included.
     *
     * @param evaluated the questionnaire evaluated against the answers in {@code posted}
     * @param complete  true to mark it completed, false to mark it in progress
     * @param filler    who is answering and for which case, so each answer added says who gave it
     * @param notes     receives one plain sentence for each thing left out or worked out
     */
    public ObjectNode buildResponse(DtrAnswers.Posted posted, EnableWhenEvaluator.Form evaluated, boolean complete, Filler filler,
                                    Clock clock, List<String> notes) {
        MemberDetail member = filler.member();
        ObjectNode response = posted.response() != null ? (ObjectNode) posted.response().deepCopy() : JSON.objectNode();
        if (!response.has("resourceType")) {
            response.put("resourceType", "QuestionnaireResponse");
        }
        if (posted.response() == null) {
            caseOf(response, filler, notes);
        }
        if (Texts.blank(JsonSafe.text(response, "questionnaire"))) {
            String canonical = QuestionnaireJson.canonical(posted.questionnaire());
            if (canonical != null) {
                response.put("questionnaire", canonical);
            } else {
                notes.add("The questionnaire has no url, so the response does not name the questionnaire it answers.");
            }
        }
        response.put("status", complete ? OnyxDtrConventions.STATUS_COMPLETED : OnyxDtrConventions.STATUS_IN_PROGRESS);
        if (!response.has("subject")) {
            subject(response, member, notes);
        }
        response.put("authored", RunStamps.timestamp(clock));
        ArrayNode items = QuestionnaireJson.buildItems(evaluated.roots(), withOrigins(posted, filler, notes),
                QuestionnaireJson.responseItems(posted.response()), evaluated::include);
        if (items.isEmpty()) {
            response.remove("item");
        } else {
            response.set("item", items);
        }
        for (QuestionRef left : evaluated.answersLeftOut()) {
            notes.add("The answer given to " + left.linkId() + " was left out of the response, because the answers given disable that question.");
        }
        for (String linkId : withNoQuestion(evaluated.roots(), posted.merged())) {
            notes.add("The response to start from holds an answer for " + linkId + ", which is not a question of this questionnaire. It was "
                    + "left out of the response.");
        }
        for (Item item : QuestionnaireJson.flatten(evaluated.roots())) {
            if (item.group() && item.repeats()) {
                notes.add("The group " + Texts.orElse(item.linkId(), item.path()) + " repeats. This tool answers it once: it does not put "
                        + "several instances of a group into a response.");
            }
        }
        return response;
    }

    /**
     * The linkIds that have an answer and no question to put it under: an answer is placed by
     * walking the questionnaire, so one whose linkId the questionnaire does not hold, or holds as a
     * group or as display text, has nowhere to go. It is named in a note and never dropped in silence.
     */
    private static List<String> withNoQuestion(List<Item> roots, Map<String, List<JsonNode>> answers) {
        List<String> questions = QuestionnaireJson.flatten(roots).stream().filter(Item::question).map(Item::linkId).toList();
        return answers.keySet().stream().filter(linkId -> !questions.contains(linkId)).toList();
    }

    /**
     * The answers to put into a response: those it already holds as they are, and the ones being
     * added in this step marked with where they came from. The DTR guide requires that of a client
     * whenever a person fills in or changes an answer: the person is named as the author, and the
     * source is override when the answer was pre-populated before, manual otherwise.
     *
     * <p>An answer posted again with the value it already had was neither filled in nor changed, so
     * it keeps the origin it had. An answer posted as a FHIR answer that already says where it came
     * from is used as written.
     */
    private static Map<String, List<JsonNode>> withOrigins(DtrAnswers.Posted posted, Filler filler, List<String> notes) {
        Map<String, List<JsonNode>> earlier = posted.response() == null ? Map.of() : QuestionnaireJson.answers(posted.response());
        Map<String, List<JsonNode>> out = new LinkedHashMap<>(posted.merged());
        boolean marked = false;
        for (Map.Entry<String, List<JsonNode>> given : posted.given().entrySet()) {
            List<JsonNode> before = earlier.getOrDefault(given.getKey(), List.of());
            if (given.getValue().isEmpty()) {
                continue;
            }
            if (sameValues(before, given.getValue())) {
                out.put(given.getKey(), before);
                continue;
            }
            boolean populated = before.stream().map(OnyxDtrConventions::originSource)
                    .anyMatch(source -> OnyxDtrConventions.ORIGIN_AUTO.equals(source) || OnyxDtrConventions.ORIGIN_OVERRIDE.equals(source));
            List<JsonNode> answers = new ArrayList<>();
            for (JsonNode answer : given.getValue()) {
                // An extension element that is not a list is the tester's own malformed answer, sent as written.
                boolean canBeMarked = OnyxDtrConventions.origin(answer) == null && answer instanceof ObjectNode
                        && (!answer.has("extension") || answer.get("extension").isArray());
                if (canBeMarked) {
                    answers.add(withOrigin((ObjectNode) answer, populated ? OnyxDtrConventions.ORIGIN_OVERRIDE : OnyxDtrConventions.ORIGIN_MANUAL,
                            filler.namesAProvider()));
                    marked = true;
                } else {
                    answers.add(answer);
                }
            }
            out.put(given.getKey(), answers);
        }
        if (marked) {
            notes.add("Each answer added in this step was marked with the information-origin extension the DTR guide requires of a client: "
                    + "given by a person, or changed by one where it had been pre-populated. " + (filler.namesAProvider()
                    ? "The provider on the form is named as its author, by the id this tool gives the Practitioner in every request."
                    : "No author is named, because the request names no provider. The guide asks for one."));
        }
        return out;
    }

    private static boolean sameValues(List<JsonNode> before, List<JsonNode> given) {
        if (before.size() != given.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            String key = QuestionnaireJson.valueKey(before.get(i), "value");
            if (key == null || !key.equals(QuestionnaireJson.valueKey(given.get(i), "value"))
                    || !before.get(i).get(key).equals(given.get(i).get(key))) {
                return false;
            }
        }
        return true;
    }

    private static ObjectNode withOrigin(ObjectNode answer, String source, boolean author) {
        ObjectNode out = JSON.objectNode();
        ArrayNode extensions = out.putArray("extension");
        JsonSafe.elements(answer, "extension").forEach(extension -> extensions.add(extension.deepCopy()));
        ObjectNode origin = extensions.addObject();
        ArrayNode parts = origin.putArray("extension");
        parts.addObject().put("url", "source").put("valueCode", source);
        if (author) {
            // The Practitioner resource has this id in every request this tool builds. The NPI itself is not put here.
            parts.addObject().put("url", "author").putArray("extension").addObject().put("url", "practitioner")
                    .putObject("valueReference").put("reference", CaseResources.PRACTITIONER_REF);
        }
        origin.put("url", OnyxDtrConventions.EXT_INFORMATION_ORIGIN);
        JsonSafe.properties(answer).forEach(property -> {
            if (!"extension".equals(property.getKey())) {
                out.set(property.getKey(), property.getValue().deepCopy());
            }
        });
        return out;
    }

    /**
     * Says on a response this tool starts from nothing which coverage and which orders it is for.
     * A response the package carried already says so, in the payer's own words, and is left alone.
     */
    private static void caseOf(ObjectNode response, Filler filler, List<String> notes) {
        ArrayNode extensions = JSON.arrayNode();
        if (filler.namesAMember()) {
            extensions.addObject().put("url", OnyxDtrConventions.EXT_QR_COVERAGE).putObject("valueReference")
                    .put("reference", CaseResources.COVERAGE_REF);
        }
        for (String order : filler.orders()) {
            extensions.addObject().put("url", OnyxDtrConventions.EXT_QR_CONTEXT).putObject("valueReference").put("reference", order);
        }
        if (!extensions.isEmpty()) {
            response.set("extension", extensions);
        }
        int orders = filler.orders().size();
        notes.add("No response was given to start from, so this tool built one. "
                + (filler.namesAMember() ? "It names the coverage of the case" : "It names no coverage, because the request names no member")
                + (orders == 0 ? ", and no order, because this step was handed no order and no procedure code."
                : ", and " + (orders == 1 ? "the order" : "the " + orders + " orders") + " it is for.")
                + (extensions.isEmpty() ? "" : " The references use the ids this tool gives those resources in every request.")
                + " It states no intended use, which DTR 2.2.0-ballot asks for and this tool cannot know.");
    }

    /** Puts a copy of the questionnaire inside the response and points the response at it. */
    private static void contain(ObjectNode response, JsonNode questionnaire, List<String> notes) {
        ObjectNode copy = (ObjectNode) questionnaire.deepCopy();
        String id = JsonSafe.presentText(copy, "id");
        if (id == null) {
            id = OnyxDtrConventions.CONTAINED_QUESTIONNAIRE_ID;
            copy.put("id", id);
            notes.add("The questionnaire arrived with no id, so the copy inside the QuestionnaireResponse was given the id " + id
                    + " to be referred to by.");
        }
        String canonical = QuestionnaireJson.canonical(questionnaire);
        if (canonical != null && !QuestionnaireJson.derivedFrom(copy).contains(canonical)) {
            // The guides tie an adaptive response to the questionnaire being completed by the derivedFrom of the questionnaire
            // inside it. It goes first, so that it is read before anything the questionnaire was itself derived from.
            ArrayNode derivedFrom = JSON.arrayNode().add(canonical);
            JsonSafe.elements(copy, "derivedFrom").forEach(derivedFrom::add);
            copy.set("derivedFrom", derivedFrom);
            notes.add("The copy of the questionnaire inside the QuestionnaireResponse was marked as derived from " + canonical
                    + ", which is how the DTR and SDC guides tie an adaptive response to the questionnaire being completed.");
        }
        ArrayNode contained = response.get("contained") instanceof ArrayNode existing ? existing : response.putArray("contained");
        contained.add(copy);
        response.put("questionnaire", "#" + id);
    }

    private static void subject(ObjectNode response, MemberDetail member, List<String> notes) {
        if (member != null && Texts.present(member.memberId())) {
            // The local id the Patient resource has in every request this tool builds. The member ID itself is not put here.
            response.putObject("subject").put("reference", CaseResources.PATIENT_REF);
        } else {
            notes.add("The response names no subject, because the request names no member.");
        }
    }

    private static List<String> carried(Map<String, List<String>> carry, String key) {
        return carry == null ? List.of() : clean(carry.get(key));
    }

    private static List<String> clean(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (Texts.present(value) && !out.contains(value.strip())) {
                    out.add(value.strip());
                }
            }
        }
        return out;
    }
}
