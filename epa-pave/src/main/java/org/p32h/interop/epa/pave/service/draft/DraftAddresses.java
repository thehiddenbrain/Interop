package org.p32h.interop.epa.pave.service.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.UrlForms;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.config.DraftSupport;
import org.p32h.interop.epa.pave.service.draft.DraftViews.AddressDraft;
import org.p32h.interop.epa.pave.service.draft.DraftViews.RequestChoice;
import org.p32h.interop.epa.pave.service.draft.DraftViews.ValueDraft;

/**
 * What the files give each row of the Edit form's Addresses and Values, said right after "Fill
 * the form from these files" (D-307). On the first real run the tester saw chips and a sentence
 * that the rest could be set once saved, then rows of empty boxes, and typed every address and
 * value by hand: a typed address then replaced the collection's request it was meant to be.
 *
 * <p>The request a row names is the one a call would use once the files are saved: the first in
 * the order of the file that is mapped to the operation, passing over a sample of an error, as
 * the gateway chooses (R-02). Nothing is guessed: a row nothing gives says so and what happens
 * then, and a placeholder nothing defines stays visible as itself.
 */
final class DraftAddresses {

    private static final String DISCOVERY_MARKER = "/cds-services";

    private DraftAddresses() {
    }

    /** The detection of each request in the order of the file, as the store will make it. */
    record Detected(PostmanRequest request, OperationDetector.Detection detection) {

        boolean errorSample() {
            // As the gateway passes it over: a request whose name says "error" is a sample of a failure.
            return request.name().toLowerCase(Locale.ROOT).contains("error");
        }
    }

    static List<Detected> detect(PostmanCollection collection) {
        List<Detected> detected = new ArrayList<>();
        for (PostmanRequest request : collection.requests()) {
            detected.add(new Detected(request, OperationDetector.detect(request.name(), request.method(), request.url(),
                    request.folderPath())));
        }
        return detected;
    }

    /** @param tokenRequests the names of the requests the sign-in read as token requests, as a sentence quotes them */
    static List<RequestChoice> requests(List<Detected> detected, PostmanEnvironment environment, PostmanCollection collection, String forId,
                                        DraftSupport support, java.util.Set<String> tokenRequests) {
        List<RequestChoice> choices = new ArrayList<>();
        for (Detected each : detected) {
            PostmanRequest request = each.request();
            String display = request.url() == null ? null : support.resolve(forId, environment, collection, request.url()).shown();
            choices.add(new RequestChoice(request.id(), request.qualifiedName(), request.method(), display,
                    each.detection().operation().name(), each.detection().basis(), each.errorSample(),
                    tokenRequests.contains(request.quotedName())));
        }
        return choices;
    }

    static List<AddressDraft> addresses(List<Detected> detected, String collectionId, PostmanEnvironment environment,
                                        PostmanCollection collection, String forId, DraftSupport support) {
        List<AddressDraft> rows = new ArrayList<>();
        for (DraftSupport.Addressed addressed : DraftSupport.addressed()) {
            rows.add(row(addressed, detected, collectionId, environment, collection, forId, support));
        }
        return rows;
    }

