package org.p32h.interop.epa.pave.service.draft;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;

/**
 * What {@code POST /api/config/environments/draft} answers (plan 5.3). Nothing here holds the
 * value of a secret: a secret appears only as the reference it is, and a variable only as the
 * masked row every stored variable is shown with.
 */
public final class DraftViews {

    private DraftViews() {
    }

    /**
     * @param files      one line per file given, in the order environment, collection
     * @param name       the environment export's name; absent without one that was read
     * @param nameTaken  for a new environment, whether that name is stored already; absent without a name
     * @param variables  the environment export's variables, masked
     * @param signIn     what sign-in the files show; absent when they show none
     * @param shapes     the request shapes the collection's CRD order-sign sample shows (R-37)
     * @param operations the operations the collection maps, one row each
     * @param warnings   one line each: what was not filled and why, and what the parser set aside
     * @param epoch      the configuration epoch the draft was read against
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Draft(List<FileLine> files, String name, Boolean nameTaken, List<VariableView> variables, SignInDraft signIn,
                        List<ShapeRow> shapes, List<OperationRow> operations, List<String> warnings, long epoch) {

        public Draft {
            files = files == null ? List.of() : List.copyOf(files);
            variables = variables == null ? List.of() : List.copyOf(variables);
            shapes = shapes == null ? List.of() : List.copyOf(shapes);
            operations = operations == null ? List.of() : List.copyOf(operations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** Counts only: a name and variables come from the tester's files. */
        @Override
        public String toString() {
            return "Draft[" + files.size() + " files, " + variables.size() + " variables, " + operations.size() + " operations]";
        }
    }

    /**
     * @param slot      environmentExport or collectionExport
     * @param fileName  the name of the file as chosen, or absent
     * @param read      whether it was read
     * @param line      "Read: ..." or "Not read: ...", the words shown beside the file
     * @param reference for a failure of this tool, the reference it is logged under
     * @param cause     for a failure of this tool, the chain of exception types, as the log names it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileLine(String slot, String fileName, boolean read, String line, String reference, String cause) {
    }

    /**
     * The sign-in the form can be filled with, in the shape of the settings view (plan 5.4).
     *
     * @param method               NONE, CLIENT_CREDENTIALS or BEARER_TOKEN; absent when the files
     *                             show some sign-in fields but not a whole method
     * @param clientAuthentication BASIC or BODY, only when the collection states it
     * @param clientSecret         set with its reference; absent when the files hold none or hold a value typed in
     * @param bearerToken          as {@code clientSecret}
     * @param from                 the collection's sign-in, or the environment's variable names
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignInDraft(String method, String tokenUrl, String clientId, String scope, String clientAuthentication,
                              SecretReference clientSecret, SecretReference bearerToken, String from) {
    }

    /** A secret as the form may see it: only ever a reference such as {{clientSecret}} or {{env:PAVE_NAME}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SecretReference(boolean set, String reference) {
    }

    /**
     * @param key         the shape, for example CRD_DRAFT_ORDERS_TYPE
     * @param value       one of its option values
     * @param from        "the collection's sample"
     * @param requestName the collection request whose saved body shows it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShapeRow(String key, String value, String from, String requestName) {
    }

    /**
     * @param operation   the operation by name
     * @param shortLabel  the operation in a word or two, sent as {@code short}
     * @param label       the operation's full label
     * @param requestName the first request of the collection mapped to it
     * @param template    that request's url as written, placeholders intact
     * @param scripted    whether that request carries a Postman script, which PAVE never runs
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperationRow(String operation, @JsonProperty("short") String shortLabel, String label, String requestName,
                               String template, boolean scripted) {
    }
}
