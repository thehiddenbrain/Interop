package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.slf4j.LoggerFactory;

/**
 * Answers from the case (plan R-18): each case field a question can ask for, a choice matched by
 * code and by display, the date default only with a date of service, nothing for what the case
 * cannot answer, the payer's own answers kept, and nothing logged.
 */
class CaseAnswersTest {

    private static final MemberDetail MEMBER = MemberDetail.builder().memberId("S123456789").firstName("Given")
            .lastName("Placeholder-Surname").dateOfBirth("1950-01-31").gender("female").lineOfBusiness("Medicare").build();
    private static final ProviderDetail PROVIDER = ProviderDetail.builder().npi("1234567893").firstName("Test").lastName("Provider-Placeholder")
            .specialty("Sleep medicine").build();
    private static final List<CodeInput> CODES = List.of(new CodeInput("95810", "http://www.ama-assn.org/go/cpt", "Polysomnography"),
            CodeInput.of("95811"));

    private static String question(String linkId, String text, String type) {
        return "{\"linkId\":\"" + linkId + "\",\"text\":\"" + text + "\",\"type\":\"" + type + "\"}";
    }

    private static String questionnaire(String... items) {
        return "{\"resourceType\":\"Questionnaire\",\"status\":\"active\",\"item\":[" + String.join(",", items) + "]}";
    }

    private static CaseAnswers.Request full(String questionnaire) {
        return new CaseAnswers.Request(questionnaire, MEMBER, PROVIDER, CODES, "2026-03-20", "11", "U");
    }

    private static Map<String, CaseAnswers.Answer> byLinkId(CaseAnswers.Result result) {
        Map<String, CaseAnswers.Answer> out = new LinkedHashMap<>();
        result.answers().forEach(answer -> out.put(answer.linkId(), answer));
        return out;
    }

    @Test
    void eachCaseFieldAnswersTheQuestionThatAsksForIt() {
        String q = questionnaire(
                question("name", "Patient name", "string"),
                question("first", "Patient first name", "string"),
                question("last", "Member's last name", "string"),
                question("dob", "Date of birth", "date"),
                question("gender", "Patient gender", "string"),
                question("memberId", "Member ID", "string"),
                question("lob", "Line of business", "string"),
                question("provider", "Ordering provider name", "string"),
                question("npi", "Ordering provider NPI", "string"),
                question("specialty", "Provider specialty", "text"),
                question("codes", "Requested CPT or HCPCS codes", "string"),
                question("dos", "Requested date of service", "date"),
                question("pos", "Place of service", "string"),
                question("los", "Level of service", "string"));
        Map<String, CaseAnswers.Answer> answers = byLinkId(CaseAnswers.answer(full(q)));

        assertThat(answers.keySet()).containsExactly("name", "first", "last", "dob", "gender", "memberId", "lob", "provider", "npi", "specialty",
                "codes", "dos", "pos", "los");
        assertThat(answers.values()).allSatisfy(answer -> assertThat(answer.mark()).isEqualTo(CaseAnswers.FROM_THE_CASE));
        assertThat(answers.get("name").values()).containsExactly("Given Placeholder-Surname");
        assertThat(answers.get("name").field()).isEqualTo("member.name");
        assertThat(answers.get("first").values()).containsExactly("Given");
        assertThat(answers.get("last").values()).containsExactly("Placeholder-Surname");
        assertThat(answers.get("dob").values()).containsExactly("1950-01-31");
        assertThat(answers.get("dob").field()).isEqualTo("member.dateOfBirth");
        assertThat(answers.get("gender").values()).containsExactly("female");
        assertThat(answers.get("memberId").values()).containsExactly("S123456789");
        assertThat(answers.get("memberId").field()).isEqualTo("member.memberId");
        assertThat(answers.get("lob").values()).containsExactly("Medicare");
        assertThat(answers.get("provider").values()).containsExactly("Test Provider-Placeholder");
        assertThat(answers.get("provider").field()).isEqualTo("provider.name");
        assertThat(answers.get("npi").values()).containsExactly("1234567893");
        assertThat(answers.get("npi").field()).isEqualTo("provider.npi");
        assertThat(answers.get("specialty").values()).containsExactly("Sleep medicine");
        assertThat(answers.get("codes").values()).containsExactly("95810, 95811");
        assertThat(answers.get("codes").field()).isEqualTo("procedureCodes");
        assertThat(answers.get("dos").values()).containsExactly("2026-03-20");
        assertThat(answers.get("dos").field()).isEqualTo("dateOfService");
        assertThat(answers.get("pos").values()).containsExactly("Office (11)");
        assertThat(answers.get("pos").field()).isEqualTo("placeOfService");
        assertThat(answers.get("los").values()).containsExactly("Urgent (U)");
        assertThat(answers.get("los").field()).isEqualTo("levelOfService");
    }

