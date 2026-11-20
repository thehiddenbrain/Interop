package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A record's default toString prints every component. For a type that holds member data or wire
 * JSON that would put it in a log the first time somebody logged the object, so those types
 * override toString. These tests fail if an override is removed.
 */
class RedactionTest {

    private static final String SURNAME = "Placeholder-Surname";
    private static final String MEMBER_ID = "S123456789";
    private static final String BODY = "{\"name\":\"" + SURNAME + "\"}";

    @Test
    void memberTypesDoNotPrintMemberData() {
        PostalAddress address = new PostalAddress("1 Placeholder Street", null, "Sampletown", "MA", "00000");
        MemberDetail member = MemberDetail.builder().memberId(MEMBER_ID).firstName("Given").lastName(SURNAME)
                .dateOfBirth("1970-01-01").address(address).subscriberId("SUB-1").build();
        assertThat(member.toString()).doesNotContain(SURNAME).doesNotContain(MEMBER_ID).doesNotContain("1970").doesNotContain("SUB-1");
        assertThat(address.toString()).doesNotContain("Placeholder Street").doesNotContain("Sampletown");
        assertThat(new MemberIdForms(MEMBER_ID, MEMBER_ID, false).toString()).doesNotContain(MEMBER_ID);
        MemberLookupResult result = new MemberLookupResult(LookupStatus.FOUND, MemberIds.forms(MEMBER_ID), member, List.of(),
                List.of(), List.of(), null, null, "found");
        assertThat(result.toString()).doesNotContain(SURNAME).doesNotContain(MEMBER_ID);
        assertThat(CaseSpec.builder().memberId(MEMBER_ID).caseId("c1").build().toString()).doesNotContain(MEMBER_ID);
    }

