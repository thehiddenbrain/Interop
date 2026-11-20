package org.p32h.interop.epa.pave.service.crd;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.model.compare.CaseValue;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;
import org.p32h.interop.epa.pave.model.compare.GroupCount;
import org.p32h.interop.epa.pave.model.compare.NotFound;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.service.compare.JsonComparer;
import org.p32h.interop.epa.pave.service.compare.Nesting;
import org.p32h.interop.epa.pave.service.compare.ReferenceText;
import org.p32h.interop.epa.pave.service.compare.ResourceIndex;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.CaseField;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.Found;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.Missing;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.Reading;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Compares a CRD request a tester pasted with the one this tool builds for the same case.
 *
 * <p>The tester holds a sample request from the vendor and wants to know where this tool's own
 * request differs from it. Two requests for two different members would differ everywhere and
 * say nothing, so the tool builds its request for the case on the form, and where the form is
 * silent for the case read out of the pasted request. What is then left is how each request is
 * built, which is the question.
 *
 * <p>Nothing is sent and nothing is recorded. Nothing here says which of the two requests a
 * service expects, because this tool does not know: it shows where they differ and leaves the
 * verdict to the vendor's sample and to sending both.
 */
public final class CrdRequestComparer {

    /** What stands in for the id of the hook call. One is generated only when a request is sent. */
    static final String HOOK_INSTANCE = "(a new id is generated when the request is sent)";

    /** The largest pasted text that is compared. A real request is a few kilobytes. */
    static final int MAX_BYTES = 2 * 1024 * 1024;

    /** Said once on every comparison, so the list of differences is never read as a list of faults. */
    static final String WHAT_IT_CANNOT_SHOW = "This comparison shows where the two requests differ. It cannot show which one the service "
            + "expects: the vendor's sample is the better evidence of that, and sending each and reading the answers settles it.";

    private static final Pattern POSITION = Pattern.compile("\\(line \\d+, column \\d+\\)");

    private final CrdRequestBuilder builder;
    private final CrdRequestGlossary glossary = new CrdRequestGlossary();

    public CrdRequestComparer(CrdRequestBuilder builder) {
        this.builder = builder;
    }

    /** True when the pasted text is larger than this tool compares. */
    static boolean tooLarge(String text) {
        // A character is at least one byte, so the length alone settles most cases without encoding the text.
        return text != null && (text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES);
    }

