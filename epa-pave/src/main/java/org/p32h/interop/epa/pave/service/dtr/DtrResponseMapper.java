package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions.Progress;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.FhirViews;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.dtr.AnswerOptionView;
import org.p32h.interop.epa.pave.model.dtr.AnswerOriginView;
import org.p32h.interop.epa.pave.model.dtr.AnswerView;
import org.p32h.interop.epa.pave.model.dtr.ChangesView;
import org.p32h.interop.epa.pave.model.dtr.ConditionState;
import org.p32h.interop.epa.pave.model.dtr.DtrCode;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.EnableWhenView;
import org.p32h.interop.epa.pave.model.dtr.ExtensionView;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.ItemEnabled;
import org.p32h.interop.epa.pave.model.dtr.ItemView;
import org.p32h.interop.epa.pave.model.dtr.PackageBundleView;
import org.p32h.interop.epa.pave.model.dtr.PackageContentView;
import org.p32h.interop.epa.pave.model.dtr.PackageResourceView;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson.Item;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireResponseView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import org.p32h.interop.epa.pave.model.dtr.ResponseItemView;
import org.p32h.interop.epa.pave.service.dtr.EnableWhenEvaluator.Form;
import org.p32h.interop.epa.pave.service.dtr.EnableWhenEvaluator.ItemState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads what a DTR operation returned into the designed view and an outcome.
 *
 * <p>The response is read through {@link TrackedJson}. Every field placed in the view is consumed
 * by the read that placed it, and whatever is left is returned as additional fields, so a field
 * this mapper was never told about appears on screen without anyone having to add it here.
 *
 * <p>The same reading is applied to a success, a 400, a 500 and an OperationOutcome. A fault
 * response is not reduced to its status.
 *
 * <p>A questionnaire package is accepted in either of the shapes it may arrive in, a Parameters
 * resource or a Bundle, and in any nesting of the two. Every questionnaire found is listed.
 * Nothing is reduced to the first.
 */
public final class DtrResponseMapper {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** Everything a package holds, found by walking it, before any of it is turned into a view. */
    private final class Walk {
        private final List<TrackedJson.Node> questionnaires = new ArrayList<>();
        private final List<TrackedJson.Node> responses = new ArrayList<>();
        private final List<PackageBundleView> bundles = new ArrayList<>();
        private final List<PackageResourceView> resources = new ArrayList<>();
        private final List<IssueView> issues = new ArrayList<>();
        private final List<String> parameters = new ArrayList<>();
        private String note;

        private DtrResponseKind root(TrackedJson.Node root) {
            JsonNode raw = root.raw();
            if (OperationOutcomes.is(raw)) {
                root.text("resourceType");
                issues.addAll(OperationOutcomes.issues(root));
                return DtrResponseKind.OPERATION_OUTCOME;
            }
            if (QuestionnaireJson.isType(raw, "Parameters")) {
                root.text("resourceType");
                parameters(root.child("parameter"));
                note = parameters.isEmpty() ? "The package arrived as a Parameters resource that holds no parameters."
                        : "The package arrived as a Parameters resource holding: " + String.join(", ", parameters) + ".";
                return DtrResponseKind.PARAMETERS;
            }
            if (QuestionnaireJson.isType(raw, "Bundle")) {
                bundle(root);
                note = "The package arrived as a Bundle.";
                return DtrResponseKind.BUNDLE;
            }
            if (QuestionnaireJson.isType(raw, "Questionnaire")) {
                questionnaires.add(root);
                note = "The response is a Questionnaire resource by itself, not wrapped in a package.";
                return DtrResponseKind.QUESTIONNAIRE;
            }
            if (QuestionnaireJson.isType(raw, "QuestionnaireResponse")) {
                responses.add(root);
                note = "The response is a QuestionnaireResponse, which is not a questionnaire package.";
                return DtrResponseKind.QUESTIONNAIRE_RESPONSE;
            }
            note = otherJson(raw, "one of the shapes a questionnaire package arrives in");
            return DtrResponseKind.OTHER_JSON;
        }

        private void parameters(TrackedJson.Node array) {
            statedByTheView(array);
            for (TrackedJson.Node parameter : array.elements()) {
                if (!parameter.isObject()) {
                    continue;
                }
                String name = parameter.text("name");
                TrackedJson.Node resource = parameter.child("resource");
                if (resource.isObject()) {
                    parameters.add(Texts.orElse(name, "(no name)") + " ("
                            + Texts.orElse(JsonSafe.presentText(resource.raw(), "resourceType"), "no resourceType") + ")");
                    resource(resource);
                } else if (parameter.child("part").isArray()) {
                    parameters.add(Texts.orElse(name, "(no name)") + " (parts)");
                } else {
                    parameters.add(Texts.orElse(name, "(no name)"));
                }
                parameters(parameter.child("part"));
            }
        }

