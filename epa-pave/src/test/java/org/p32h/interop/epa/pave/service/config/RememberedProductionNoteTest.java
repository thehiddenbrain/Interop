package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ObjectNode;

/**
 * I-19: a sandbox whose address was remembered as production by a slip is blocked with the
 * guardrail's own sentences, word for word, and under them a note naming the environment the
 * address was remembered from and the way out; /api/mode's write access says the same, never
 * "writes are not blocked" while a send would be refused.
 */
class RememberedProductionNoteTest {

    private static final String SUBMIT = "http://127.0.0.1:18950/fhir/Claim/$submit";

    @TempDir
    Path directory;

    private static ObjectNode environment(String name, String kind) {
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("name", name);
        body.put("kind", kind);
        if (!"prod".equals(kind)) {
            body.put("acknowledgeNotProd", true);
        }
        body.putObject("addresses").put("PAS_SUBMIT", SUBMIT);
        return body;
    }

    private static int save(MockMvc mvc, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse().getStatus();
    }

    private String note(String state) {
        return "PAS submit goes to " + SUBMIT + ", which this installation remembers as an address of the production environment "
                + "\"Standin Prod Copy\"" + state + ". Writes to it stay blocked whatever environment is active. If it was stored as prod "
                + "by mistake, stop PAVE, delete the entries whose environment is \"Standin Prod Copy\" from productionDestinations in "
                + directory.resolve("store.json") + ", and start PAVE again.";
    }

    private static final String GUARDRAIL_ACTION = "An owner has locked this installation against production writes "
            + "(config-store.allow-prod-writes is false), so no unlock is possible. Only whoever owns the installation can change that. "
            + "To test without production, switch to a sandbox or uat environment.";

    @Test
    void aSandboxBlockedByAnAddressRememberedFromADeletedProductionSaysWhyAndTheWayOut() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        assertThat(save(mvc, environment("Standin Sandbox", "sandbox"))).isEqualTo(200);
        fixture.store.activate("Standin Sandbox");
        WriteAccess before = fixture.gateway.writeAccess(null);
        assertThat(before.writesAllowed()).isTrue();
        assertThat(before.reason()).isEqualTo("\"Standin Sandbox\" is a sandbox environment, so writes are not blocked.");

        assertThat(save(mvc, environment("Standin Prod Copy", "prod"))).isEqualTo(200);
        // Stored: the floor is locked, so no environment that can be made active lets the write go.
        ProdWriteBlockedException stored = catchThrowableOfType(ProdWriteBlockedException.class,
                () -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)));
        assertThat(stored.action()).isEqualTo(GUARDRAIL_ACTION + " " + note(""));

        fixture.store.activate("Standin Prod Copy");
        fixture.store.removeEnvironment("Standin Prod Copy");
        fixture.store.activate("Standin Sandbox");
        ProdWriteBlockedException blocked = catchThrowableOfType(ProdWriteBlockedException.class,
                () -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)));
        // The guardrail's sentences, word for word, and the note under them.
        assertThat(blocked.getMessage()).startsWith("PAS submit is blocked: the address it would go to belongs to the production "
                + "environment \"Standin Prod Copy\", and an owner has locked this installation against production writes.");
        assertThat(blocked.action()).isEqualTo(GUARDRAIL_ACTION + " " + note(", which is no longer stored"));

        WriteAccess after = fixture.gateway.writeAccess(null);
        assertThat(after.production()).isFalse();
        assertThat(after.writesAllowed()).isFalse();
        assertThat(after.floorAllows()).isFalse();
        assertThat(after.reason()).isEqualTo(note(", which is no longer stored"));
    }

    @Test
    void withTheFloorOpenAStoredOwnerKeepsTheGuardrailsWayOutAndADeletedOneGetsTheNote() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).live().allowProdWrites().build();
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        assertThat(save(mvc, environment("Standin Sandbox", "sandbox"))).isEqualTo(200);
        assertThat(save(mvc, environment("Standin Prod Copy", "prod"))).isEqualTo(200);
        fixture.store.activate("Standin Sandbox");
        ProdWriteBlockedException stored = catchThrowableOfType(ProdWriteBlockedException.class,
                () -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)));
        assertThat(stored.action()).endsWith("application.yml or another environment cannot send a write to it.");
        WriteAccess storedAccess = fixture.gateway.writeAccess(null);
        assertThat(storedAccess.writesAllowed()).isFalse();
        assertThat(storedAccess.reason()).startsWith("PAS submit is blocked: the address it would go to belongs to the production "
                + "environment \"Standin Prod Copy\"");

        fixture.store.removeEnvironment("Standin Prod Copy");
        ProdWriteBlockedException removed = catchThrowableOfType(ProdWriteBlockedException.class,
                () -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)));
        assertThat(removed.action()).endsWith("cannot send a write to it. " + note(", which is no longer stored"));
        assertThat(fixture.gateway.writeAccess(null).reason()).isEqualTo(note(", which is no longer stored"));
    }
}