    @Test
    void wireTypesDoNotPrintBodies() {
        WireRequest request = new WireRequest("POST", "https://example.invalid/x", "{{base}}/x", List.of(), BODY, true, null);
        WireResponse response = new WireResponse(200, List.of(), BODY, 10L, true, null);
        WireView wire = new WireView(request, response, MemberIds.forms(MEMBER_ID));
        VendorExchange exchange = new VendorExchange(request, 200, List.of(), BODY, null, JsonSafe.parse(BODY).node(), null, 5,
                OnyxFailureType.NONE, null, Set.of(), null, null, List.of());
        for (Object value : new Object[] {request, response, wire, exchange}) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain(SURNAME).doesNotContain(MEMBER_ID);
        }
    }

    @Test
    void resolvedTypesPrintOnlyTheMaskedForm() {
        ResolvedText text = new ResolvedText("{{clientSecret}}", "[secret clientSecret]", "hunter2-value",
                List.of(new VariableUse("clientSecret", VariableSource.ENVIRONMENT, true, "[secret clientSecret]")), List.of());
        ResolvedUrl url = new ResolvedUrl("{{base}}/x?key={{apiKey}}", "https://example.invalid/x?key=[secret apiKey]",
                "https://example.invalid/x?key=hunter2-value", List.of(), List.of(), "test");
        ResolvedHeader header = new ResolvedHeader("X-Api-Key", "[secret apiKey]", "hunter2-value");
        for (Object value : new Object[] {text, url, header}) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain("hunter2-value");
            assertThat(JsonSafe.mapper().writeValueAsString(value)).as(value.getClass().getSimpleName() + " as JSON")
                    .doesNotContain("hunter2-value");
        }
    }

    @Test
    void aLookupUrlThatHoldsTheMemberIdIsNotPrinted() {
        // The wire view has to show the url that was sent, member ID included. Nothing else may.
        ResolvedUrl url = new ResolvedUrl("https://example.invalid/members/{id}", "https://example.invalid/members/" + MEMBER_ID,
                "https://example.invalid/members/" + MEMBER_ID, List.of(), List.of(), "lookup.member.url");
        assertThat(url.toString()).doesNotContain(MEMBER_ID).contains("{id}");
        org.p32h.interop.epa.pave.service.config.PreparedCall call =
                org.p32h.interop.epa.pave.service.config.TestPreparedCalls.with(EpaOperation.MEMBER_LOOKUP, "GET", url,
                        List.of(new ResolvedHeader("X-Api-Key", "[secret memberApiKey]", "hunter2-value")), EnvironmentRef.none(), null, false);
        assertThat(call.toString()).doesNotContain(MEMBER_ID).doesNotContain("hunter2-value");
        org.p32h.interop.epa.pave.service.config.CallIntent intent =
                org.p32h.interop.epa.pave.service.config.CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", MEMBER_ID);
        assertThat(intent.toString()).doesNotContain(MEMBER_ID);
        assertThat(intent.urlValues()).containsEntry("id", MEMBER_ID);
    }

    @Test
    void viewValuesAreNotPrinted() {
        // Every view type is built from Shown, so redacting it there covers all of them.
        assertThat(Shown.of(SURNAME).toString()).isEqualTo("Shown[VALUE]");
        assertThat(new LookupField("lastName", "Last name", Shown.of(SURNAME), "name.family").toString()).doesNotContain(SURNAME);
        assertThat(new AdditionalField("member.nickname", Presence.VALUE, "\"" + SURNAME + "\"").toString()).doesNotContain(SURNAME);
        assertThat(new VariableUse("memberId", VariableSource.RUN_VALUE, false, MEMBER_ID).toString()).doesNotContain(MEMBER_ID);
        assertThat(new IssueView(Shown.of("error"), Shown.of("not-found"), Shown.of(SURNAME + " not found"), Shown.absent(),
                List.of(), List.of(), List.of()).toString()).doesNotContain(SURNAME);
        assertThat(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001").toString()).doesNotContain("PA-PLACEHOLDER-0001");
    }

    @Test
    void anEnvelopeDoesNotPrintItsDetailOrWire() {
        RunRecord record = RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.NO_AUTH_REQUIRED).result(TestResult.UNVERIFIED).build();
        RunEnvelope<String> envelope = RunEnvelope.<String>builder().runId("run-1").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN)
                .verdict(VerdictView.of(CrdOutcome.NO_AUTH_REQUIRED, "Prior authorization is not required.", List.of()))
                .expectation(Expectations.assess(null, CrdOutcome.NO_AUTH_REQUIRED, null, null, CrdOutcome.values(), List.of()))
                .nextStep(NextStepView.of(NextAction.FLOW_ENDS, "The flow ends here.", "No authorization is needed."))
                .ledger(new LedgerReceipt(true, record, null, 1)).detail(BODY).build();
        assertThat(envelope.toString()).doesNotContain(SURNAME);
    }

    @Test
    void aLedgerDraftDoesNotPrintTheMemberIdOrAnAuthorizationNumber() {
        // The draft a service hands the ledger holds the real member ID, and a receipt and a case
        // outcome print the record they hold, so one log statement on any of them would leak it.
        RunRecord draft = RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED").result(TestResult.UNVERIFIED).memberId(MEMBER_ID)
                .dateOfService("2026-02-03").npi("1234567893")
                .correlation(java.util.Map.of(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001")).build();
        assertThat(draft.toString()).isEqualTo("RunRecord[run-1, PAS, PAS_SUBMIT, APPROVED]");
        assertThat(new LedgerReceipt(true, draft, null, 1).toString()).doesNotContain(MEMBER_ID).doesNotContain("PA-PLACEHOLDER-0001")
                .doesNotContain("2026-02-03");
    }

    @Test
    void aVerdictAndADeterminationRowDoNotPrintAnAuthorizationNumber() {
        // The ledger tokenises an authorization number. A default toString would print it.
        VerdictView verdict = VerdictView.of(CrdOutcome.AUTH_SATISFIED, "Prior authorization is already satisfied.",
                List.of("Existing authorization: PA-PLACEHOLDER-0001."));
        assertThat(verdict.toString()).isEqualTo("VerdictView[AUTH_SATISFIED, CLEAR]");
        DeterminationRow row = new DeterminationRow(null, "Existing authorization", "PA-PLACEHOLDER-0001", null, "satisfied-pa-id", true,
                Presence.VALUE);
        assertThat(row.toString()).isEqualTo("DeterminationRow[Existing authorization, VALUE]");
    }

    /**
     * Records whose components hold text and whose default toString is kept on purpose, each with
     * why its text can be neither member data, a cell of a suite file nor text from a response.
     * A record that is not here and keeps the default fails {@link #everyRecordThatCanHoldTextRedactsItsToString}.
     */
    private static final Map<String, String> PRINTS_ONLY_ITS_OWN_TEXT = Map.ofEntries(
            Map.entry("org.p32h.interop.epa.pave.model.CodeInput", "a procedure code, its system and its description as chosen from the "
                    + "code list"),
            // Kept because a frozen test reads its messages through toString. Its messages are the
            // tool's own; the one that repeats typed text is an expectation term the tester wrote.
            Map.entry("org.p32h.interop.epa.pave.model.FieldError", "a field name and the tool's message about it"),
            Map.entry("org.p32h.interop.epa.pave.model.EnvironmentRef", "the name an operator gave an uploaded environment, and its kind"),
            Map.entry("org.p32h.interop.epa.pave.model.LedgerHealth", "a folder, a file name and the ledger's own messages and times"),
            Map.entry("org.p32h.interop.epa.pave.model.LedgerReceipt", "the ledger's own message; the record prints what identifies the run"),
            Map.entry("org.p32h.interop.epa.pave.model.OutcomeOption", "an outcome code of this tool and its label"),
            Map.entry("org.p32h.interop.epa.pave.model.NextActionOption", "a next action of this tool and its label"),
            Map.entry("org.p32h.interop.epa.pave.model.FindingKindOption", "a finding kind of this tool and the words of its group"),
            Map.entry("org.p32h.interop.epa.pave.model.suite.SuiteCountGroup$Labelled", "a count group of this tool, its label and a count"),
            Map.entry("org.p32h.interop.epa.pave.model.ScenarioList", "the mock scenario files, synthetic by construction, and the tool's "
                    + "problems with them"),
            Map.entry("org.p32h.interop.epa.pave.model.ScenarioSummary", "one mock scenario file, synthetic by construction"),
            Map.entry("org.p32h.interop.epa.pave.model.chain.ChainStage", "a stage name and a state line the tool writes; its runs print "
                    + "what identifies them"),
            Map.entry("org.p32h.interop.epa.pave.model.crd.ActionView", "where the action sat, a path the tool writes; every value is "
                    + "shown through Shown or an additional field, both redacted"),
            Map.entry("org.p32h.interop.epa.pave.model.crd.CrdDetail", "the tool's notes on the response and the request; every view in "
                    + "it redacts"),
            Map.entry("org.p32h.interop.epa.pave.model.suite.SuitePreview$Kind", "what a kind of row runs, in the tool's words, and a count"),
            Map.entry("org.p32h.interop.epa.pave.model.suite.SuiteTiming$Bucket", "a label the tool writes for a range of times"),
            Map.entry("org.p32h.interop.epa.pave.service.suite.SuiteCases$Target", "what a row runs, in the tool's words"),
            Map.entry("org.p32h.interop.epa.pave.service.ledger.DayFiles$DayFile", "a ledger file's path and its day"),
            Map.entry("org.p32h.interop.epa.pave.service.ledger.JsonlRunLedger$Appended", "the ledger's own failure message and a count"),
            Map.entry("org.p32h.interop.epa.pave.service.ledger.JsonlRunLedger$DayCount", "a ledger file's name, size and count"),
            Map.entry("org.p32h.interop.epa.pave.service.ledger.ReplayReport", "the replay's own notes; every entry prints its run and "
                    + "status only")
    );

    /** The packages whose records are checked: the model, and the two that hold suite files and ledger lines. */
    private static final List<String> PACKAGES = List.of("org.p32h.interop.epa.pave.model", "org.p32h.interop.epa.pave.service.suite",
            "org.p32h.interop.epa.pave.service.ledger");

    @Test
    void everyRecordThatCanHoldTextRedactsItsToString() throws Exception {
        // Found by the member data audit (m7): this test listed its types by hand, and several
        // records that hold suite cells or response text printed everything.
        List<Class<?>> records = recordsIn(PACKAGES);
        assertThat(records).as("records found").hasSizeGreaterThan(100);
        List<String> printing = new java.util.ArrayList<>();
        Map<Class<?>, Boolean> seen = new java.util.HashMap<>();
        for (Class<?> type : records) {
            if (!PRINTS_ONLY_ITS_OWN_TEXT.containsKey(type.getName()) && printsText(type, seen)) {
                printing.add(type.getName());
            }
        }
        assertThat(printing).as("records whose toString prints text, member data or response text included").isEmpty();
        // Each exception names a record that exists and still keeps the default, so the list cannot go stale.
        for (String name : PRINTS_ONLY_ITS_OWN_TEXT.keySet()) {
            Class<?> type = Class.forName(name, false, RedactionTest.class.getClassLoader());
            assertThat(keepsTheDefaultToString(type)).as(name).isTrue();
        }
        // The check itself: a record known to print everything is caught, and one known to redact is not.
        assertThat(printsText(ExampleThatPrints.class, new java.util.HashMap<>())).isTrue();
        assertThat(printsText(MemberDetail.class, new java.util.HashMap<>())).isFalse();
        assertThat(new ExampleThatPrints("S123456789").toString()).contains("S123456789");
    }

    @Test
    void theRecordsTheAuditPrintedNowPrintNoneOfWhatTheyHold() {
        String cell = "Zzlastname-Placeholder 1961-07-23";
        assertThat(new org.p32h.interop.epa.pave.service.suite.CsvRows.Table(List.of(MEMBER_ID, "crd", "note"),
                List.of(List.of(MEMBER_ID, "crd", cell)), List.of()).toString()).doesNotContain(MEMBER_ID).doesNotContain(cell);
        assertThat(new org.p32h.interop.epa.pave.service.suite.SuiteTable("CSV", List.of(MEMBER_ID, "crd", cell), List.of(), List.of())
                .toString()).doesNotContain(MEMBER_ID).doesNotContain(cell);
        assertThat(new Finding(FindingKind.values()[0], "title", "The service returned pa-needed=" + cell, null, null, null).toString())
                .doesNotContain(cell);
        ProviderDetail provider = ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Zzprovider").phone("617-555-0100")
                .build();
        assertThat(new ProviderLookupResult(LookupStatus.FOUND, "1234567893", provider, List.of("The network status " + cell),
                List.of(), List.of(), null, null, "found").toString()).doesNotContain("Zzprovider").doesNotContain(cell)
                .doesNotContain("617-555-0100");
    }

    /** A record that keeps the generated toString, to show that the check finds one. */
    private record ExampleThatPrints(String memberId) {
    }

    /**
     * True when a record's toString is the one the compiler generates. That one is a call to the
     * runtime's record helper, and the compiled class names it by the type it takes: the record
     * itself, returning a String. A toString the record writes has no such entry. A record that
     * also declares a static method taking itself and returning a String would be reported too,
     * which errs toward the safe side.
     */
    private static boolean keepsTheDefaultToString(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (java.io.InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            String constants = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            return constants.contains("(L" + type.getName().replace('.', '/') + ";)Ljava/lang/String;");
        }
    }

    /**
     * True when printing a record can print text: it keeps the generated toString and one of its
     * components is text, or a list, a map or a record that can print text. A value that is shown
     * through {@link Shown}, or any type with a toString of its own, prints what that type allows.
     */
    private static boolean printsText(Class<?> type, Map<Class<?>, Boolean> seen) throws IOException {
        Boolean known = seen.get(type);
        if (known != null) {
            return known;
        }
        if (PRINTS_ONLY_ITS_OWN_TEXT.containsKey(type.getName())) {
            return false;
        }
        // Assumed not while it is being looked at, so a record that holds itself does not loop.
        seen.put(type, false);
        boolean prints = false;
        if (keepsTheDefaultToString(type)) {
            for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
                if (printsText(component.getGenericType(), seen)) {
                    prints = true;
                    break;
                }
            }
        }
        seen.put(type, prints);
        return prints;
    }

    private static boolean printsText(java.lang.reflect.Type type, Map<Class<?>, Boolean> seen) throws IOException {
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                if (printsText(argument, seen)) {
                    return true;
                }
            }
            return printsText(parameterized.getRawType(), seen);
        }
        if (!(type instanceof Class<?> kind)) {
            // A type variable or a wildcard: it can be anything, text included.
            return true;
        }
        if (kind.isPrimitive() || Number.class.isAssignableFrom(kind) || kind == Boolean.class || kind.isEnum()
                || java.time.temporal.Temporal.class.isAssignableFrom(kind) || kind == Shown.class) {
            return false;
        }
        if (kind.isRecord()) {
            return printsText(kind, seen);
        }
        if (java.util.Collection.class.isAssignableFrom(kind) || Map.class.isAssignableFrom(kind)) {
            // What a collection prints is decided by what it holds, which the type arguments said.
            return false;
        }
        // Text, a JSON tree, or any other type: assumed to print what it holds.
        return true;
    }

    private static List<Class<?>> recordsIn(List<String> packages) throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(RunRecord.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> out = new java.util.ArrayList<>();
        for (String name : packages) {
            java.nio.file.Path folder = root.resolve(name.replace('.', '/'));
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(folder)) {
                for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    String className = root.relativize(file).toString().replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "");
                    Class<?> type = Class.forName(className, false, RedactionTest.class.getClassLoader());
                    if (type.isRecord()) {
                        out.add(type);
                    }
                }
            }
        }
        return out;
    }

    @Test
    void aVendorNameMadeOnlyOfPunctuationIsNoVendor() {
        // A dash in a list cell means "none". It must not compare equal to another piece of punctuation.
        assertThat(DelegatedVendors.same("-", "/")).isFalse();
        assertThat(DelegatedVendors.same("-", "-")).isFalse();
        assertThat(DelegatedVendors.canonical("-")).isNull();
        assertThat(DelegatedVendors.canonical(" n/a ")).isEqualTo("n/a");
        assertThat(DelegatedVendors.same("EVICORE", "evi-core")).isTrue();
        assertThat(DelegatedVendors.known("Optum PostAcute")).isTrue();
        assertThat(DelegatedVendors.known("-")).isFalse();
    }

    @Test
    void aNextStepPrintsTheNamesOfWhatItCarriesAndNeverTheValues() {
        NextStepView step = new NextStepView(NextAction.START_PAS, "Next: submit.", "Because.", java.util.Map.of(
                NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of("{\"answer\":\"" + SURNAME + "\"}"),
                NextStepView.CARRY_CLAIM_ID, List.of("PA-PLACEHOLDER-0001")));
        assertThat(step.toString()).doesNotContain(SURNAME).doesNotContain("PA-PLACEHOLDER-0001")
                .contains(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE).contains("START_PAS");
        assertThat(step.first(NextStepView.CARRY_CLAIM_ID)).isEqualTo("PA-PLACEHOLDER-0001");
        assertThat(step.first(NextStepView.CARRY_ORDER)).isNull();
    }
}
