package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.JacksonConfig;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsService;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.HeaderInput;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSaved;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSettingsInput;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupsView;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.SignInInput;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lookups saved on screen are kept in lookups.json (R-11): they persist, the file is the
 * owner's alone and never holds a non-ASCII character, a damaged file reads as nothing saved with
 * a line that says why and is never written over, and no secret reaches an answer or a log line.
 */
class LookupSettingsStoreTest {

    private static final String KEY = "member-key-placeholder-3131";
    private static final String HEADER_VALUE = "trace-placeholder-3232";

    @TempDir
    Path directory;

    private static JsonMapper mapper() {
        return JacksonConfig.strict(JsonMapper.builder()).build();
    }

    private LookupSettingsStore store() {
        return new LookupSettingsStore(ConfigStoreProperties.of(Map.of("directory", directory.toString())), mapper());
    }

    private LookupSettingsService service(LookupSettingsStore store, OperationGateway gateway) {
        return new LookupSettingsService(store, LookupProperties.defaults(), gateway);
    }

    static LookupSettingsInput post(String apiKey) {
        return new LookupSettingsInput(true, "POST", "https://members.example.invalid/member/search",
                "{\"memberIds\": [\"{memberId}\"], \"on\": \"{dateOfService}\"}", "MM/dd/yyyy", "AS_TYPED",
                new SignInInput("API_KEY", "x-api-key", apiKey, null, null, null, null, null, null, null, null, null),
                List.of(new HeaderInput("x-trace", HEADER_VALUE, null)), "members", Map.of("dateOfBirth", "dob"), 3000, 5000);
    }

    @Test
    void aSavedLookupPersistsInItsOwnOwnerOnlyFileAndIsReadBackByANewStore() throws Exception {
        LookupSaved saved = service(store(), null).save(LookupKind.MEMBER, post(KEY));
        assertThat(saved.lookup().stated()).isTrue();
        assertThat(saved.lookup().inForce().from()).isEqualTo("this screen");
        assertThat(saved.lookup().inForce().words()).isEqualTo("POST, signs in with an API key header");
        assertThat(saved.warnings()).hasSize(2);

        LookupSettingsStore fresh = store();
        LookupSettings read = fresh.read().member();
        assertThat(read.method()).isEqualTo("POST");
        assertThat(read.signIn().apiKey()).isEqualTo(KEY);
        assertThat(read.fieldPaths()).containsEntry("dateOfBirth", "dob");
        assertThat(fresh.read().provider()).isNull();
        Path file = directory.resolve("lookups.json");
        assertThat(fresh.file()).isEqualTo(file.toAbsolutePath().normalize());
        assertThat(Files.readString(file)).doesNotContain("store.json");
        assertThat(directory.resolve("store.json")).doesNotExist();
        if (OwnerOnly.supported()) {
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void nonAsciiIsEscapedSoTheFileSurvivesAnyEditor() throws Exception {
        LookupSettingsInput input = post(KEY);
        input = new LookupSettingsInput(true, "POST", input.address(), "{\"id\": \"{memberId}\", \"note\": \"café\"}", null, null,
                input.signIn(), input.headers(), null, null, null, null);
        service(store(), null).save(LookupKind.MEMBER, input);
        byte[] bytes = Files.readAllBytes(directory.resolve("lookups.json"));
        for (byte b : bytes) {
            assertThat(b & 0xff).isLessThan(0x80);
        }
        assertThat(store().read().member().bodyTemplate()).contains("café");
    }

    @Test
    void aDamagedFileReadsAsNothingSavedWithItsLineAndIsNeverWrittenOver() throws Exception {
        Path file = directory.resolve("lookups.json");
        Files.writeString(file, "{\"version\": 1, \"member\": {\"method\": ", StandardCharsets.UTF_8);
        LookupSettingsService service = service(store(), null);
        LookupsView views = service.views();
        assertThat(views.member().stated()).isFalse();
        assertThat(views.member().problem()).startsWith("The file " + file.toAbsolutePath().normalize() + " could not be read, so no lookup "
                + "saved on screen is used and application.yml's lookups apply.");
        assertThat(views.member().inForce().from()).isEqualTo("not set");
        assertThat(service.saved(LookupKind.MEMBER)).isNull();
        assertThatThrownBy(() -> service.save(LookupKind.MEMBER, post(KEY))).isInstanceOf(ConfigStoreException.class)
                .hasMessageEndingWith("Nothing was changed.");
        assertThat(Files.readString(file)).isEqualTo("{\"version\": 1, \"member\": {\"method\": ");
    }

    @Test
    void aStoredLookupThatBreaksARuleIsNotUsedAndSaysSo() throws Exception {
        Files.writeString(directory.resolve("lookups.json"), "{\"version\": 1, \"member\": {\"enabled\": true, \"method\": \"GET\", "
                + "\"address\": \"https://members.example.invalid/no-token\"}}", StandardCharsets.UTF_8);
        LookupSettingsService service = service(store(), null);
        assertThat(service.saved(LookupKind.MEMBER)).isNull();
        assertThat(service.views().member().problem()).contains("breaks a rule this page keeps");
    }

    @Test
    void noSecretIsInAnAnswerOrALogLineAndEveryOneIsHeld() throws Exception {
        Map<String, String> system = new HashMap<>();
        system.put("PAVE_MEMBER_KEY", KEY);
        ConfigFixture fixture = ConfigFixture.on(directory.resolve("cfg")).operatingSystem(system).build();
        fixture.activeEnvironmentOf("Lookup Sandbox", EnvironmentKind.SANDBOX, "payerName", "Placeholder Payer");
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        try {
            LookupSettingsService service = service(store(), fixture.gateway);
            LookupSaved saved = service.save(LookupKind.MEMBER, post("{{env:PAVE_MEMBER_KEY}}"));
            String answer = mapper().writeValueAsString(saved) + mapper().writeValueAsString(service.views());
            assertThat(answer).doesNotContain(KEY).doesNotContain(HEADER_VALUE).contains("{{env:PAVE_MEMBER_KEY}}");
            assertThat(saved.warnings()).hasSize(1);
            assertThat(saved.lookup().headers().get(0).value().set()).isTrue();
            assertThat(saved.lookup().headers().get(0).value().reference()).isNull();
            assertThat(saved.toString() + service.saved(LookupKind.MEMBER)).doesNotContain(HEADER_VALUE).doesNotContain("PAVE_MEMBER_KEY");

            SentSecrets held = service.held();
            String masked = held.mask("echo " + KEY + " and " + HEADER_VALUE).text();
            assertThat(masked).doesNotContain(KEY).doesNotContain(HEADER_VALUE);

            String logged = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(logged).contains("The member lookup was saved on screen").doesNotContain(KEY).doesNotContain(HEADER_VALUE);
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
    }

    @Test
    void removingTheSavedLookupBringsApplicationYmlBackAndKeepsTheOther() {
        LookupSettingsService service = service(store(), null);
        service.save(LookupKind.MEMBER, post(KEY));
        service.save(LookupKind.PROVIDER, new LookupSettingsInput(true, "GET", "https://providers.example.invalid/npi/{id}", null, null,
                null, null, null, null, null, null, null));
        LookupSaved removed = service.remove(LookupKind.MEMBER);
        assertThat(removed.lookup().stated()).isFalse();
        assertThat(service.saved(LookupKind.MEMBER)).isNull();
        assertThat(service.saved(LookupKind.PROVIDER)).isNotNull();
        assertThat(service.views().provider().inForce().words()).isEqualTo("GET, sends no sign-in");
    }
}