    /**
     * @param form      the form as posted, with the pasted request in {@code rawBody}
     * @param variables fills in placeholders, in the pasted text and for the builder alike
     */
    public RequestComparison compare(CrdRunRequest form, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
        String yourText = fillIn(form.rawBody(), variables, notes);
        JsonSafe.Parsed parsed = JsonSafe.parse(yourText);
        JsonNode yours = parsed.ok() ? parsed.node() : null;
        String whyNot = null;
        if (yours == null) {
            Matcher position = POSITION.matcher(Texts.orElse(parsed.error(), ""));
            boolean hasPosition = position.find();
            Nesting.TooDeep deep = hasPosition ? null : Nesting.tooDeep(yourText);
            if (deep != null) {
                // Valid JSON can still be deeper than the parser reads, and the parser does not say where it stopped.
                whyNot = "The two cannot be compared field by field, because your request nests deeper than this tool reads (more than "
                        + deep.limit() + " levels), first at line " + deep.line() + ", column " + deep.column() + ".";
            } else {
                whyNot = "The two cannot be compared field by field, because your request could not be read as JSON"
                        + (hasPosition ? " " + position.group() : "") + ".";
            }
        } else if (!yours.isObject()) {
            whyNot = "The two cannot be compared field by field, because your request is JSON but not a JSON object: it is " + kind(yours) + ".";
        } else if (yours.isEmpty()) {
            whyNot = "The two cannot be compared field by field, because your request is an empty JSON object.";
        }
        if (yours != null && parsed.repeatedKeys()) {
            notes.add("Your request has a key that appears more than once in one object. Only the last value of such a key is shown "
                    + "and was compared.");
        }

        boolean readable = yours != null && yours.isObject() && !yours.isEmpty();
        Reading reading = CrdRequestReader.read(readable ? yours : JsonNodeFactory.instance.objectNode());
        Case built = merge(form, reading);
        if (!readable) {
            // Nothing was looked for in a text that is not a request, so nothing is reported as looked for and not found.
            built.notFound.clear();
        }
        notes.addAll(built.notes);
        // What the reader could not use is said whether or not the two end up compared: it is often the reason they are not.
        notes.addAll(reading.notes());

        String toolBody = null;
        JsonNode tool = null;
        if (built.lacking.isEmpty()) {
            toolBody = builder.build(built.request, HOOK_INSTANCE, variables).json();
            tool = JsonSafe.parse(toolBody).node();
        } else {
            String lacking = "the form has " + list(built.lacking) + ", and " + (built.lacking.size() == 1 ? "it" : "they")
                    + " could not be read from your request";
            if (whyNot == null) {
                whyNot = "This tool could not build its own request to compare with yours: " + lacking + ".";
            } else {
                notes.add("The tool's own request is not shown: " + lacking + ".");
            }
        }

        // Compared on the values as typed; only what is shown of a credential is masked.
        ComparisonMasks masks = new ComparisonMasks();
        RequestComparison.Builder answer = RequestComparison.builder()
                .caveat(WHAT_IT_CANNOT_SHOW)
                .hook(tool == null ? null : built.request.hook())
                .toolBody(masks.body(toolBody))
                .yourBody(masks.body(yours == null ? yourText : JsonSafe.pretty(yours)))
                .caseSource(built.sources)
                .notFound(built.notFound);
        if (whyNot != null) {
            return answer.comparable(false).whyNot(whyNot).headline("The two requests were not compared field by field.").notes(notes)
                    .maskedNote(masks.note()).build();
        }

        JsonComparer.Result result = new JsonComparer(CrdRequestGlossary.RULES, glossary).compare(tool, yours, builtFrom(tool, built, reading));
        Map<DifferenceGroup, Integer> counted = new EnumMap<>(DifferenceGroup.class);
        for (Difference difference : result.differences()) {
            counted.merge(difference.group(), 1, Integer::sum);
        }
        List<String> all = new ArrayList<>();
        all.add(WHAT_IT_CANNOT_SHOW);
        all.addAll(notes);
        all.addAll(result.notes());
        systemsLeftToThisTool(built, reading, variables, all);
        otherOrderTypes(reading, all);
        pointingOutside(yours, "Your request", all);
        pointingOutside(tool, "The tool's request", all);
        if (counted.getOrDefault(DifferenceGroup.LABELS, 0) > 0 && Texts.present(JsonSafe.text(yours, "fhirServer"))) {
            all.add("Your request names a fhirServer. An id in it may also be used to fetch a resource from that server, so a renamed id can "
                    + "matter there.");
        }
        List<GroupCount> counts = new ArrayList<>();
        for (DifferenceGroup group : DifferenceGroup.values()) {
            counts.add(GroupCount.of(group, counted.getOrDefault(group, 0)));
        }
        List<Difference> differences = result.differences().stream().map(masks::difference).toList();
        return answer.comparable(true).headline(headline(result.differences().size(), counted)).differences(differences)
                .counts(counts).notes(all).maskedNote(masks.note()).build();
    }

    // ---- the pasted text ----

