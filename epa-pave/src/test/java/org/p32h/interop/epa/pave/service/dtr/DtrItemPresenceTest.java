package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.dtr.ItemView;

/**
 * The lists of a questionnaire item say whether each was not returned, returned as null or
 * returned empty. A choice question with no options reads the same three ways otherwise.
 */
class DtrItemPresenceTest {

    private final DtrResponseMapper mapper = new DtrResponseMapper();

    private ItemView item(String fields) {
        String body = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Questionnaire\","
                + "\"status\":\"active\",\"item\":[{\"linkId\":\"q1\",\"type\":\"choice\",\"text\":\"Pick one\"" + (fields.isEmpty() ? "" : "," + fields)
                + "}]}}]}";
        return mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, body), List.of())
                .detail().questionnaires().get(0).items().get(0);
    }

    @Test
    void theAnswerOptionsOfAQuestionAreSaidToBeNotReturnedNullOrEmpty() {
        assertThat(item("").lists()).containsEntry("answerOption", Presence.ABSENT);
        assertThat(item("\"answerOption\":null").lists()).containsEntry("answerOption", Presence.NULL);
        assertThat(item("\"answerOption\":[]").lists()).containsEntry("answerOption", Presence.EMPTY);
        assertThat(item("\"answerOption\":[{\"valueString\":\"Yes\"}]").lists()).containsEntry("answerOption", Presence.VALUE);
        assertThat(item("\"answerOption\":null").answerOptions()).isEmpty();
        assertThat(item("\"answerOption\":[]").answerOptions()).isEmpty();
    }

    @Test
    void everyOtherListOfTheItemSaysWhatItWas() {
        ItemView view = item("\"initial\":null,\"enableWhen\":[],\"item\":null");
        assertThat(view.lists()).containsEntry("initial", Presence.NULL).containsEntry("enableWhen", Presence.EMPTY)
                .containsEntry("item", Presence.NULL).containsEntry("extension", Presence.ABSENT);
    }
}
