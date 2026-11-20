package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.PasJson.action;
import static org.p32h.interop.epa.pave.service.pas.PasJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.PasJson.items;
import static org.p32h.interop.epa.pave.service.pas.PasJson.response;
import static org.p32h.interop.epa.pave.service.pas.PasJson.sent;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.PasItemView;

/**
 * The periods of a PAS answer and the lists of a ClaimResponse, its items and their adjudications
 * say whether each was not returned, returned as null or returned empty. A start and an end, or an
 * empty list, cannot say it: a null period and an empty one both have neither.
 */
class PasPresenceTest {

    private final PasResponseMapper mapper = new PasResponseMapper();

    private ClaimResponseView read(String... fields) {
        return mapper.read(response(200, bundle(claimResponse(fields))), sent("95810")).detail().claimResponses().get(0);
    }

    private static String periodItem(String valuePeriod) {
        String period = valuePeriod == null ? "" : ",\"valuePeriod\":" + valuePeriod;
        return "{\"itemSequence\":1,\"extension\":[{\"url\":\"" + OnyxPasConventions.ITEM_PRE_AUTH_PERIOD + "\"" + period + "}],"
                + "\"adjudication\":[{\"category\":{\"coding\":[{\"code\":\"submitted\"}]},\"extension\":[" + action("A1") + "]}]}";
    }

    @Test
    void thePeriodOfTheWholeResponseSaysWhatItWas() {
        assertThat(read("\"outcome\":\"complete\"").preAuthPeriod()).isEqualTo(Presence.ABSENT);
        assertThat(read("\"preAuthPeriod\":null").preAuthPeriod()).isEqualTo(Presence.NULL);
        assertThat(read("\"preAuthPeriod\":{}").preAuthPeriod()).isEqualTo(Presence.EMPTY);
        ClaimResponseView stated = read("\"preAuthPeriod\":{\"start\":\"2026-02-01\"}");
        assertThat(stated.preAuthPeriod()).isEqualTo(Presence.VALUE);
        assertThat(stated.preAuthPeriodStart().value()).isEqualTo("2026-02-01");
    }

    @Test
    void thePeriodOfAnItemSaysWhatTheExtensionHeld() {
        assertThat(read(items(periodItem("null"))).decisions().get(0).authorizedPeriod()).isEqualTo(Presence.NULL);
        assertThat(read(items(periodItem("{}"))).decisions().get(0).authorizedPeriod()).isEqualTo(Presence.EMPTY);
        // The extension was sent with no period in it at all.
        assertThat(read(items(periodItem(null))).decisions().get(0).authorizedPeriod()).isEqualTo(Presence.ABSENT);
        assertThat(read(items(periodItem("{\"start\":\"2026-02-01\"}"))).decisions().get(0).authorizedPeriod()).isEqualTo(Presence.VALUE);
        // No such extension: the item says nothing about a period, which is not the same as an empty one.
        assertThat(read(items(PasJson.decided(1, "A1"))).decisions().get(0).authorizedPeriod()).isNull();
        assertThat(read(items(periodItem("null"))).decisions().get(0).named("other").authorizedPeriod()).isEqualTo(Presence.NULL);
    }

    @Test
    void theListsOfAnItemAndItsAdjudicationsSayWhatTheyWere() {
        PasItemView absent = read(items("{\"itemSequence\":1}")).items().get(0);
        assertThat(absent.lists()).containsEntry("adjudication", Presence.ABSENT).containsEntry("extension", Presence.ABSENT)
                .containsEntry("noteNumber", Presence.ABSENT);
        assertThat(read(items("{\"itemSequence\":1,\"adjudication\":null}")).items().get(0).lists())
                .containsEntry("adjudication", Presence.NULL);
        assertThat(read(items("{\"itemSequence\":1,\"adjudication\":[]}")).items().get(0).lists())
                .containsEntry("adjudication", Presence.EMPTY);
        PasItemView decided = read(items(PasJson.decided(1, "A1"))).items().get(0);
        assertThat(decided.lists()).containsEntry("adjudication", Presence.VALUE);
        assertThat(decided.adjudications().get(0).lists()).containsEntry("extension", Presence.VALUE);
    }

    @Test
    void theListsOfTheClaimResponseSayWhatTheyWere() {
        ClaimResponseView view = read("\"item\":null", "\"error\":[]");
        assertThat(view.lists()).containsEntry("item", Presence.NULL).containsEntry("error", Presence.EMPTY)
                .containsEntry("processNote", Presence.ABSENT).containsEntry("identifier", Presence.ABSENT);
    }
}