    /**
     * The pasted text with its placeholders filled in. When any placeholder has no value the
     * text is compared exactly as it was pasted: a text with only some of its placeholders filled
     * in is something nobody wrote and nothing would send.
     */
    private static String fillIn(String pasted, Function<String, ResolvedText> variables, List<String> notes) {
        ResolvedText resolved = variables == null ? null : variables.apply(pasted);
        if (resolved == null) {
            return pasted;
        }
        if (!resolved.resolved() || resolved.display() == null) {
            if (!resolved.unresolved().isEmpty()) {
                List<String> names = new ArrayList<>(new LinkedHashSet<>(resolved.unresolved()));
                notes.add("Your request has " + (names.size() == 1 ? "a placeholder" : "placeholders") + " with no value: "
                        + String.join(", ", names.stream().map(name -> "{{" + name + "}}").toList()) + ". It was compared as pasted, with "
                        + "every placeholder left in place. A value is looked for among the values typed for this run, in the active "
                        + "environment and in application.yml, and not in the variables of a collection.");
            }
            return pasted;
        }
        Set<String> secrets = new LinkedHashSet<>();
        for (VariableUse use : resolved.variables()) {
            if (use.secret()) {
                secrets.add(use.name());
            }
        }
        if (!secrets.isEmpty()) {
            notes.add("Your request takes a value from " + (secrets.size() == 1 ? "a secret variable" : "secret variables") + ": "
                    + String.join(", ", secrets) + ". A secret is shown and compared as its mask, never as its value.");
        }
        // The display form, in which a secret is masked. The comparison is shown on screen, and a secret never is.
        return resolved.display();
    }

    private static String kind(JsonNode node) {
        if (node.isArray()) {
            return "a list";
        }
        if (node.isString()) {
            return "a text";
        }
        if (node.isNumber()) {
            return "a number";
        }
        return node.isNull() ? "a null" : "true or false";
    }

    // ---- the case ----

    /** The case the tool's request is built for, and where each of its values came from. */
    static final class Case {
        final List<CaseValue> sources = new ArrayList<>();
        final List<NotFound> notFound = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        /** What a request cannot be built without and nobody supplied, in words. */
        final List<String> lacking = new ArrayList<>();
        /** The request to build from. It is still set when something is lacking, so a test can see what was merged. */
        CrdRunRequest request;
        private final Reading reading;

        private Case(Reading reading) {
            this.reading = reading;
        }

        /**
         * One value of the case: the form's when the form has one, otherwise the one read from the
         * pasted request, otherwise nothing. Nothing is ever put in the place of a missing value.
         *
         * @param refused why a value in the pasted request is not to be used, or null when it may be
         */
        private String value(CaseField field, String onForm, String refused) {
            if (Texts.present(onForm)) {
                sources.add(new CaseValue(field.key(), field.label(), CaseValue.Source.FORM, null, null, Shown.of(onForm.strip()), null));
                return onForm;
            }
            Found read = reading.found(field);
            if (read != null && refused == null) {
                sources.add(new CaseValue(field.key(), field.label(), CaseValue.Source.YOUR_REQUEST, read.path(), read.pointer(),
                        Shown.of(read.value()), read.note()));
                return read.value();
            }
            Missing missing = reading.missing().get(field);
            if (read != null) {
                notFound.add(new NotFound(field.key(), field.label(), read.path(), refused, List.of()));
            } else {
                notFound.add(new NotFound(field.key(), field.label(), missing == null ? null : missing.lookedIn(),
                        missing == null ? null : missing.note(), missing == null ? List.of() : missing.candidates().stream()
                        .map(candidate -> new NotFound.Candidate(candidate.path(), candidate.pointer(), Shown.of(candidate.value()))).toList()));
            }
            return null;
        }

        /** Records one value that was read from the pasted request and used. */
        private void read(String field, String label, Found value) {
            sources.add(new CaseValue(field, label, CaseValue.Source.YOUR_REQUEST, value.path(), value.pointer(), Shown.of(value.value()), value.note()));
        }

        private boolean fromRequest(CaseField field) {
            return sources.stream().anyMatch(source -> source.field().equals(field.key()) && source.source() == CaseValue.Source.YOUR_REQUEST);
        }

        private void drop(CaseField field) {
            notFound.removeIf(entry -> entry.field().equals(field.key()));
        }

        private void annotate(CaseField field, String note) {
            for (int i = 0; i < sources.size(); i++) {
                CaseValue source = sources.get(i);
                if (source.field().equals(field.key())) {
                    sources.set(i, new CaseValue(source.field(), source.label(), source.source(), source.path(), source.pointer(), source.value(),
                            source.note() == null ? note : source.note() + " " + note));
                }
            }
        }
    }