    private static AddressDraft row(DraftSupport.Addressed addressed, List<Detected> detected, String collectionId,
                                    PostmanEnvironment environment, PostmanCollection collection, String forId, DraftSupport support) {
        EpaOperation operation = addressed.operation();
        List<Detected> fits = new ArrayList<>();
        List<Detected> errorSamples = new ArrayList<>();
        for (Detected each : detected) {
            if (each.detection().operation() == operation) {
                (each.errorSample() ? errorSamples : fits).add(each);
            }
        }
        List<String> lines = new ArrayList<>();
        List<String> candidates = fits.stream().map(each -> each.request().id()).toList();
        if (!fits.isEmpty()) {
            PostmanRequest used = fits.get(0).request();
            if (fits.size() > 1) {
                lines.add(fits.size() + " requests fit this operation. \"" + used.quotedName() + "\" is used, the first in the file: "
                        + "choose another below to use it instead.");
            }
            String before = collectionId == null ? null : support.usedBefore(operation, collectionId);
            if (before != null) {
                lines.add("Until it is removed in Configuration > Collections, a call uses " + before + ", not this request.");
            }
            if (used.url() == null) {
                lines.add("This request has no url, so it cannot be sent.");
                return new AddressDraft(operation.name(), addressed.key(), addressed.label(), addressed.shortLabel(), used.id(),
                        used.qualifiedName(), null, fromRequest(used), lines, candidates);
            }
            Resolution resolution = support.resolve(forId, environment, collection, used.url());
            undefined(resolution, lines);
            return new AddressDraft(operation.name(), addressed.key(), addressed.label(), addressed.shortLabel(), used.id(),
                    used.qualifiedName(), resolution.shown(), fromRequest(used), lines, candidates);
        }

        if (collection == null) {
            lines.add("No collection export is chosen, so no request gives it.");
        } else {
            lines.add("No request of the collection was found for it. Choose one below.");
        }
        for (Detected sample : errorSamples) {
            lines.add("\"" + sample.request().quotedName() + "\" fits it, but its name says it is a sample of an error, which a call "
                    + "uses only when it is chosen.");
        }
        String fallback = support.fallback(operation);
        if (fallback != null) {
            Resolution resolution = support.resolve(forId, environment, collection, fallback);
            undefined(resolution, lines);
            return new AddressDraft(operation.name(), addressed.key(), addressed.label(), addressed.shortLabel(), null, null,
                    resolution.shown(), "from application.yml", lines, candidates);
        }
        if (operation == EpaOperation.CRD_DISCOVERY) {
            // Discovery lives beside the hooks: with nothing else, a call cuts a hook request's url after /cds-services.
            for (EpaOperation hook : List.of(EpaOperation.CRD_ORDER_SIGN, EpaOperation.CRD_ORDER_SELECT)) {
                Detected found = detected.stream().filter(each -> each.detection().operation() == hook && !each.errorSample()
                        && each.request().url() != null).findFirst().orElse(null);
                if (found != null) {
                    String template = found.request().url();
                    int end = UrlForms.endOfSegment(template, DISCOVERY_MARKER);
                    if (end >= 0) {
                        template = template.substring(0, end);
                    }
                    lines.clear();
                    Resolution resolution = support.resolve(forId, environment, collection, template);
                    undefined(resolution, lines);
                    return new AddressDraft(operation.name(), addressed.key(), addressed.label(), addressed.shortLabel(), null, null,
                            resolution.shown(), "cut from the collection's request \"" + found.request().quotedName() + "\" after "
                            + DISCOVERY_MARKER, lines, candidates);
                }
            }
        }
        lines.add("Without one, a call for it is refused before anything is sent.");
        return new AddressDraft(operation.name(), addressed.key(), addressed.label(), addressed.shortLabel(), null, null, null, null, lines,
                candidates);
    }

    private static String fromRequest(PostmanRequest request) {
        return "from the collection's request \"" + request.quotedName() + "\"";
    }

    /** One line for each placeholder of an address that nothing defines: it stays in the address as written. */
    private static void undefined(Resolution resolution, List<String> lines) {
        for (String name : resolution.unresolved()) {
            if (name.startsWith(VariableResolver.OS_REFERENCE)) {
                continue;
            }
            lines.add("{{" + Names.shown(name) + "}} is not defined in these files, so it stays as written: a run asks for its value "
                    + "before it sends.");
        }
    }

    static List<ValueDraft> values(PostmanEnvironment environment, PostmanCollection collection, String forId, DraftSupport support) {
        List<ValueDraft> rows = new ArrayList<>();
        for (DraftSupport.Known known : DraftSupport.known()) {
            Resolution resolution = support.resolve(forId, environment, collection, "{{" + known.name() + "}}");
            if (resolution.complete() && !resolution.variables().isEmpty()) {
                VariableUse use = resolution.variables().get(0);
                rows.add(new ValueDraft(known.name(), known.label(), known.why(), resolution.display(), from(use), null));
                continue;
            }
            if ("callbackUrl".equals(known.name()) && support.callbackUrl() != null) {
                rows.add(new ValueDraft(known.name(), known.label(), known.why(), support.callbackUrl(), "from application.yml", null));
                continue;
            }
            // What follows when nothing gives a value is said once for the part, on screen; only a blank variable has its own line.
            String line = resolution.reasons().containsKey(known.name())
                    ? "{{" + Names.shown(known.name()) + "}} has no value in these files. Postman exports a variable's initial value only."
                    : null;
            rows.add(new ValueDraft(known.name(), known.label(), known.why(), null, null, line));
        }
        return rows;
    }

    private static String from(VariableUse use) {
        if (use.source() == VariableSource.APPLICATION_YML) {
            return "from application.yml";
        }
        if (use.source() == VariableSource.COLLECTION) {
            return "from the collection's variable " + Names.shown(use.name());
        }
        return "from the export's variable " + Names.shown(use.name());
    }

    /** The id a collection is stored under, as the store names it, or null without one. */
    static String collectionId(PostmanCollection collection) {
        return collection == null || Texts.blank(collection.name()) ? null : org.p32h.interop.epa.pave.postman.Ids.named(collection.name());
    }
}