    @Test
    void aQuestionThatRepeatsTakesOneValuePerCode() {
        String q = questionnaire("{\"linkId\":\"codes\",\"text\":\"Procedure codes\",\"type\":\"string\",\"repeats\":true}");
        assertThat(CaseAnswers.answer(full(q)).answers().get(0).values()).containsExactly("95810", "95811");
    }

    @Test
    void aChoiceIsMatchedByCodeAndByDisplay() {
        String q = questionnaire(
                // by display: the case says "female", the option's code is F
                "{\"linkId\":\"gender\",\"text\":\"Sex\",\"type\":\"choice\",\"answerOption\":["
                        + "{\"valueCoding\":{\"code\":\"M\",\"display\":\"Male\"}},{\"valueCoding\":{\"code\":\"F\",\"display\":\"Female\"}}]}",
                // by code
                "{\"linkId\":\"pos\",\"text\":\"Place of service\",\"type\":\"choice\",\"answerOption\":["
                        + "{\"valueCoding\":{\"code\":\"21\",\"display\":\"Inpatient\"}},{\"valueCoding\":{\"code\":\"11\",\"display\":\"Clinic visit\"}}]}",
                // by the words of the Service card's code: U is "Urgent"
                "{\"linkId\":\"los\",\"text\":\"Urgency\",\"type\":\"choice\",\"answerOption\":["
                        + "{\"valueCoding\":{\"code\":\"routine\",\"display\":\"Routine\"}},{\"valueCoding\":{\"code\":\"urgent\",\"display\":\"Urgent\"}}]}",
                // a string option, by its value
                "{\"linkId\":\"lob\",\"text\":\"Line of business\",\"type\":\"choice\",\"answerOption\":["
                        + "{\"valueString\":\"Commercial\"},{\"valueString\":\"Medicare\"}]}",
                // a code by its description
                "{\"linkId\":\"codes\",\"text\":\"Procedure code\",\"type\":\"choice\",\"answerOption\":["
                        + "{\"valueCoding\":{\"code\":\"PSG\",\"display\":\"polysomnography\"}},{\"valueCoding\":{\"code\":\"HST\",\"display\":\"Home sleep test\"}}]}");
        Map<String, CaseAnswers.Answer> answers = byLinkId(CaseAnswers.answer(full(q)));

        assertThat(answers.get("gender").values()).containsExactly("F");
        assertThat(answers.get("pos").values()).containsExactly("11");
        assertThat(answers.get("los").values()).containsExactly("urgent");
        assertThat(answers.get("lob").values()).containsExactly("Medicare");
        assertThat(answers.get("codes").values()).containsExactly("PSG");
        assertThat(answers.values()).allSatisfy(answer -> assertThat(answer.mark()).isEqualTo(CaseAnswers.FROM_THE_CASE));
    }

