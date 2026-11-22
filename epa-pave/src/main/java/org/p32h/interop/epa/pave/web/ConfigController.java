package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.ConfigService;
import org.p32h.interop.epa.pave.service.config.ConfigViews;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Activated;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionList;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Effective;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentList;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.MappingChanged;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Removed;
import org.p32h.interop.epa.pave.service.config.ConfigViews.RequestDetail;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Resolved;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.config.SettingsViews;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Config endpoints: upload and switch environments, upload collections and correct their
 * mapping, unlock and relock production writes, and see what is in force.
 *
 * <p>Nothing here returns the value of a secret. Every answer is built by {@link ConfigService}
 * from masked rows, and an upload answers with what was stored in that same masked form.
 *
 * <p>The production unlock lives in the HTTP session and is read from there. It is never a
 * request parameter or part of a posted body, so nothing a browser sends can stand in for it. The
 * only thing a browser can send is the name of the environment, typed, and the gateway decides
 * whether that earns an unlock.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final String UNLOCK = "unlock";
    private static final String LOCK = "lock";

    /** What an operation parameter accepts, in words. Built from the enum so it cannot fall behind it. */
    private static final String OPERATIONS = "one of " + String.join(", ", Arrays.stream(EpaOperation.values()).map(Enum::name).toList());

    /**
     * What {@code PUT /api/config/collections/{id}/mapping} takes.
     *
     * @param requestId the id of the request within the collection
     * @param operation the operation it stands for, by name. Choosing what detection proposed
     *                  removes the correction
     */
    public record MappingRequest(String requestId, String operation) {
    }

    /**
     * What {@code POST /api/config/writes} takes.
     *
     * @param action      unlock or lock
     * @param environment for unlock, the name of the active production environment, typed exactly
     */
    public record WritesRequest(String action, String environment) {
    }

    /**
     * What {@code POST /api/config/resolve} takes.
     *
     * @param operation the operation to preview, by name
     * @param request   a specific collection request, or absent for the one the operation uses by default
     * @param variables placeholder values typed for this run
     * @param urlValues values for the single-brace parts of a url, such as the id of a lookup
     */
    public record ResolveRequest(String operation, RequestRef request, Map<String, String> variables, Map<String, String> urlValues) {

        /** Prints the names of the values and never the values: one typed for a run can be a secret or a member ID. */
        @Override
        public String toString() {
            return "ResolveRequest[" + operation + ", variables=" + (variables == null ? List.of() : variables.keySet()) + ", urlValues="
                    + (urlValues == null ? List.of() : urlValues.keySet()) + "]";
        }
    }

    /**
     * What {@code POST /api/config/environments} takes as JSON (R-06): the export as the page read it.
     *
     * @param fileName           the name the file was chosen under, for display only
     * @param text               the file's text, decoded as UTF-8 by the page
     * @param kind               sandbox, uat or prod; never guessed
     * @param acknowledgeNotProd the confirmation that an environment that reads as production is not
     */
    public record EnvironmentUploadRequest(String fileName, String text, String kind, Boolean acknowledgeNotProd) {

        /** Names the file and the kind, never the text: an export can hold credentials and member data. */
        @Override
        public String toString() {
            return "EnvironmentUploadRequest[" + kind + "]";
        }
    }

    /**
     * What {@code POST /api/config/collections} takes as JSON (R-06).
     *
     * @param fileName the name the file was chosen under, for display only
     * @param text     the file's text, decoded as UTF-8 by the page
     */
    public record CollectionUploadRequest(String fileName, String text) {

        /** Never the text: a collection can hold credentials and member data. */
        @Override
        public String toString() {
            return "CollectionUploadRequest[]";
        }
    }

    private final ConfigService service;

    public ConfigController(ConfigService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------------------------------
    // Environments
    // ------------------------------------------------------------------------------------------

    /**
     * Stores an uploaded environment. The kind is required and is never inferred or defaulted. An
     * environment that reads as production and is declared as anything else is refused unless
     * {@code acknowledgeNotProd} is true.
     */
    @PostMapping("/environments")
    public EnvironmentUploaded uploadEnvironment(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(name = "kind", required = false) String kind,
                                                 @RequestParam(name = "acknowledgeNotProd", required = false) String acknowledgeNotProd) {
        if (Texts.blank(kind)) {
            throw ValidationException.of("kind", "State what kind of environment this is: sandbox, uat or prod. It is never guessed.");
        }
        EnvironmentKind declared = Params.optional("kind", kind, EnvironmentKind::parse, "sandbox, uat or prod");
        Boolean acknowledged = Params.optional("acknowledgeNotProd", acknowledgeNotProd, ConfigController::trueOrFalse, "true or false");
        return service.uploadEnvironment(file.getOriginalFilename(), bytes(file), declared, Boolean.TRUE.equals(acknowledged));
    }

    /**
     * As the multipart upload, with the export posted as the text the page read from the file
     * (R-06): one parser, on the server, and no multipart machinery on the screen's path.
     */
    @PostMapping(path = "/environments", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public EnvironmentUploaded uploadEnvironmentText(@RequestBody EnvironmentUploadRequest body) {
        if (Texts.blank(body.kind())) {
            throw ValidationException.of("kind", "State what kind of environment this is: sandbox, uat or prod. It is never guessed.");
        }
        EnvironmentKind declared = Params.optional("kind", body.kind(), EnvironmentKind::parse, "sandbox, uat or prod");
        return service.uploadEnvironment(body.fileName(), text("text", body.text()), declared, Boolean.TRUE.equals(body.acknowledgeNotProd()));
    }

    @GetMapping("/environments")
    public EnvironmentList environments() {
        return service.environments();
    }

    /**
     * Makes an environment the active one, with no restart. The name in the path can be the
     * environment's name or its id: a name that holds a slash cannot travel in one path segment.
     */
    @PostMapping("/environments/{name}/activate")
    public Activated activate(@PathVariable("name") String name, HttpServletRequest http) {
        return service.activate(name, SessionUnlock.from(http));
    }

    /** One environment for its form: every setting it states, each beside what is in force and where that comes from. */
    @GetMapping("/environments/{id}/settings")
    public SettingsViews.EnvironmentSettingsView settings(@PathVariable("id") String id) {
        return service.settings(id);
    }

    /**
     * Makes or saves an environment from its form, with any exports chosen, in one commit. A secret
     * left blank keeps the stored one; a property the form does not have is refused by name.
     */
    @PostMapping("/environments/settings")
    public SettingsViews.SettingsSaved saveSettings(@RequestBody SettingsViews.EnvironmentSettingsInput body) {
        return service.saveSettings(body);
    }

    @DeleteMapping("/environments/{name}")
    public Removed removeEnvironment(@PathVariable("name") String name) {
        return service.removeEnvironment(name);
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    @PostMapping("/collections")
    public CollectionUploaded uploadCollection(@RequestParam("file") MultipartFile file) {
        return service.uploadCollection(file.getOriginalFilename(), bytes(file));
    }

    /** As the multipart upload, with the export posted as the text the page read from the file (R-06). */
    @PostMapping(path = "/collections", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public CollectionUploaded uploadCollectionText(@RequestBody CollectionUploadRequest body) {
        return service.uploadCollection(body.fileName(), text("text", body.text()));
    }

    @GetMapping("/collections")
    public CollectionList collections() {
        return service.collections();
    }

    @GetMapping("/collections/{id}/requests/{requestId}")
    public RequestDetail request(@PathVariable("id") String id, @PathVariable("requestId") String requestId) {
        return service.request(id, requestId);
    }

    @PutMapping("/collections/{id}/mapping")
    public MappingChanged correctMapping(@PathVariable("id") String id, @RequestBody MappingRequest body) {
        List<FieldError> errors = new ArrayList<>();
        if (Texts.blank(body.requestId())) {
            errors.add(new FieldError("requestId", "State which request of the collection to map."));
        }
        if (Texts.blank(body.operation())) {
            errors.add(new FieldError("operation", "Choose the operation this request stands for."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        EpaOperation operation = Params.optional("operation", body.operation(), EpaOperation::parse, OPERATIONS);
        return service.correctMapping(id, body.requestId().strip(), operation);
    }

    @DeleteMapping("/collections/{id}")
    public Removed removeCollection(@PathVariable("id") String id) {
        return service.removeCollection(id);
    }

    // ------------------------------------------------------------------------------------------
    // Production writes
    // ------------------------------------------------------------------------------------------

    /**
     * Unlocks production writes for this browser session, or locks them again. The answer says
     * whether a write would be sent now and why. A refused unlock is answered by the exception
     * handler: 403 when an owner has locked the installation, 422 when the name does not match or
     * there is nothing to unlock.
     */
    @PostMapping("/writes")
    public WriteAccess writes(@RequestBody WritesRequest body, HttpServletRequest http) {
        String action = Texts.present(body.action()) ? body.action().strip().toLowerCase(Locale.ROOT) : "";
        if (UNLOCK.equals(action)) {
            if (body.environment() == null) {
                throw ValidationException.of("environment", "Type the name of the active production environment to unlock writes to it.");
            }
            // The name goes to the gateway exactly as it was typed. It is compared, never logged.
            ProdUnlock unlock = service.unlock(body.environment());
            SessionUnlock.store(http, unlock);
            return service.writeAccess(unlock);
        }
        if (LOCK.equals(action)) {
            SessionUnlock.clear(http);
            service.lock();
            return service.writeAccess(null);
        }
        throw ValidationException.of("action", "The action must be " + UNLOCK + " or " + LOCK + ".");
    }

    // ------------------------------------------------------------------------------------------
    // What is in force
    // ------------------------------------------------------------------------------------------

    @GetMapping("/effective")
    public Effective effective(HttpServletRequest http) {
        return service.effective(SessionUnlock.from(http));
    }

    /** What a request would be, before it is sent. Nothing is sent and nothing is recorded. */
    /** The file as the list of unreadable stored files names it. */
    public record UnreadableRemoveRequest(String file) {
    }

    /** "Remove it from the store": moves an unreadable stored file aside, never deleting it, and takes it out of store.json. */
    @PostMapping("/unreadable/remove")
    public ConfigViews.UnreadableRemoved removeUnreadable(@RequestBody UnreadableRemoveRequest body) {
        return service.removeUnreadable(body.file());
    }

    @PostMapping("/resolve")
    public Resolved resolve(@RequestBody ResolveRequest body, HttpServletRequest http) {
        if (Texts.blank(body.operation())) {
            throw ValidationException.of("operation", "State which operation to resolve.");
        }
        EpaOperation operation = Params.optional("operation", body.operation(), EpaOperation::parse, OPERATIONS);
        RequestRef request = body.request();
        if (request != null && (Texts.blank(request.collectionId()) || Texts.blank(request.requestId()))) {
            throw ValidationException.of("request", "A request is named by both its collectionId and its requestId. Leave request out "
                    + "to use the one the operation uses by default.");
        }
        return service.resolve(operation, request, body.variables(), body.urlValues(), SessionUnlock.from(http));
    }

    /** The posted text as the bytes the store keeps: the text as the page decoded it (R-06). */
    private static byte[] text(String field, String text) {
        if (text == null) {
            throw ValidationException.of(field, "The file holds no text. Choose it again.");
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BadInputException("The uploaded file could not be read.", "Upload the file again.");
        }
    }

    /** Reads true or false and nothing else, so a misspelt acknowledgement is refused and not taken for a no. */
    private static Boolean trueOrFalse(String text) {
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Not true or false.");
    }
}