    /**
     * Puts the form and the pasted request together into one case.
     *
     * <p>The details of a member are taken from the pasted request only when it does not name
     * another member than the form does. A name and a date of birth read for one member ID and
     * sent with another would describe nobody. The same holds for a provider and an NPI.
     */
    static Case merge(CrdRunRequest form, Reading reading) {
        Case out = new Case(reading);
        String hook = hook(form, reading, out);

        MemberDetail formMember = form.member();
        Found readMemberId = reading.found(CaseField.MEMBER_ID);
        String formMemberId = formMember == null ? null : formMember.memberId();
        boolean anotherMember = Texts.present(formMemberId) && readMemberId != null
                && !MemberIds.noSpace(formMemberId).equals(MemberIds.noSpace(readMemberId.value()));
        String notThisMember = anotherMember ? "Your request holds this for another member ID than the one on the form, so it was not used." : null;
        if (anotherMember) {
            out.notes.add("The member ID on the form and the member ID in your request are not the same. The tool's request was built for "
                    + "the member on the form, and the member details in your request were not used for it.");
        }
        String memberId = out.value(CaseField.MEMBER_ID, formMemberId, null);
        if (memberId != null && MemberIds.forms(memberId).changed()) {
            out.annotate(CaseField.MEMBER_ID, "This tool removes the spaces from a member ID before it sends it.");
        }
        String firstName = out.value(CaseField.MEMBER_FIRST_NAME, formMember == null ? null : formMember.firstName(), notThisMember);
        String lastName = out.value(CaseField.MEMBER_LAST_NAME, formMember == null ? null : formMember.lastName(), notThisMember);
        String dateOfBirth = out.value(CaseField.MEMBER_DATE_OF_BIRTH, formMember == null ? null : formMember.dateOfBirth(), notThisMember);
        String gender = out.value(CaseField.MEMBER_GENDER, formMember == null ? null : formMember.gender(), notThisMember);
        String subscriberId = out.value(CaseField.SUBSCRIBER_ID, formMember == null ? null : formMember.subscriberId(), notThisMember);
        String planName = out.value(CaseField.PLAN_NAME, formMember == null ? null : formMember.planName(), notThisMember);
        String coverageStatus = out.value(CaseField.COVERAGE_STATUS, formMember == null ? null : formMember.coverageStatus(), notThisMember);
        String lineOfBusiness = out.value(CaseField.LINE_OF_BUSINESS, formMember == null ? null : formMember.lineOfBusiness(), null);
        if (out.fromRequest(CaseField.MEMBER_DATE_OF_BIRTH) && !IsoDates.valid(dateOfBirth)) {
            out.notes.add("The date of birth read from your request is not a full date written as YYYY-MM-DD. A run from the form refuses "
                    + "such a date. It was used here as it is written, so that both requests describe the same member.");
        }
        MemberDetail member = null;
        if (formMember != null || anyPresent(memberId, firstName, lastName, dateOfBirth, gender, subscriberId, planName, coverageStatus, lineOfBusiness)) {
            member = (formMember == null ? MemberDetail.builder() : formMember.toBuilder()).memberId(memberId).firstName(firstName)
                    .lastName(lastName).dateOfBirth(dateOfBirth).gender(gender).subscriberId(subscriberId).planName(planName)
                    .coverageStatus(coverageStatus).lineOfBusiness(lineOfBusiness).build();
        }
        if (memberId == null) {
            out.lacking.add("no member ID");
        }

        ProviderDetail formProvider = form.provider();
        Found readNpi = reading.found(CaseField.NPI);
        String formNpi = formProvider == null ? null : formProvider.npi();
        boolean anotherProvider = Texts.present(formNpi) && readNpi != null && !formNpi.strip().equals(readNpi.value().strip());
        String notThisProvider = anotherProvider ? "Your request holds this for another NPI than the one on the form, so it was not used." : null;
        if (anotherProvider) {
            out.notes.add("The NPI on the form and the NPI in your request are not the same. The tool's request was built for the provider "
                    + "on the form, and the provider's name in your request was not used for it.");
        }
        String npi = out.value(CaseField.NPI, formNpi, null);
        if (out.fromRequest(CaseField.NPI) && !Npi.isValid(npi)) {
            out.notes.add("The NPI read from your request does not pass the NPI check digit. A run from the form refuses such an NPI. It "
                    + "was used here so that both requests describe the same provider.");
        }
        String providerFirst = out.value(CaseField.PROVIDER_FIRST_NAME, formProvider == null ? null : formProvider.firstName(), notThisProvider);
        String providerLast = out.value(CaseField.PROVIDER_LAST_NAME, formProvider == null ? null : formProvider.lastName(), notThisProvider);
        String providerText = out.value(CaseField.PROVIDER_NAME_TEXT, formProvider == null ? null : formProvider.organizationName(), notThisProvider);
        // A name is written either in its parts or as one text. Whichever was found, the other is not something missing.
        if (providerFirst != null || providerLast != null) {
            out.drop(CaseField.PROVIDER_NAME_TEXT);
        } else if (providerText != null) {
            out.drop(CaseField.PROVIDER_FIRST_NAME);
            out.drop(CaseField.PROVIDER_LAST_NAME);
        }
        ProviderDetail provider = null;
        if (formProvider != null || anyPresent(npi, providerFirst, providerLast, providerText)) {
            provider = (formProvider == null ? ProviderDetail.builder() : formProvider.toBuilder()).npi(npi).firstName(providerFirst)
                    .lastName(providerLast).organizationName(providerText).build();
        }
        if (npi == null) {
            out.lacking.add("no NPI");
        }

        List<CodeInput> codes = new ArrayList<>();
        for (CodeInput code : form.procedureCodes()) {
            if (code != null && Texts.present(code.code())) {
                codes.add(code);
            }
        }
        if (!codes.isEmpty()) {
            out.sources.add(new CaseValue(CaseField.PROCEDURE_CODES.key(), CaseField.PROCEDURE_CODES.label(), CaseValue.Source.FORM, null, null,
                    Shown.of(String.join(", ", codes.stream().map(code -> code.code().strip()).toList())), null));
        } else if (!reading.ordersWithCode().isEmpty()) {
            List<CrdRequestReader.Order> orders = reading.ordersWithCode();
            for (int i = 0; i < orders.size(); i++) {
                CrdRequestReader.Order order = orders.get(i);
                codes.add(new CodeInput(order.code().value(), order.system() == null ? null : order.system().value(),
                        order.display() == null ? null : order.display().value()));
                // Named as the form names them, so a screen can set each beside the field it would have come from.
                String field = CaseField.PROCEDURE_CODES.key() + "[" + i + "]";
                out.read(field + ".code", "Procedure code " + (i + 1), order.code());
                // The system and the description travel with the code, and each is a value read from the pasted request in its own right.
                if (order.system() != null) {
                    out.read(field + ".system", "System of procedure code " + (i + 1), order.system());
                } else {
                    out.notFound.add(new NotFound(field + ".system", "System of procedure code " + (i + 1), order.codingPath() + ".system",
                            "The coding states no system. This tool then takes one from its code list, or else from the format of the code, "
                                    + "and writes none when neither gives one.", List.of()));
                }
                if (order.display() != null) {
                    out.read(field + ".description", "Description of procedure code " + (i + 1), order.display());
                } else {
                    out.notFound.add(new NotFound(field + ".description", "Description of procedure code " + (i + 1),
                            order.codingPath() + ".display", "The coding states no display. This tool writes a display only when it is given "
                            + "a description.", List.of()));
                }
            }
        } else {
            Missing missing = reading.missing().get(CaseField.PROCEDURE_CODES);
            out.notFound.add(new NotFound(CaseField.PROCEDURE_CODES.key(), CaseField.PROCEDURE_CODES.label(),
                    missing == null ? null : missing.lookedIn(), missing == null ? null : missing.note(), List.of()));
            out.lacking.add("no procedure code");
        }
        String dateOfService = out.value(CaseField.DATE_OF_SERVICE, form.dateOfService(), null);

        out.request = CrdRunRequest.builder().hook(hook).member(member).provider(provider).procedureCodes(codes).dateOfService(dateOfService)
                .includePrefetch(form.includePrefetch()).variables(form.variables()).build();
        return out;
    }