        private void bundle(TrackedJson.Node bundle) {
            bundle.text("resourceType");
            TrackedJson.Node entries = bundle.child("entry");
            statedByTheView(entries);
            List<Shown> fullUrls = new ArrayList<>();
            for (TrackedJson.Node entry : entries.elements()) {
                if (entry.isObject() && entry.child("fullUrl").exists()) {
                    fullUrls.add(entry.shown("fullUrl"));
                }
            }
            bundles.add(new PackageBundleView(bundle.path(), bundle.shown("type"), bundle.shown("id"), entries.elements().size(), fullUrls));
            for (TrackedJson.Node entry : entries.elements()) {
                TrackedJson.Node resource = entry.child("resource");
                if (resource.isObject()) {
                    resource(resource);
                }
            }
        }

        private void resource(TrackedJson.Node node) {
            String type = JsonSafe.presentText(node.raw(), "resourceType");
            if ("Bundle".equals(type)) {
                bundle(node);
            } else if ("Parameters".equals(type)) {
                node.text("resourceType");
                parameters(node.child("parameter"));
            } else if ("Questionnaire".equals(type)) {
                questionnaires.add(node);
            } else if ("QuestionnaireResponse".equals(type)) {
                responses.add(node);
            } else if ("OperationOutcome".equals(type)) {
                node.text("resourceType");
                issues.addAll(OperationOutcomes.issues(node));
            } else {
                resources.add(resourceView(node));
            }
        }
    }

    /**
     * Reads the response to a package fetch.
     *
     * @param requestedCanonicals the questionnaires the request asked for by canonical url. Empty when it named none
     */
    public DtrReading readPackage(VendorExchange received, List<String> requestedCanonicals) {
        // What the view copies out of the body must not show a secret the request carried, any more than the wire view does.
        VendorExchange exchange = received.withReturnedSecretsMasked();
        List<String> requested = new ArrayList<>();
        if (requestedCanonicals != null) {
            requestedCanonicals.stream().filter(Texts::present).map(String::strip).filter(c -> !requested.contains(c)).forEach(requested::add);
        }
        // The body as received when it could not be parsed, and anything worth knowing about the
        // body as a whole, come first so they are not lost below a long list.
        List<AdditionalField> additional = new ArrayList<>(TrackedJson.bodyFieldsWithRepeatedKeys(exchange));
        Walk walk = new Walk();
        List<QuestionnaireView> questionnaires = new ArrayList<>();
        List<QuestionnaireResponseView> responses = new ArrayList<>();
        DtrResponseKind kind;
        String note;
        JsonNode json = exchange.json();
        if (json == null) {
            kind = withoutJson(exchange);
            note = withoutJsonNote(exchange);
        } else {
            TrackedJson tracked = TrackedJson.of(json);
            kind = walk.root(tracked.root());
            note = walk.note;
            for (TrackedJson.Node response : walk.responses) {
                responses.add(response(response, response.path()));
            }
            int position = 1;
            for (TrackedJson.Node questionnaire : walk.questionnaires) {
                questionnaires.add(packaged(questionnaire, position++, walk));
            }
            additional.addAll(tracked.unconsumed());
        }
        // Only a response that was read can be said not to hold something. A fault has its own outcome.
        List<String> notReturned = !exchange.ok() ? List.of()
                : requested.stream().filter(wanted -> questionnaires.stream().noneMatch(q -> DtrReading.namedBy(wanted, q))).toList();
        DtrOutcome outcome = DtrOutcomeRules.forPackage(exchange, kind, questionnaires, notReturned);
        DtrDetail detail = new DtrDetail(kind, note, questionnaires.isEmpty() ? null : OnyxDtrConventions.KIND_RULE, requested, notReturned,
                walk.bundles, questionnaires, walk.resources, responses, null, walk.issues, List.of(), true, true);
        return new DtrReading(detail, outcome, additional);
    }

    /** A questionnaire of a package, evaluated against the answers the package arrived with for it. */
    private QuestionnaireView packaged(TrackedJson.Node node, int position, Walk walk) {
        JsonNode raw = node.raw();
        JsonNode answered = null;
        String arrivedWith = null;
        for (TrackedJson.Node response : walk.responses) {
            if (carriedFor(response.raw(), raw, walk)) {
                answered = response.raw();
                arrivedWith = response.path();
                break;
            }
        }
        Map<String, List<JsonNode>> answers;
        String basis;
        if (answered != null) {
            answers = QuestionnaireJson.answers(answered);
            basis = "The conditions were evaluated against the answers in the QuestionnaireResponse the package arrived with.";
        } else {
            answers = initialAnswers(raw);
            basis = answers.isEmpty() ? "The conditions were evaluated with no answers, because the package arrived with none for this questionnaire."
                    : "The conditions were evaluated against the initial values the questionnaire states, because the package arrived "
                    + "with no answers for it.";
        }
        return questionnaire(node, position, node.path(), EnableWhenEvaluator.evaluate(raw, answers),
                answered == null ? Map.of() : QuestionnaireJson.answers(answered), basis, arrivedWith);
    }