    @Test
    void aClosedChoiceWithNoOptionForTheCaseValueGetsNothingAndAnOpenOneGetsTheText() {
        String options = "\"answerOption\":[{\"valueCoding\":{\"code\":\"C\",\"display\":\"Commercial\"}}]";
        String q = questionnaire(
                "{\"linkId\":\"closed\",\"text\":\"Line of business\",\"type\":\"choice\"," + options + "}",
                "{\"linkId\":\"open\",\"text\":\"Plan type\",\"type\":\"open-choice\"," + options + "}");
        Map<String, CaseAnswers.Answer> answers = byLinkId(CaseAnswers.answer(full(q)));

        assertThat(answers).doesNotContainKey("closed");
        assertThat(answers.get("open").values()).containsExactly("Medicare");
    }

    @Test
    void aChoiceThatDoesNotRepeatTakesACodeOnlyWhenExactlyOneOptionMatches() {
        String q = questionnaire("{\"linkId\":\"codes\",\"text\":\"CPT code\",\"type\":\"choice\",\"answerOption\":["
                + "{\"valueCoding\":{\"code\":\"95810\"}},{\"valueCoding\":{\"code\":\"95811\"}}]}");
        assertThat(CaseAnswers.answer(full(q)).answers()).isEmpty();
    }

    @Test
    void aDateQuestionNothingElseAnswersGetsTheDateOfServiceAsADefault() {
        String q = questionnaire(question("when", "When did symptoms start?", "date"), question("at", "Last visit", "dateTime"));
        List<CaseAnswers.Answer> answers = CaseAnswers.answer(full(q)).answers();

        assertThat(answers).extracting(CaseAnswers.Answer::linkId).containsExactly("when", "at");
        assertThat(answers).allSatisfy(answer -> {
            assertThat(answer.values()).containsExactly("2026-03-20");
            assertThat(answer.mark()).isEqualTo(CaseAnswers.DEFAULT_DATE);
            assertThat(answer.field()).isEqualTo("dateOfService");
        });
    }

    @Test
    void withNoDateOfServiceNoDateIsFilledAndNoneIsMadeUp() {
        String q = questionnaire(question("when", "When did symptoms start?", "date"), question("dos", "Date of service", "date"),
                question("dob", "Date of birth", "date"));
        CaseAnswers.Request request = new CaseAnswers.Request(q, MEMBER.toBuilder().dateOfBirth(null).build(), PROVIDER, CODES, null, "11", "U");
        assertThat(CaseAnswers.answer(request).answers()).isEmpty();
        CaseAnswers.Request blank = new CaseAnswers.Request(q, MEMBER.toBuilder().dateOfBirth(null).build(), PROVIDER, CODES, "  ", null, null);
        assertThat(CaseAnswers.answer(blank).answers()).isEmpty();
    }

    @Test
    void aDateOfBirthQuestionTheCaseCannotAnswerDoesNotTakeTheDateDefault() {
        String q = questionnaire(question("dob", "Patient date of birth", "date"));
        CaseAnswers.Request request = new CaseAnswers.Request(q, MEMBER.toBuilder().dateOfBirth(null).build(), PROVIDER, CODES, "2026-03-20",
                null, null);
        assertThat(CaseAnswers.answer(request).answers()).isEmpty();
    }

    @Test
    void nothingIsGivenForAQuestionTheCaseCannotAnswer() {
        String q = questionnaire(
                question("pt", "Has the patient tried physical therapy for at least six weeks?", "boolean"),
                question("ahi", "Apnea hypopnea index", "decimal"),
                question("notes", "Clinical notes", "text"),
                question("dx", "Diagnosis code (ICD-10)", "string"),
                question("facility", "Name of the facility where the patient will be seen", "string"),
                question("guardian", "Patient guardian name", "string"),
                question("bare", "Name", "string"),
                question("npiNumber", "NPI", "integer"),
                question("phone", "Provider phone", "string"),
                "{\"linkId\":\"untyped-group\",\"text\":\"Patient name\",\"item\":[" + question("inner", "Comments", "string") + "]}",
                "{\"linkId\":\"display\",\"text\":\"Patient name\",\"type\":\"display\"}");
        assertThat(CaseAnswers.answer(full(q)).answers()).isEmpty();
    }