    /**
     * The hook the tool builds for. A pasted request that names a hook this tool builds decides
     * it, whatever the form says: an order-sign request beside an order-select request would list
     * differences that say nothing about either.
     */
    private static String hook(CrdRunRequest form, Reading reading, Case out) {
        String onForm = Texts.present(form.hook()) ? CrdRequestBuilder.hook(form) : null;
        Found pasted = reading.found(CaseField.HOOK);
        if (pasted != null && (CrdRunRequest.ORDER_SIGN.equals(pasted.value()) || CrdRunRequest.ORDER_SELECT.equals(pasted.value()))) {
            out.sources.add(new CaseValue(CaseField.HOOK.key(), CaseField.HOOK.label(), CaseValue.Source.YOUR_REQUEST, pasted.path(),
                    pasted.pointer(), Shown.of(pasted.value()), null));
            if (onForm != null && !onForm.equals(pasted.value())) {
                out.notes.add("The form says " + onForm + " and your request names the hook " + pasted.value() + ". The tool's request "
                        + "was built for " + pasted.value() + ", so that the two can be compared.");
            }
            return pasted.value();
        }
        String hook = onForm != null ? onForm : CrdRunRequest.ORDER_SIGN;
        out.sources.add(new CaseValue(CaseField.HOOK.key(), CaseField.HOOK.label(), CaseValue.Source.FORM, null, null, Shown.of(hook),
                onForm == null ? "The form names no hook, which this tool reads as order-sign." : null));
        if (pasted != null) {
            out.notes.add("Your request names a hook this tool does not build. The tool's request was built for " + hook + ".");
        }
        return hook;
    }