    /**
     * True when a response of a package is the one it carries for this questionnaire. A response
     * that names a questionnaire, by canonical url or through the questionnaire inside it, is tied
     * to the one it names and to no other. One that names none at all is taken to answer the only
     * questionnaire in the package.
     */
    private static boolean carriedFor(JsonNode response, JsonNode questionnaire, Walk walk) {
        List<String> named = OnyxDtrConventions.completes(response);
        if (!named.isEmpty()) {
            return named.stream().anyMatch(canonical -> QuestionnaireJson.names(canonical, questionnaire));
        }
        // A local reference that leads to nothing which names a questionnaire is still a statement about which one, so it is not tied.
        return JsonSafe.presentText(response, "questionnaire") == null && walk.questionnaires.size() == 1 && walk.responses.size() == 1;
    }

    /** The answers a questionnaire starts with by its own account: initial values, and options it marks as selected. */
    static Map<String, List<JsonNode>> initialAnswers(JsonNode questionnaire) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        for (Item item : QuestionnaireJson.questions(questionnaire)) {
            if (item.linkId() == null) {
                continue;
            }
            List<JsonNode> values = new ArrayList<>();
            for (JsonNode initial : JsonSafe.elements(item.node(), "initial")) {
                if (initial.isObject() && QuestionnaireJson.answered(initial)) {
                    values.add(initial);
                }
            }
            for (JsonNode option : JsonSafe.elements(item.node(), "answerOption")) {
                String key = QuestionnaireJson.valueKey(option, "value");
                if (key != null && Boolean.TRUE.equals(JsonSafe.bool(option, "initialSelected")) && QuestionnaireJson.answered(option)) {
                    ObjectNode answer = JSON.objectNode();
                    answer.set(key, option.get(key));
                    values.add(answer);
                }
            }
            if (!values.isEmpty()) {
                out.put(item.linkId(), values);
            }
        }
        return out;
    }

    /**
     * Reads the response to one step of the adaptive loop.
     *
     * @param sent the QuestionnaireResponse that was sent, for saying what changed. Null when what
     *             was sent could not be read as one
     */
    public DtrReading readNextQuestion(VendorExchange received, JsonNode sent) {
        // What the view copies out of the body must not show a secret the request carried, any more than the wire view does.
        VendorExchange exchange = received.withReturnedSecretsMasked();
        List<AdditionalField> additional = new ArrayList<>(TrackedJson.bodyFieldsWithRepeatedKeys(exchange));
        List<IssueView> issues = new ArrayList<>();
        List<PackageResourceView> resources = new ArrayList<>();
        QuestionnaireView questionnaire = null;
        QuestionnaireResponseView response = null;
        FormStateView form = null;
        Progress progress = Progress.NOT_UNDERSTOOD;
        DtrResponseKind kind;
        String note = null;
        JsonNode json = exchange.json();
        if (json == null) {
            kind = withoutJson(exchange);
            note = withoutJsonNote(exchange);
        } else {
            TrackedJson tracked = TrackedJson.of(json);
            TrackedJson.Node root = tracked.root();
            TrackedJson.Node responseNode = null;
            if (OperationOutcomes.is(json)) {
                kind = DtrResponseKind.OPERATION_OUTCOME;
                root.text("resourceType");
                issues.addAll(OperationOutcomes.issues(root));
            } else if (QuestionnaireJson.isType(json, "QuestionnaireResponse")) {
                kind = DtrResponseKind.QUESTIONNAIRE_RESPONSE;
                responseNode = root;
            } else if (QuestionnaireJson.isType(json, "Parameters")) {
                kind = DtrResponseKind.PARAMETERS;
                root.text("resourceType");
                for (TrackedJson.Node parameter : root.children("parameter")) {
                    TrackedJson.Node resource = parameter.child("resource");
                    String type = JsonSafe.presentText(resource.raw(), "resourceType");
                    if ("QuestionnaireResponse".equals(type) && responseNode == null) {
                        parameter.text("name");
                        responseNode = resource;
                    } else if ("OperationOutcome".equals(type)) {
                        parameter.text("name");
                        resource.text("resourceType");
                        issues.addAll(OperationOutcomes.issues(resource));
                    }
                }
                note = responseNode == null ? "The body is a Parameters resource that holds no QuestionnaireResponse."
                        : "The QuestionnaireResponse arrived inside a Parameters resource.";
            } else {
                kind = DtrResponseKind.OTHER_JSON;
                note = otherJson(json, "a QuestionnaireResponse");
            }
            if (responseNode != null) {
                JsonNode rawResponse = responseNode.raw();
                JsonNode rawQuestionnaire = QuestionnaireJson.containedQuestionnaire(rawResponse);
                TrackedJson.Node questionnaireNode = null;
                for (TrackedJson.Node contained : responseNode.children("contained")) {
                    if (rawQuestionnaire != null && contained.raw() == rawQuestionnaire) {
                        questionnaireNode = contained;
                    } else if (contained.isObject()) {
                        resources.add(resourceView(contained));
                    }
                }
                response = response(responseNode, responseNode.path());
                progress = OnyxDtrConventions.progress(response.status().code());
                if (questionnaireNode != null) {
                    Form evaluated = EnableWhenEvaluator.evaluate(rawQuestionnaire, QuestionnaireJson.answers(rawResponse));
                    questionnaire = questionnaire(questionnaireNode, 1, questionnaireNode.path(), evaluated, Map.of(),
                            "The conditions were evaluated against the answers in the QuestionnaireResponse the service returned.", null);
                    form = formState(evaluated, rawResponse, response.status(), progress == Progress.COMPLETED, List.of(),
                            changes(sent, rawQuestionnaire, rawResponse));
                } else {
                    note = (note == null ? "" : note + " ") + "The QuestionnaireResponse carries no questionnaire inside itself that it "
                            + "can be told to answer, so this tool cannot list the questions it asks.";
                }
            }
            additional.addAll(tracked.unconsumed());
        }
        DtrOutcome outcome = DtrOutcomeRules.forNextQuestion(exchange, kind, response != null, questionnaire, progress, form);
        DtrDetail detail = new DtrDetail(kind, note, questionnaire == null ? null : OnyxDtrConventions.KIND_RULE, List.of(), List.of(),
                List.of(), questionnaire == null ? List.of() : List.of(questionnaire), resources,
                response == null ? List.of() : List.of(response), form, issues, List.of(), true, true);
        return new DtrReading(detail, outcome, additional);
    }

    /**
     * Describes a response put together inside the tool. Nothing was received, so there are no
     * additional fields: the questionnaire was shown in full when its package was read.
     *
     * @param questionnaire the questionnaire being answered
     * @param response      the QuestionnaireResponse as it now stands
     * @param evaluated     the questionnaire evaluated against the answers given
     * @param answeredAs    how the questionnaire is answered, which decides who can call the response complete
     * @param progress      for an adaptive questionnaire, where the status the service last returned puts the response
     * @param builtHere     true when this tool put the response together, false when it is the one the service last returned
     * @param notes         what a tester should know about how the response was put together
     */
    public DtrReading describeResponse(JsonNode questionnaire, JsonNode response, Form evaluated, QuestionnaireKind answeredAs,
                                       Progress progress, boolean builtHere, List<String> notes) {
        QuestionnaireView questionnaireView = questionnaire(TrackedJson.of(questionnaire).root(), 1, "As posted with the request", evaluated,
                Map.of(), "The conditions were evaluated against the answers given.", null);
        QuestionnaireResponseView responseView = response(TrackedJson.of(response).root(),
                builtHere ? "Put together by this tool" : "As the service last returned it");
        FormStateView form = formState(evaluated, response, responseView.status(),
                OnyxDtrConventions.progress(responseView.status().code()) == Progress.COMPLETED, notes, null);
        DtrOutcome outcome = DtrOutcomeRules.forResponse(answeredAs, progress, form);
        DtrDetail detail = new DtrDetail(DtrResponseKind.NOT_SENT, "Nothing was sent to the service for this step.",
                OnyxDtrConventions.KIND_RULE, List.of(), List.of(), List.of(), List.of(questionnaireView), List.of(), List.of(responseView),
                form, List.of(), List.of(), true, false);
        return new DtrReading(detail, outcome, List.of());
    }

    /** Where a questionnaire stands with the answers a response holds. */
    static FormStateView formState(Form evaluated, JsonNode response, DtrCode status, boolean statusCompleted, List<String> givenNotes,
                                   ChangesView changes) {
        Map<String, Item> byLinkId = new LinkedHashMap<>();
        for (ItemState state : evaluated.all()) {
            if (state.item().linkId() != null) {
                byLinkId.putIfAbsent(state.item().linkId(), state.item());
            }
        }
        List<String> notes = new ArrayList<>(givenNotes);
        List<AnswerView> answers = new ArrayList<>();
        Map<String, List<JsonNode>> held = QuestionnaireJson.answers(response);
        for (Map.Entry<String, List<JsonNode>> entry : held.entrySet()) {
            Item item = byLinkId.get(entry.getKey());
            answers.add(new AnswerView(entry.getKey(), item == null ? null : item.text(),
                    entry.getValue().stream().map(QuestionnaireJson::answerDisplay).toList()));
            if (item == null) {
                notes.add("The response holds an answer for " + entry.getKey() + ", which is not an item of the questionnaire it answers.");
            }
        }
        for (QuestionRef left : evaluated.answersLeftOut()) {
            if (held.containsKey(left.linkId())) {
                notes.add("The response holds an answer for " + left.linkId() + ", which the answers given disable. It is not counted.");
            }
        }
        List<QuestionRef> unanswered = evaluated.requiredUnanswered();
        return new FormStateView(evaluated.questions().size(), evaluated.shownQuestions().size(), evaluated.answered().size(), status,
                statusCompleted && unanswered.isEmpty(), evaluated.asked(), answers, unanswered, evaluated.undetermined(), changes, notes);
    }

    /**
     * What changed between the QuestionnaireResponse that was sent and the one that came back:
     * the questions that appeared, the ones that disappeared, and any answer that was sent and is
     * no longer there.
     */
    static ChangesView changes(JsonNode sent, JsonNode returnedQuestionnaire, JsonNode returnedResponse) {
        if (sent == null) {
            return ChangesView.notCompared("What was sent could not be read as a QuestionnaireResponse, so this tool cannot say what "
                    + "changed since the previous step.");
        }
        Map<String, QuestionRef> before = questions(QuestionnaireJson.containedQuestionnaire(sent));
        Map<String, QuestionRef> after = questions(returnedQuestionnaire);
        List<QuestionRef> appeared = after.entrySet().stream().filter(e -> !before.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        List<QuestionRef> disappeared = before.entrySet().stream().filter(e -> !after.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        Map<String, List<JsonNode>> kept = QuestionnaireJson.answers(returnedResponse);
        List<QuestionRef> dropped = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> answered : QuestionnaireJson.answers(sent).entrySet()) {
            String linkId = answered.getKey();
            if (!kept.containsKey(linkId)) {
                // The answer that went missing, as the screen shows an answer, so a tester sees what was lost and not only where.
                dropped.add((before.containsKey(linkId) ? before.get(linkId) : new QuestionRef(linkId, null))
                        .withValues(answered.getValue().stream().map(QuestionnaireJson::answerDisplay).filter(java.util.Objects::nonNull).toList()));
            }
        }
        List<String> parts = new ArrayList<>();
        if (!appeared.isEmpty()) {
            parts.add(count(appeared.size(), "question") + " appeared: " + named(appeared) + ".");
        }
        if (!disappeared.isEmpty()) {
            parts.add(count(disappeared.size(), "question") + " disappeared: " + named(disappeared) + ".");
        }
        if (!dropped.isEmpty()) {
            parts.add((dropped.size() == 1 ? "The answer that was sent to " : "The answers that were sent to ") + named(dropped)
                    + (dropped.size() == 1 ? " is" : " are") + " not in the response.");
        }
        String summary = parts.isEmpty() ? "Nothing changed since the previous step: the same questions are asked and every answer "
                + "that was sent came back." : "Since the previous step: " + String.join(" ", parts);
        return new ChangesView(true, summary, appeared, disappeared, dropped);
    }

    private static Map<String, QuestionRef> questions(JsonNode questionnaire) {
        Map<String, QuestionRef> out = new LinkedHashMap<>();
        if (questionnaire != null) {
            for (Item item : QuestionnaireJson.questions(questionnaire)) {
                if (item.linkId() != null) {
                    out.putIfAbsent(item.linkId(), item.ref());
                }
            }
        }
        return out;
    }

    private static String named(List<QuestionRef> questions) {
        return String.join(", ", questions.stream().map(QuestionRef::named).toList());
    }

    private static String count(int number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }

    // ---- the views ----

    private QuestionnaireView questionnaire(TrackedJson.Node node, int position, String foundAt, Form form,
                                            Map<String, List<JsonNode>> prepopulated, String answersBasis, String arrivedWith) {
        JsonNode raw = node.raw();
        node.text("resourceType");
        Shown id = node.shown("id");
        Shown url = node.shown("url");
        Shown version = node.shown("version");
        Shown name = node.shown("name");
        Shown title = node.shown("title");
        DtrCode status = DtrCodes.coded(DtrCodes.QUESTIONNAIRE_STATUS, node.shown("status"));
        Shown date = node.shown("date");
        Shown publisher = node.shown("publisher");
        Shown description = node.shown("description");
        // A part of the narrative this mapper was never told about stays among the fields that were returned and not interpreted.
        Shown narrative = node.child("text").shown("div");
        Shown narrativeStatus = node.child("text").shown("status");
        List<Shown> subjectType = each(node.children("subjectType"));
        List<Shown> profiles = each(node.child("meta").children("profile"));
        List<Shown> derivedFrom = each(node.children("derivedFrom"));
        List<ExtensionView> extensions = extensions(node);
        List<PackageResourceView> contained = new ArrayList<>();
        for (TrackedJson.Node resource : node.children("contained")) {
            if (resource.isObject()) {
                contained.add(resourceView(resource));
            }
        }
        List<ItemView> items = items(node, "item", form, prepopulated);
        OnyxDtrConventions.Kind kind = OnyxDtrConventions.kind(raw);
        List<ItemState> questions = form.questions();
        String subject = Texts.firstPresent(title.orNull(), url.orNull(), "Questionnaire " + position);
        return new QuestionnaireView(position, foundAt, subject, id, url, version, QuestionnaireJson.canonical(raw), name, title, status,
                date, publisher, description, narrative, narrativeStatus, subjectType, profiles, derivedFrom, kind.kind(), kind.basis(), questions.size(),
                (int) questions.stream().filter(state -> state.item().required()).count(), items, extensions, contained,
                QuestionnaireJson.problems(raw), DtrNotApplied.describe(raw), answersBasis, arrivedWith, JsonSafe.compact(raw));
    }

    private List<ItemView> items(TrackedJson.Node parent, String prefix, Form form, Map<String, List<JsonNode>> prepopulated) {
        List<ItemView> out = new ArrayList<>();
        int index = 0;
        for (TrackedJson.Node item : parent.children("item")) {
            if (item.isObject()) {
                out.add(item(item, prefix + "[" + index + "]", form, prepopulated));
            }
            index++;
        }
        return out;
    }

    private ItemView item(TrackedJson.Node item, String path, Form form, Map<String, List<JsonNode>> prepopulated) {
        ItemState state = form.state(path);
        Shown linkId = item.shown("linkId");
        Shown text = item.shown("text");
        Shown prefix = item.shown("prefix");
        DtrCode type = DtrCodes.coded(DtrCodes.ITEM_TYPE, item.shown("type"));
        Shown required = item.shown("required");
        Shown repeats = item.shown("repeats");
        Shown readOnly = item.shown("readOnly");
        Shown answerValueSet = item.shown("answerValueSet");

        List<AnswerOptionView> options = new ArrayList<>();
        for (TrackedJson.Node option : item.children("answerOption")) {
            if (!option.isObject()) {
                continue;
            }
            String key = QuestionnaireJson.valueKey(option.raw(), "value");
            JsonNode value = key == null ? null : option.raw().get(key);
            String label = key == null ? null : key.endsWith("Coding") && value.isObject()
                    ? Texts.firstPresent(JsonSafe.presentText(value, "display"), JsonSafe.presentText(value, "code"))
                    : QuestionnaireJson.valueText(key, value);
            options.add(new AnswerOptionView(label, key == null ? null : QuestionnaireJson.valueText(key, value), typed(option, "value"),
                    option.shown("initialSelected")));
        }
        List<Shown> initial = new ArrayList<>();
        for (TrackedJson.Node entry : item.children("initial")) {
            if (entry.isObject()) {
                initial.add(typed(entry, "value"));
            }
        }
        List<Shown> arrived = new ArrayList<>();
        if (linkId.hasValue()) {
            for (JsonNode answer : prepopulated.getOrDefault(linkId.value(), List.of())) {
                arrived.add(Shown.of(QuestionnaireJson.answerDisplay(answer)));
            }
        }
        DtrCode behavior = DtrCodes.coded(DtrCodes.ENABLE_BEHAVIOR, item.shown("enableBehavior"));
        List<EnableWhenView> conditions = new ArrayList<>();
        int index = 0;
        for (TrackedJson.Node entry : item.children("enableWhen")) {
            // The evaluation walked the same list in the same order, so the two line up by position.
            EnableWhenEvaluator.Condition evaluated = state != null && index < state.conditions().size() ? state.conditions().get(index) : null;
            conditions.add(new EnableWhenView(entry.shown("question"), DtrCodes.coded(DtrCodes.OPERATOR, entry.shown("operator")),
                    typed(entry, "answer"), evaluated == null ? ConditionState.NOT_EVALUATED : evaluated.state(),
                    evaluated == null ? "Not evaluated." : evaluated.explanation()));
            index++;
        }
        List<ExtensionView> extensions = extensions(item);
        List<ItemView> children = items(item, path + ".item", form, prepopulated);
        return new ItemView(path, linkId, text, prefix, type, state != null && state.item().question(), required, repeats, readOnly,
                answerValueSet, options, initial, arrived, behavior, conditions, state == null ? ItemEnabled.UNDETERMINED : state.enabled(),
                state == null ? null : state.reason(), extensions, children,
                item.presences("answerOption", "initial", "enableWhen", "item", "extension"));
    }

    private QuestionnaireResponseView response(TrackedJson.Node node, String foundAt) {
        JsonNode raw = node.raw();
        node.text("resourceType");
        Shown id = node.shown("id");
        Shown questionnaire = node.shown("questionnaire");
        DtrCode status = DtrCodes.coded(DtrCodes.RESPONSE_STATUS, node.shown("status"));
        Shown authored = node.shown("authored");
        Shown subject = node.child("subject").exists() ? FhirViews.reference(node.child("subject")) : Shown.absent();
        Shown author = node.child("author").exists() ? FhirViews.reference(node.child("author")) : Shown.absent();
        List<Shown> profiles = each(node.child("meta").children("profile"));
        List<ResponseItemView> items = responseItems(node.child("item"), "item");
        return new QuestionnaireResponseView(foundAt, id, questionnaire, status, authored, subject, author, profiles, items, extensions(node),
                QuestionnaireJson.answers(raw).size(), JsonSafe.compact(raw));
    }

    private List<ResponseItemView> responseItems(TrackedJson.Node array, String prefix) {
        List<ResponseItemView> out = new ArrayList<>();
        int index = 0;
        for (TrackedJson.Node item : array.elements()) {
            String path = prefix + "[" + index + "]";
            if (item.isObject()) {
                List<Shown> answers = new ArrayList<>();
                List<AnswerOriginView> origins = new ArrayList<>();
                List<ExtensionView> answerExtensions = new ArrayList<>();
                List<ResponseItemView> nested = new ArrayList<>(responseItems(item.child("item"), path + ".item"));
                int answerIndex = 0;
                for (TrackedJson.Node answer : item.children("answer")) {
                    if (answer.isObject()) {
                        answers.add(typed(answer, "value"));
                        origins.add(origin(answer, answerExtensions));
                        nested.addAll(responseItems(answer.child("item"), path + ".answer[" + answerIndex + "].item"));
                    }
                    answerIndex++;
                }
                out.add(new ResponseItemView(path, item.shown("linkId"), item.shown("text"), answers, origins, answerExtensions, nested));
            }
            index++;
        }
        return out;
    }

    /**
     * Where an answer came from, read from its information-origin extension: the source, the
     * practitioner named as the author, and each role given. Every other extension the answer
     * carries is shown by its url and value. A part of the origin this mapper was never told about
     * is not read here, so it stays among the fields that were returned and not interpreted.
     *
     * @param others receives the other extensions of the answer
     */
    private static AnswerOriginView origin(TrackedJson.Node answer, List<ExtensionView> others) {
        AnswerOriginView origin = AnswerOriginView.notStated();
        for (TrackedJson.Node extension : answer.children("extension")) {
            if (!extension.isObject()) {
                continue;
            }
            if (!OnyxDtrConventions.EXT_INFORMATION_ORIGIN.equals(JsonSafe.presentText(extension.raw(), "url"))) {
                Shown url = extension.shown("url");
                Shown value = FhirViews.extensionValue(extension) != null ? FhirViews.extensionValueShown(extension)
                        : extension.child("extension").exists() ? extension.shown("extension") : Shown.absent();
                others.add(new ExtensionView(url, value));
                continue;
            }
            extension.text("url");
            Shown source = Shown.absent();
            Shown author = Shown.absent();
            List<Shown> roles = new ArrayList<>();
            for (TrackedJson.Node part : extension.children("extension")) {
                String name = JsonSafe.presentText(part.raw(), "url");
                if ("source".equals(name)) {
                    part.text("url");
                    source = part.shown("valueCode");
                } else if ("author".equals(name)) {
                    part.text("url");
                    for (TrackedJson.Node detail : part.children("extension")) {
                        String detailName = JsonSafe.presentText(detail.raw(), "url");
                        if ("practitioner".equals(detailName)) {
                            detail.text("url");
                            author = FhirViews.reference(detail.child("valueReference"));
                        } else if ("role".equals(detailName)) {
                            detail.text("url");
                            roles.add(FhirViews.concept(detail.child("valueCodeableConcept")));
                        }
                    }
                }
            }
            origin = new AnswerOriginView(source, author, roles);
        }
        return origin;
    }

    /** A resource that is neither a questionnaire nor a response: its identity, and for a value set or a library what it holds. */
    private PackageResourceView resourceView(TrackedJson.Node node) {
        String type = JsonSafe.presentText(node.raw(), "resourceType");
        String foundAt = node.path();
        Shown resourceType = node.shown("resourceType");
        Shown id = node.shown("id");
        Shown url = node.shown("url");
        Shown version = node.shown("version");
        Shown name = node.shown("name");
        Shown title = node.shown("title");
        Shown status = node.shown("status");
        List<Shown> concepts = new ArrayList<>();
        List<PackageContentView> content = new ArrayList<>();
        if ("ValueSet".equals(type)) {
            for (TrackedJson.Node include : node.child("compose").children("include")) {
                String system = include.presentText("system");
                for (TrackedJson.Node concept : include.children("concept")) {
                    String code = concept.presentText("code");
                    String display = concept.presentText("display");
                    String coded = system == null ? code : system + "|" + (code == null ? "" : code);
                    String text = display != null && coded != null ? display + " (" + coded + ")" : Texts.firstPresent(display, coded);
                    concepts.add(text == null ? Shown.empty() : Shown.of(text));
                }
            }
            for (TrackedJson.Node contains : node.child("expansion").children("contains")) {
                concepts.add(FhirViews.coding(contains));
            }
        }
        if ("Library".equals(type)) {
            for (TrackedJson.Node part : node.children("content")) {
                if (!part.isObject()) {
                    continue;
                }
                TrackedJson.Node data = part.child("data");
                String encoded = JsonSafe.text(data.raw());
                // The bytes are in the wire view. Here they are described by their size and not repeated.
                data.consume();
                content.add(new PackageContentView(part.shown("contentType"), part.shown("url"), data.presence(),
                        Texts.present(encoded) ? encoded.length() : null));
            }
        }
        return new PackageResourceView(foundAt, resourceType, id, url, version, name, title, status, concepts, content);
    }

    /** Each extension on an element, by its url and its value. An extension made of nested extensions shows those as JSON. */
    private static List<ExtensionView> extensions(TrackedJson.Node holder) {
        List<ExtensionView> out = new ArrayList<>();
        for (TrackedJson.Node extension : holder.children("extension")) {
            if (!extension.isObject()) {
                continue;
            }
            Shown url = extension.shown("url");
            Shown value = FhirViews.extensionValue(extension) != null ? FhirViews.extensionValueShown(extension)
                    : extension.child("extension").exists() ? extension.shown("extension") : Shown.absent();
            out.add(new ExtensionView(url, value));
        }
        return out;
    }

    /**
     * The value an element carries under a typed property such as {@code valueCoding} or
     * {@code answerBoolean}, for the screen. Absent when it carries none.
     */
    private static Shown typed(TrackedJson.Node holder, String prefix) {
        for (Map.Entry<String, TrackedJson.Node> property : holder.properties()) {
            String key = property.getKey();
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                if (key.endsWith("Coding")) {
                    return FhirViews.coding(property.getValue());
                }
                if (key.endsWith("Reference")) {
                    return FhirViews.reference(property.getValue());
                }
                return property.getValue().shown();
            }
        }
        return Shown.absent();
    }

    private static List<Shown> each(List<TrackedJson.Node> nodes) {
        List<Shown> out = new ArrayList<>();
        nodes.forEach(node -> out.add(node.shown()));
        return out;
    }

    /**
     * A list the view states the emptiness of itself, so a null or an empty one is not listed a
     * second time among the fields nobody interpreted. Anything else in that place, such as a
     * string where the list should be, is left to be listed.
     */
    private static void statedByTheView(TrackedJson.Node node) {
        if (node.raw().isNull() || (node.isArray() && node.raw().isEmpty())) {
            node.consume();
        }
    }

    private static DtrResponseKind withoutJson(VendorExchange exchange) {
        if (exchange.httpStatus() == null) {
            return DtrResponseKind.NO_RESPONSE;
        }
        return exchange.responseBody() == null ? DtrResponseKind.NO_BODY : DtrResponseKind.NOT_JSON;
    }

    private static String withoutJsonNote(VendorExchange exchange) {
        if (exchange.httpStatus() == null) {
            return "No response arrived.";
        }
        if (exchange.responseBody() == null) {
            return "The response has no body.";
        }
        return ("The body could not be read as JSON. It is shown under the fields that were returned but not interpreted, and in "
                + "the wire view, as received except that a place repeating a secret the request carried is masked. "
                + Texts.orElse(exchange.parseError(), "")).strip();
    }

    private static String otherJson(JsonNode json, String expected) {
        if (!json.isObject()) {
            return "The body is JSON but is not an object, so none of it could be interpreted. All of it is listed below.";
        }
        String type = JsonSafe.presentText(json, "resourceType");
        return type == null ? "The body is a JSON object with no resourceType, so it is not " + expected + ". All of it is listed below."
                : "The body is a " + type + " resource, which is not " + expected + ". All of it is listed below.";
    }
}