    @Test
    void anEmptyCaseAnswersNothing() {
        String q = questionnaire(question("name", "Patient name", "string"), question("npi", "NPI", "string"), question("codes", "CPT", "string"),
                question("pos", "Place of service", "string"));
        CaseAnswers.Request empty = new CaseAnswers.Request(q, null, null, null, null, null, null);
        assertThat(CaseAnswers.answer(empty).answers()).isEmpty();
        CaseAnswers.Request blanks = new CaseAnswers.Request(q, MemberDetail.builder().firstName(" ").build(),
                ProviderDetail.builder().npi("").build(), List.of(CodeInput.of(" ")), null, " ", null);
        assertThat(CaseAnswers.answer(blanks).answers()).isEmpty();
    }

    @Test
    void thePayersOwnAnswersWin() {
        String questionnaire = "{\"resourceType\":\"Questionnaire\",\"id\":\"q1\",\"item\":["
                + question("name", "Patient name", "string") + ","
                + question("dob", "Date of birth", "date") + ","
                + "{\"linkId\":\"npi\",\"text\":\"NPI\",\"type\":\"string\",\"initial\":[{\"valueString\":\"1111111111\"}]},"
                + "{\"linkId\":\"memberId\",\"text\":\"Member ID\",\"type\":\"string\",\"readOnly\":true},"
                + "{\"linkId\":\"lob\",\"text\":\"Line of business\",\"type\":\"string\",\"extension\":[{\"url\":\""
                + CaseAnswers.HIDDEN + "\",\"valueBoolean\":true}]},"
                + "{\"linkId\":\"gender\",\"text\":\"Gender\",\"type\":\"choice\",\"answerOption\":["
                + "{\"valueCoding\":{\"code\":\"female\"}},{\"valueCoding\":{\"code\":\"unknown\"},\"initialSelected\":true}]},"
                + "{\"linkId\":\"grp\",\"type\":\"group\",\"item\":[" + question("specialty", "Specialty", "string") + "]}"
                + "]}";
        String response = "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"#q1\",\"status\":\"in-progress\","
                + "\"contained\":[" + questionnaire + "],\"item\":["
                + "{\"linkId\":\"name\",\"answer\":[{\"valueString\":\"Payer Given Name\"}]},"
                + "{\"linkId\":\"dob\",\"answer\":[{\"valueString\":\"\"}]},"
                + "{\"linkId\":\"grp\",\"item\":[{\"linkId\":\"specialty\",\"answer\":[{\"valueString\":\"Pulmonology\"}]}]}]}";
        List<CaseAnswers.Answer> answers = CaseAnswers.answer(full(response)).answers();

        // Only the date of birth: the payer's empty answer is no answer, and every other question is the payer's.
        assertThat(answers).extracting(CaseAnswers.Answer::linkId).containsExactly("dob");
        assertThat(answers.get(0).values()).containsExactly("1950-01-31");
    }

    @Test
    void aLinkIdUsedTwiceGetsNothing() {
        String q = questionnaire(question("same", "Patient name", "string"), question("same", "Member ID", "string"));
        assertThat(CaseAnswers.answer(full(q)).answers()).isEmpty();
    }

    @Test
    void aDateOfServiceGivenToAnotherTypeIsNotSent() {
        String q = questionnaire(question("dos", "Date of service", "integer"));
        assertThat(CaseAnswers.answer(full(q)).answers()).isEmpty();
    }

    @Test
    void questionTextIsMatchedByWholeWords() {
        assertThat(CaseAnswers.topic("Is the stay inpatient?")).isNull();
        assertThat(CaseAnswers.topic("Patient's DOB")).isNotNull();
        assertThat(CaseAnswers.topic("MEMBER-ID")).isNotNull();
        assertThat(CaseAnswers.topic("Is there a sexually transmitted infection?")).isNull();
        assertThat(CaseAnswers.words("  Date:of\tBirth!! ")).isEqualTo(" date of birth ");
    }