    private static boolean anyPresent(String... values) {
        for (String value : values) {
            if (Texts.present(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tool's Patient and Practitioner, each with the resource of the pasted request its values
     * were read from. Paired by key, the tool's Practitioner would be compared with whichever
     * Practitioner the pasted request happens to hold under the same key, and the values the tool
     * took from the one the request names would be reported as differing from another one's.
     */
    private static Map<String, String> builtFrom(JsonNode tool, Case built, Reading reading) {
        Reading own = CrdRequestReader.read(tool);
        Map<String, String> pairs = new LinkedHashMap<>();
        pair(pairs, own.patient(), reading.patient(), built);
        pair(pairs, own.practitioner(), reading.practitioner(), built);
        return pairs;
    }

    private static void pair(Map<String, String> pairs, String toolPointer, String yourPointer, Case built) {
        boolean readFromIt = yourPointer != null && built.sources.stream().anyMatch(source -> source.source() == CaseValue.Source.YOUR_REQUEST
                && source.pointer() != null && source.pointer().startsWith(yourPointer + "/"));
        if (toolPointer != null && readFromIt) {
            pairs.put(toolPointer, yourPointer);
        }
    }

    // ---- notes about the two requests ----

    /**
     * A coding system read from the pasted request is used as it stands, so that the two orders
     * describe the same code and can be compared. That alone would hide one thing a tester needs
     * to know: which system this tool writes when nobody states one. So the request is built once
     * more with no system stated, and each code whose system then comes out differently is noted.
     */
    private void systemsLeftToThisTool(Case built, Reading reading, Function<String, ResolvedText> variables, List<String> notes) {
        List<CrdRequestReader.Order> orders = reading.ordersWithCode();
        boolean codesReadFromRequest = built.sources.stream().anyMatch(source -> source.field().startsWith(CaseField.PROCEDURE_CODES.key() + "["));
        if (!codesReadFromRequest || orders.stream().allMatch(order -> order.system() == null)) {
            return;
        }
        CrdRunRequest request = built.request;
        List<CodeInput> unstated = request.procedureCodes().stream().map(code -> new CodeInput(code.code(), null, code.description())).toList();
        CrdRunRequest leftToTheTool = CrdRunRequest.builder().hook(request.hook()).member(request.member()).provider(request.provider())
                .procedureCodes(unstated).dateOfService(request.dateOfService()).includePrefetch(request.includePrefetch())
                .variables(request.variables()).build();
        JsonNode alone = JsonSafe.parse(builder.build(leftToTheTool, HOOK_INSTANCE, variables).json()).node();
        List<JsonNode> entries = JsonSafe.elements(JsonSafe.at(alone, "context", "draftOrders"), "entry");
        for (int i = 0; i < orders.size() && i < entries.size(); i++) {
            String stated = orders.get(i).system() == null ? null : orders.get(i).system().value();
            String chosen = JsonSafe.elements(JsonSafe.at(entries.get(i), "resource", "code"), "coding").stream().findFirst()
                    .map(coding -> JsonSafe.presentText(coding, "system")).orElse(null);
            if (stated != null && !stated.equals(chosen)) {
                notes.add("Your request states the system of the code at " + orders.get(i).code().path() + ", and the tool's request was built "
                        + "with that system. Left to work the system out for itself, from the code list or else from the format of the code, "
                        + (chosen == null ? "this tool writes no system for that code." : "this tool writes " + chosen + "."));
            }
        }
    }

    private static void otherOrderTypes(Reading reading, List<String> notes) {
        Set<String> types = new LinkedHashSet<>();
        reading.ordersWithCode().forEach(order -> types.add(order.resourceType()));
        types.remove("ServiceRequest");
        for (String type : types) {
            notes.add("Your request carries an order as " + article(type) + ". This tool builds a ServiceRequest for each procedure code, so "
                    + "the two orders are different kinds of resource and each is listed whole.");
        }
    }

    /**
     * Says, as a fact about one request, which of its references point at a resource it does not
     * carry, when it names no FHIR server the resource could be fetched from.
     */
    private static void pointingOutside(JsonNode request, String whose, List<String> notes) {
        if (Texts.present(JsonSafe.text(request, "fhirServer"))) {
            return;
        }
        Map<String, List<ResourceIndex.Use>> byTarget = new LinkedHashMap<>();
        for (ResourceIndex.Use use : ResourceIndex.of(request, CrdRequestGlossary.RULES).pointingOutside()) {
            ReferenceText text = use.text();
            String target = text.type() == null ? text.raw().strip() : text.type() + "/" + text.id();
            byTarget.computeIfAbsent(target, key -> new ArrayList<>()).add(use);
        }
        for (List<ResourceIndex.Use> uses : byTarget.values()) {
            String type = uses.get(0).text().type();
            List<String> places = uses.stream().map(ResourceIndex.Use::path).toList();
            notes.add(whose + " points at " + (type == null ? "a resource" : article(type)) + " it does not carry, at " + list(places)
                    + ". It names no fhirServer to fetch it from.");
        }
    }

    private static String article(String resourceType) {
        return ("AEIOU".indexOf(resourceType.charAt(0)) >= 0 ? "an " : "a ") + resourceType;
    }

    // ---- the headline ----

    private static String headline(int total, Map<DifferenceGroup, Integer> counted) {
        if (total == 0) {
            return "The two requests are the same in everything that was compared.";
        }
        List<String> parts = new ArrayList<>();
        for (DifferenceGroup group : DifferenceGroup.values()) {
            int count = counted.getOrDefault(group, 0);
            if (count > 0) {
                parts.add(group.counted(count));
            }
        }
        return "The two requests differ in " + total + (total == 1 ? " place: " : " places: ") + list(parts) + ".";
    }

    /** "a", "a and b", "a, b, and c". */
    private static String list(List<String> parts) {
        if (parts.size() <= 2) {
            return String.join(" and ", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + ", and " + parts.get(parts.size() - 1);
    }
}
