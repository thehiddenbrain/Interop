package org.p32h.interop.epa.pave.service.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.pas.PasOptions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Answers to a questionnaire's questions taken from the case on screen (plan R-18).
 *
 * <p>There is no SMART launch and no patient record behind DTR here, so the only data a question
 * about the member, the provider, the codes or the service can be answered from is what the tester
 * typed or looked up on the case. A question is matched by its text: the text is lower-cased, cut
 * into words, and searched for whole words and phrases with {@code contains}. No regular
 * expression is used, so no question text, however long, can make the matching recurse.
 *
 * <p>What is filled is the tester's own data, never a guess: a field the case does not hold leaves
 * its question empty, a choice is filled only when one of its options carries the case's code or
 * words, and every answer names the case field it came from and carries a mark the page shows. The
 * one default is a date question that nothing else answers, which gets the date of service, marked
 * as a default to change, and only when the case has a date of service. The payer's own answers
 * win: a question the payer prefilled, gave an initial value, hid or made read only gets nothing.
 *
 * <p>The items are walked with an explicit stack, so a questionnaire nested as deep as the parser
 * allows takes no frame of the thread's stack per level. Nothing here is logged: the answers are
 * member data.
 */
public final class CaseAnswers {

    /** The mark of an answer taken from a case field the question asks for. */
    public static final String FROM_THE_CASE = "from the case";

    /** The mark of the date of service given to a date question nothing else answers. */
    public static final String DEFAULT_DATE = "default, change if needed";

    static final String HIDDEN = "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden";

    private CaseAnswers() {
    }

    /**
     * What the Workbench posts: the questionnaire and the case fields on screen.
     *
     * @param questionnaire the JSON text of the Questionnaire, or of a QuestionnaireResponse that
     *                      contains it (then its answers are the payer's and are kept)
     */
    public record Request(String questionnaire, MemberDetail member, ProviderDetail provider, List<CodeInput> procedureCodes,
                          String dateOfService, String placeOfService, String levelOfService) {

        public Request {
            procedureCodes = procedureCodes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(procedureCodes));
        }