    @Test
    void itemsNestedAsDeepAsTheParserAllowsAreAnsweredWithoutRecursion() {
        // Each group is an object and a list, two of the parser's 500 levels: 240 groups and the question take 483.
        int depth = 240;
        StringBuilder q = new StringBuilder("{\"resourceType\":\"Questionnaire\",\"item\":[");
        for (int i = 0; i < depth; i++) {
            q.append("{\"linkId\":\"g").append(i).append("\",\"type\":\"group\",\"item\":[");
        }
        q.append(question("deep", "Member ID", "string"));
        q.append("]}".repeat(depth)).append("]}");
        AtomicReference<CaseAnswers.Result> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // A quarter of a request thread's stack: a walk that took a frame per level would not fit.
        Thread thread = new Thread(null, () -> {
            try {
                result.set(CaseAnswers.answer(full(q.toString())));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "case-answers-deep", 256 * 1024);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        assertThat(failure.get()).isNull();
        assertThat(result.get().answers()).extracting(CaseAnswers.Answer::linkId).containsExactly("deep");
    }

    @Test
    void aQuestionnaireThatCannotBeReadIsRefusedAtItsField() {
        assertRefused(new CaseAnswers.Request(null, MEMBER, PROVIDER, CODES, null, null, null), "questionnaire", "Required");
        assertRefused(new CaseAnswers.Request(" ", MEMBER, PROVIDER, CODES, null, null, null), "questionnaire", "Required");
        assertRefused(new CaseAnswers.Request("{\"resourceType\":", MEMBER, PROVIDER, CODES, null, null, null), "questionnaire", "not valid JSON");
        assertRefused(new CaseAnswers.Request("{\"resourceType\":\"Patient\"}", MEMBER, PROVIDER, CODES, null, null, null), "questionnaire",
                "neither a Questionnaire nor a QuestionnaireResponse");
        assertRefused(new CaseAnswers.Request("{\"resourceType\":\"QuestionnaireResponse\",\"item\":[]}", MEMBER, PROVIDER, CODES, null, null,
                null), "questionnaire", "holds no questionnaire inside it");
        assertRefused(new CaseAnswers.Request(questionnaire(), MEMBER, PROVIDER, CODES, "03/20/2026", null, null), "dateOfService",
                "yyyy-mm-dd");
        assertRefused(new CaseAnswers.Request(questionnaire(), MEMBER, PROVIDER, CODES, "2026-02-30", null, null), "dateOfService",
                "yyyy-mm-dd");
    }

    private static void assertRefused(CaseAnswers.Request request, String field, String words) {
        assertThatThrownBy(() -> CaseAnswers.answer(request)).isInstanceOf(ValidationException.class).satisfies(e -> {
            ValidationException refusal = (ValidationException) e;
            assertThat(refusal.fieldErrors()).hasSize(1);
            assertThat(refusal.fieldErrors().get(0).field()).isEqualTo(field);
            assertThat(refusal.fieldErrors().get(0).message()).contains(words);
        });
    }

    @Test
    void nothingIsLoggedAndNoRecordPrintsMemberData() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        String thread = Thread.currentThread().getName();
        CaseAnswers.Result result;
        try {
            result = CaseAnswers.answer(full(questionnaire(question("name", "Patient name", "string"), question("id", "Member ID", "string"))));
            try {
                CaseAnswers.answer(new CaseAnswers.Request("{\"S123456789\":", MEMBER, PROVIDER, CODES, null, null, null));
            } catch (ValidationException expected) {
                assertThat(expected.fieldErrors().get(0).message()).doesNotContain("S123456789");
            }
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
            appender.stop();
        }
        List<ILoggingEvent> events = new ArrayList<>(appender.list);
        assertThat(events.stream().filter(event -> thread.equals(event.getThreadName()))).isEmpty();

        assertThat(result.answers()).hasSize(2);
        String printed = result + " " + result.answers() + " " + full("{\"text\":\"S123456789\"}");
        assertThat(printed).doesNotContain("S123456789", "Given", "Placeholder-Surname", "1950-01-31");
    }
}