        /** Redacted: the questionnaire and the case can hold member data. */
        @Override
        public String toString() {
            return "CaseAnswers.Request[codes=" + procedureCodes.size() + "]";
        }
    }

    /** The answers found, in the order of the questions. Empty when none could be taken from the case. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(List<Answer> answers) {

        public Result {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }

        @Override
        public String toString() {
            return "CaseAnswers.Result[" + answers.size() + " answer(s)]";
        }
    }

    /**
     * One question answered from the case.
     *
     * @param linkId the question
     * @param values the values as {@link DtrAnswerInput} takes them as plain text: for a choice the
     *               text that chooses the option (its code, else its value), as the page's option
     *               lists carry it. More than one only for a question that repeats
     * @param field  the case field the values came from, such as {@code member.dateOfBirth}
     * @param mark   {@link #FROM_THE_CASE} or {@link #DEFAULT_DATE}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Answer(String linkId, List<String> values, String field, String mark) {

        public Answer {
            values = values == null ? List.of() : List.copyOf(values);
        }

        /** Prints the question and the field, never the values: they are member data. */
        @Override
        public String toString() {
            return "CaseAnswers.Answer[" + linkId + ", " + field + ", " + values.size() + " value(s)]";
        }
    }

    // ---- what a question asks for ----

    /** A case field a question can ask for. */
    private enum Topic {
        NPI("provider.npi"),
        MEMBER_ID("member.memberId"),
        DATE_OF_BIRTH("member.dateOfBirth"),
        GENDER("member.gender"),
        LINE_OF_BUSINESS("member.lineOfBusiness"),
        SPECIALTY("provider.specialty"),
        PLACE_OF_SERVICE("placeOfService"),
        LEVEL_OF_SERVICE("levelOfService"),
        DATE_OF_SERVICE("dateOfService"),
        CODES("procedureCodes"),
        PROVIDER_NAME("provider.name"),
        PROVIDER_FIRST_NAME("provider.firstName"),
        PROVIDER_LAST_NAME("provider.lastName"),
        MEMBER_NAME("member.name"),
        MEMBER_FIRST_NAME("member.firstName"),
        MEMBER_LAST_NAME("member.lastName");

        final String field;

        Topic(String field) {
            this.field = field;
        }
    }

    private static final List<String> NPI_WORDS = List.of("npi", "national provider identifier");
    private static final List<String> MEMBER_ID_WORDS = List.of("member id", "member identifier", "member number", "member identification",
            "beneficiary id", "beneficiary identifier", "insurance id", "medicare id", "medicaid id");
    private static final List<String> BIRTH_WORDS = List.of("date of birth", "birth date", "birthdate", "dob");
    private static final List<String> GENDER_WORDS = List.of("gender", "sex");
    private static final List<String> LINE_OF_BUSINESS_WORDS = List.of("line of business", "lob", "plan type", "insurance type",
            "coverage type");
    private static final List<String> SPECIALTY_WORDS = List.of("specialty", "speciality");
    private static final List<String> PLACE_WORDS = List.of("place of service", "site of service", "location of service",
            "service location", "service setting", "care setting");
    private static final List<String> LEVEL_WORDS = List.of("level of service", "level of care", "service level", "urgency");
    private static final List<String> SERVICE_DATE_WORDS = List.of("date of service", "dates of service", "service date",
            "date of the service", "procedure date", "date of procedure", "date of the procedure");
    private static final List<String> CODE_WORDS = List.of("procedure code", "procedure codes", "cpt", "hcpcs", "service code",
            "service codes", "billing code", "billing codes");
    // A diagnosis code is not a procedure code, though the question may say "code" and "service".
    private static final List<String> NOT_CODE_WORDS = List.of("diagnosis", "icd", "icd10", "icd 10");
    private static final List<String> NAME_WORDS = List.of("name");
    private static final List<String> FIRST_NAME_WORDS = List.of("first name", "given name");
    private static final List<String> LAST_NAME_WORDS = List.of("last name", "family name", "surname");
    private static final List<String> PROVIDER_WORDS = List.of("provider", "physician", "practitioner", "prescriber", "doctor",
            "clinician", "ordering", "requesting", "rendering", "prescribing", "referring", "surgeon");
    private static final List<String> MEMBER_WORDS = List.of("patient", "member", "beneficiary", "enrollee");
    // A name question about one of these is not about the member or the provider, even when it says "patient" or "provider".
    private static final List<String> OTHER_NAME_WORDS = List.of("facility", "organization", "organisation", "plan", "insurance",
            "insurer", "payer", "drug", "medication", "product", "device", "test", "lab", "laboratory", "hospital", "clinic",
            "pharmacy", "practice", "group", "subscriber", "guardian", "caregiver", "parent", "contact", "emergency");

    /** The case field a question's text asks for, or null when it asks for none of them. First match wins. */
    static Topic topic(String questionText) {
        String words = words(questionText);
        if (words.isBlank()) {
            return null;
        }
        if (any(words, NPI_WORDS)) {
            return Topic.NPI;
        }
        if (any(words, MEMBER_ID_WORDS)) {
            return Topic.MEMBER_ID;
        }
        if (any(words, BIRTH_WORDS)) {
            return Topic.DATE_OF_BIRTH;
        }
        if (any(words, GENDER_WORDS)) {
            return Topic.GENDER;
        }
        if (any(words, LINE_OF_BUSINESS_WORDS)) {
            return Topic.LINE_OF_BUSINESS;
        }
        if (any(words, SPECIALTY_WORDS)) {
            return Topic.SPECIALTY;
        }
        if (any(words, PLACE_WORDS)) {
            return Topic.PLACE_OF_SERVICE;
        }
        if (any(words, LEVEL_WORDS)) {
            return Topic.LEVEL_OF_SERVICE;
        }
        if (any(words, SERVICE_DATE_WORDS)) {
            return Topic.DATE_OF_SERVICE;
        }
        if (any(words, CODE_WORDS) && !any(words, NOT_CODE_WORDS)) {
            return Topic.CODES;
        }
        boolean first = any(words, FIRST_NAME_WORDS);
        boolean last = any(words, LAST_NAME_WORDS);
        if ((first || last || any(words, NAME_WORDS)) && !any(words, OTHER_NAME_WORDS)) {
            // The provider first: "the patient's physician name" names the physician.
            if (any(words, PROVIDER_WORDS)) {
                return first ? Topic.PROVIDER_FIRST_NAME : last ? Topic.PROVIDER_LAST_NAME : Topic.PROVIDER_NAME;
            }
            if (any(words, MEMBER_WORDS)) {
                return first ? Topic.MEMBER_FIRST_NAME : last ? Topic.MEMBER_LAST_NAME : Topic.MEMBER_NAME;
            }
        }
        return null;
    }

    /**
     * The text lower-cased, with every character that is not a letter or a digit made a space, runs
     * of spaces made one, and a space at each end, so a word or phrase is found whole by looking for
     * it with a space on each side.
     */
    static String words(String text) {
        if (text == null) {
            return " ";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length() + 2).append(' ');
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
        }
        if (out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
        return out.toString();
    }

    private static boolean any(String words, List<String> phrases) {
        for (String phrase : phrases) {
            if (words.contains(" " + phrase + " ")) {
                return true;
            }
        }
        return false;
    }

    // ---- the answers ----

    /**
     * The answers the case gives to the questionnaire's questions.
     *
     * @throws ValidationException at {@code questionnaire} when it is missing, not JSON, or holds no
     *                             questionnaire whose questions can be read; at {@code dateOfService}
     *                             when it is not a date
     */
    public static Result answer(Request request) {
        if (request == null || Texts.blank(request.questionnaire())) {
            throw ValidationException.of("questionnaire", "Required: the questionnaire as JSON text.");
        }
        String dateOfService = Texts.trimToNull(request.dateOfService());
        if (dateOfService != null && !isoDate(dateOfService)) {
            throw ValidationException.of("dateOfService", "Not a date: write it as yyyy-mm-dd.");
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(request.questionnaire());
        if (parsed.node() == null) {
            throw ValidationException.of("questionnaire", parsed.error());
        }
        JsonNode body = parsed.node();
        JsonNode questionnaire;
        Set<String> payerAnswered = new HashSet<>();
        if (QuestionnaireJson.isType(body, "Questionnaire")) {
            questionnaire = body;
        } else if (QuestionnaireJson.isType(body, "QuestionnaireResponse")) {
            questionnaire = QuestionnaireJson.containedQuestionnaire(body);
            if (questionnaire == null) {
                throw ValidationException.of("questionnaire", "The QuestionnaireResponse holds no questionnaire inside it that can be "
                        + "told to be the one it answers, so its questions cannot be read.");
            }
            answeredLinkIds(body, payerAnswered);
        } else {
            throw ValidationException.of("questionnaire", "It is neither a Questionnaire nor a QuestionnaireResponse, so no questions "
                    + "can be read from it.");
        }

        List<JsonNode> questions = new ArrayList<>();
        Map<String, Integer> linkIdCount = new HashMap<>();
        for (JsonNode item : itemsInOrder(questionnaire)) {
            String linkId = JsonSafe.presentText(item, "linkId");
            if (linkId != null) {
                linkIdCount.merge(linkId, 1, Integer::sum);
            }
            if (isQuestion(item)) {
                questions.add(item);
            }
        }

        List<Answer> answers = new ArrayList<>();
        for (JsonNode item : questions) {
            String linkId = JsonSafe.presentText(item, "linkId");
            // An answer is tied to its question by linkId alone: one used twice has nowhere certain to go.
            if (linkId == null || linkIdCount.getOrDefault(linkId, 0) > 1 || payerAnswered.contains(linkId) || payerOwns(item)) {
                continue;
            }
            Topic topic = topic(JsonSafe.presentText(item, "text"));
            if (topic != null) {
                List<String> values = values(item, topic, request);
                if (!values.isEmpty()) {
                    answers.add(new Answer(linkId, values, topic.field, FROM_THE_CASE));
                }
                // A question that asks for a field the case does not hold stays empty: the date default is not for it.
                continue;
            }
            String type = JsonSafe.presentText(item, "type");
            if (dateOfService != null && ("date".equals(type) || "dateTime".equals(type))) {
                answers.add(new Answer(linkId, List.of(dateOfService), Topic.DATE_OF_SERVICE.field, DEFAULT_DATE));
            }
        }
        return new Result(answers);
    }

    /** Every item of the questionnaire, each parent before its children, in document order. */
    private static List<JsonNode> itemsInOrder(JsonNode questionnaire) {
        List<JsonNode> out = new ArrayList<>();
        Deque<JsonNode> stack = new ArrayDeque<>();
        pushItems(stack, JsonSafe.child(questionnaire, "item"));
        while (!stack.isEmpty()) {
            JsonNode item = stack.pop();
            out.add(item);
            pushItems(stack, JsonSafe.child(item, "item"));
        }
        return out;
    }

    /** Pushes the objects of an item list last first, so they come off the stack in document order. */
    private static void pushItems(Deque<JsonNode> stack, JsonNode items) {
        List<JsonNode> elements = JsonSafe.elements(items);
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i).isObject()) {
                stack.push(elements.get(i));
            }
        }
    }

    /**
     * The linkIds a response already answers, wherever FHIR lets an item sit: beneath a group and
     * beneath the answer of a question that has follow-up items.
     */
    private static void answeredLinkIds(JsonNode response, Set<String> into) {
        Deque<JsonNode> stack = new ArrayDeque<>();
        pushItems(stack, JsonSafe.child(response, "item"));
        while (!stack.isEmpty()) {
            JsonNode item = stack.pop();
            String linkId = JsonSafe.presentText(item, "linkId");
            for (JsonNode answer : JsonSafe.elements(item, "answer")) {
                if (linkId != null && QuestionnaireJson.answered(answer)) {
                    into.add(linkId);
                }
                pushItems(stack, JsonSafe.child(answer, "item"));
            }
            pushItems(stack, JsonSafe.child(item, "item"));
        }
    }

    /** As {@link QuestionnaireJson.Item#question()}: an item that is neither a group nor display text. */
    private static boolean isQuestion(JsonNode item) {
        String type = JsonSafe.presentText(item, "type");
        if ("group".equals(type) || "display".equals(type)) {
            return false;
        }
        return type != null || JsonSafe.elements(item, "item").isEmpty();
    }

    /**
     * True when the payer has already given the question its value or kept it from the tester: an
     * initial value, an option selected to begin with, read only, or hidden.
     */
    private static boolean payerOwns(JsonNode item) {
        if (Boolean.TRUE.equals(JsonSafe.bool(item, "readOnly"))) {
            return true;
        }
        for (JsonNode initial : JsonSafe.elements(item, "initial")) {
            if (QuestionnaireJson.answered(initial)) {
                return true;
            }
        }
        for (JsonNode option : JsonSafe.elements(item, "answerOption")) {
            if (Boolean.TRUE.equals(JsonSafe.bool(option, "initialSelected"))) {
                return true;
            }
        }
        for (JsonNode extension : JsonSafe.elements(item, "extension")) {
            if (HIDDEN.equals(JsonSafe.presentText(extension, "url")) && Boolean.TRUE.equals(JsonSafe.bool(extension, "valueBoolean"))) {
                return true;
            }
        }
        return false;
    }

    /** The values the case gives a question about this topic, fitted to the question's type. Empty when none fits. */
    private static List<String> values(JsonNode item, Topic topic, Request request) {
        MemberDetail member = request.member();
        ProviderDetail provider = request.provider();
        String type = JsonSafe.presentText(item, "type");
        return switch (topic) {
            case NPI -> text(item, type, provider == null ? null : provider.npi());
            case MEMBER_ID -> text(item, type, member == null ? null : member.memberId());
            case DATE_OF_BIRTH -> date(type, member == null ? null : member.dateOfBirth());
            case GENDER -> text(item, type, member == null ? null : member.gender());
            case LINE_OF_BUSINESS -> text(item, type, member == null ? null : member.lineOfBusiness());
            case SPECIALTY -> text(item, type, provider == null ? null : provider.specialty());
            case PLACE_OF_SERVICE -> coded(item, type, request.placeOfService(), PasOptions.CURRENT.placesOfService());
            case LEVEL_OF_SERVICE -> coded(item, type, request.levelOfService(), PasOptions.CURRENT.levelsOfService());
            case DATE_OF_SERVICE -> date(type, request.dateOfService());
            case CODES -> codes(item, type, request.procedureCodes());
            case PROVIDER_NAME -> text(item, type, provider == null ? null : provider.displayName());
            case PROVIDER_FIRST_NAME -> text(item, type, provider == null ? null : provider.firstName());
            case PROVIDER_LAST_NAME -> text(item, type, provider == null ? null : provider.lastName());
            case MEMBER_NAME -> text(item, type, member == null ? null : fullName(member));
            case MEMBER_FIRST_NAME -> text(item, type, member == null ? null : member.firstName());
            case MEMBER_LAST_NAME -> text(item, type, member == null ? null : member.lastName());
        };
    }

    private static String fullName(MemberDetail member) {
        String first = Texts.trimToNull(member.firstName());
        String last = Texts.trimToNull(member.lastName());
        return first != null && last != null ? first + " " + last : Texts.firstPresent(last, first);
    }

    private static boolean takesText(String type) {
        return type == null || "string".equals(type) || "text".equals(type);
    }

    private static boolean choice(String type) {
        return "choice".equals(type) || "open-choice".equals(type);
    }

    /** A case value as text, or as the option that carries it for a choice. */
    private static List<String> text(JsonNode item, String type, String value) {
        String given = Texts.trimToNull(value);
        if (given == null) {
            return List.of();
        }
        if (takesText(type)) {
            return List.of(given);
        }
        if (choice(type)) {
            String option = option(item, List.of(given));
            if (option != null) {
                return List.of(option);
            }
            // An open choice takes a value its options do not list; a closed one does not, and gets nothing.
            return "open-choice".equals(type) ? List.of(given) : List.of();
        }
        return List.of();
    }

    /** A case date for a date question, or for a text question as written. */
    private static List<String> date(String type, String value) {
        String given = Texts.trimToNull(value);
        if (given == null) {
            return List.of();
        }
        if ("date".equals(type) || "dateTime".equals(type)) {
            return isoDate(given) ? List.of(given) : List.of();
        }
        return takesText(type) ? List.of(given) : List.of();
    }

    /**
     * A code of one of the Service card's lists: a choice by the option that carries the code or
     * its words, a text question as the words with the code after them.
     */
    private static List<String> coded(JsonNode item, String type, String code, List<PasOptions.Option> list) {
        String given = Texts.trimToNull(code);
        if (given == null) {
            return List.of();
        }
        String label = PasOptions.label(list, given);
        if (takesText(type)) {
            return List.of(label == null ? given : label + " (" + given + ")");
        }
        if (choice(type)) {
            String option = option(item, label == null ? List.of(given) : List.of(given, label));
            if (option != null) {
                return List.of(option);
            }
            return "open-choice".equals(type) ? List.of(label == null ? given : label + " (" + given + ")") : List.of();
        }
        return List.of();
    }

    /**
     * The procedure codes: one value per code for a question that repeats, else the codes in one
     * text. A choice takes the options that carry a code or its description; a choice that does not
     * repeat takes one only when exactly one option matches, because picking among several would be
     * a guess.
     */
    private static List<String> codes(JsonNode item, String type, List<CodeInput> codes) {
        List<CodeInput> given = codes.stream().filter(code -> code != null && Texts.present(code.code())).toList();
        if (given.isEmpty()) {
            return List.of();
        }
        boolean repeats = Boolean.TRUE.equals(JsonSafe.bool(item, "repeats"));
        if (takesText(type)) {
            List<String> texts = given.stream().map(code -> code.code().strip()).toList();
            return repeats ? texts : List.of(String.join(", ", texts));
        }
        if (choice(type)) {
            List<String> chosen = new ArrayList<>();
            for (CodeInput code : given) {
                List<String> wanted = Texts.present(code.description()) ? List.of(code.code().strip(), code.description().strip())
                        : List.of(code.code().strip());
                String option = option(item, wanted);
                if (option != null && !chosen.contains(option)) {
                    chosen.add(option);
                }
            }
            return repeats || chosen.size() == 1 ? chosen : List.of();
        }
        return List.of();
    }

    /**
     * The text that chooses the first option whose code, display or value equals one of the wanted
     * texts, without regard to case. Null when none does. The text is the option's
     * {@link QuestionnaireJson#valueText}, which is what the page's option lists post.
     */
    private static String option(JsonNode item, List<String> wanted) {
        for (JsonNode option : JsonSafe.elements(item, "answerOption")) {
            String key = QuestionnaireJson.valueKey(option, "value");
            if (key == null) {
                continue;
            }
            JsonNode value = option.get(key);
            String answerAs = QuestionnaireJson.valueText(key, value);
            if (answerAs == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            names.add(answerAs);
            if (value.isObject()) {
                names.add(JsonSafe.presentText(value, "code"));
                names.add(JsonSafe.presentText(value, "display"));
            }
            for (String name : names) {
                if (name == null) {
                    continue;
                }
                for (String text : wanted) {
                    if (name.strip().equalsIgnoreCase(text)) {
                        return answerAs;
                    }
                }
            }
        }
        return null;
    }

    /** True for a date written yyyy-mm-dd with a month and a day that can be, as a date input sends it. */
    static boolean isoDate(String text) {
        if (text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (i != 4 && i != 7 && (text.charAt(i) < '0' || text.charAt(i) > '9')) {
                return false;
            }
        }
        try {
            java.time.LocalDate.parse(text);
            return true;
        } catch (java.time.format.DateTimeParseException notADate) {
            return false;
        }
    }
}
