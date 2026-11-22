package org.p32h.interop.epa.pave.service.draft;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;
import org.p32h.interop.epa.pave.service.config.SecretSetting;

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
     * @param collectionId the id the collection is stored under once saved, which a request chosen
     *                   for an operation is mapped in; absent without a collection that was read
     * @param addresses  one row per operation of the Edit form's Addresses: what the files give it (D-307)
     * @param requests   every request of the collection, to choose one for an operation from
     * @param values     one row per value of the Edit form's Values: what the files give it (D-307)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Draft(List<FileLine> files, String name, Boolean nameTaken, List<VariableView> variables, SignInDraft signIn,
                        List<ShapeRow> shapes, List<OperationRow> operations, List<String> warnings, long epoch, String collectionId,
                        List<AddressDraft> addresses, List<RequestChoice> requests, List<ValueDraft> values) {

        public Draft {
            files = files == null ? List.of() : List.copyOf(files);
            variables = variables == null ? List.of() : List.copyOf(variables);
            shapes = shapes == null ? List.of() : List.copyOf(shapes);
            operations = operations == null ? List.of() : List.copyOf(operations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
            requests = requests == null ? List.of() : List.copyOf(requests);
            values = values == null ? List.of() : List.copyOf(values);
        }

        /** A draft that says nothing of addresses or values, as one read before D-307. */
        public Draft(List<FileLine> files, String name, Boolean nameTaken, List<VariableView> variables, SignInDraft signIn,
                     List<ShapeRow> shapes, List<OperationRow> operations, List<String> warnings, long epoch) {
            this(files, name, nameTaken, variables, signIn, shapes, operations, warnings, epoch, null, null, null, null);
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
     * @param clientSecret         set with its reference, which is all a secret is ever given as; absent when the files hold
     *                             none or hold a value typed in
     * @param bearerToken          as {@code clientSecret}
     * @param from                 the collection's sign-in, the collection's token request by name, or the environment's
     *                             variable names
     * @param fields               the fields filled, by their labels on the form, in the order of the form
     * @param candidates           for a field no one variable could fill, every variable whose name could be it (D-305), offered
     *                             as references to choose from; empty when there is no such field
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignInDraft(String method, String tokenUrl, String clientId, String scope, String clientAuthentication,
                              SecretSetting clientSecret, SecretSetting bearerToken, String from, List<String> fields,
                              List<Candidates> candidates) {

        public SignInDraft {
            fields = fields == null ? List.of() : List.copyOf(fields);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** A sign-in whose fields are listed from what it holds, with no candidates. */
        public SignInDraft(String method, String tokenUrl, String clientId, String scope, String clientAuthentication,
                           SecretSetting clientSecret, SecretSetting bearerToken, String from) {
            this(method, tokenUrl, clientId, scope, clientAuthentication, clientSecret, bearerToken, from,
                    filled(tokenUrl, clientId, clientSecret, scope, clientAuthentication, bearerToken), List.of());
        }

        static List<String> filled(String tokenUrl, String clientId, SecretSetting clientSecret, String scope, String clientAuthentication,
                                   SecretSetting bearerToken) {
            List<String> labels = new java.util.ArrayList<>();
            if (tokenUrl != null) {
                labels.add("Token url");
            }
            if (clientId != null) {
                labels.add("Client id");
            }
            if (clientSecret != null) {
                labels.add("Client secret");
            }
            if (scope != null) {
                labels.add("Scope");
            }
            if (clientAuthentication != null) {
                labels.add("Client authentication");
            }
            if (bearerToken != null) {
                labels.add("Bearer token");
            }
            return labels;
        }
    }

    /**
     * The variables that could fill one sign-in field, none of them filled (D-305).
     *
     * @param field      the field by the name the form posts it under: tokenUrl, clientId, clientSecret or scope
     * @param references each variable as a {@code {{name}}} reference, in the order of the export
     */
    public record Candidates(String field, List<String> references) {

        public Candidates {
            references = references == null ? List.of() : List.copyOf(references);
        }
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

    /**
     * What the files give one operation of the Edit form's Addresses (D-307).
     *
     * @param operation   the operation a request is mapped to for this row
     * @param key         the key an address is typed under: the operation's name, or SUBSCRIPTION
     * @param label       what the Edit form calls it
     * @param shortLabel  the operation in a word or two, sent as {@code short}
     * @param requestId   the request of the collection a call will use, or absent when none was found
     * @param requestName that request's name with its folders
     * @param display     the address with the files' values put in, a secret masked, a placeholder nothing defines left as
     *                    {@code {{name}}}; absent when nothing gives one
     * @param from        where it comes from, in words: from the collection's request, from application.yml, or cut from a
     *                    hook request; absent when nothing gives one
     * @param lines       one line each: what else the tester should know of this row
     * @param candidates  the ids of the requests that fit this operation, the one used first, in the order of the file
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddressDraft(String operation, String key, String label, @JsonProperty("short") String shortLabel, String requestId,
                               String requestName, String display, String from, List<String> lines, List<String> candidates) {

        public AddressDraft {
            lines = lines == null ? List.of() : List.copyOf(lines);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** The operation and the request's name only: an address can hold a credential. */
        @Override
        public String toString() {
            return "AddressDraft[" + operation + ", " + (requestId == null ? "no request" : requestId) + "]";
        }
    }

    /**
     * One request of the collection, to choose for an operation.
     *
     * @param id          its id within the collection, which the mapping takes
     * @param name        its name with its folders
     * @param method      its method
     * @param display     its address as {@link AddressDraft#display()} shows one
     * @param operation   the operation it is mapped to, or UNMAPPED
     * @param basis       what that mapping rests on, in words
     * @param errorSample  whether its name says it is a sample of an error, which no call uses unless it is chosen
     * @param tokenRequest whether it is a request for an access token, which the sign-in reads and no operation is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestChoice(String id, String name, String method, String display, String operation, String basis,
                                boolean errorSample, boolean tokenRequest) {

        @Override
        public String toString() {
            return "RequestChoice[" + id + ", " + operation + "]";
        }
    }

    /**
     * What the files give one value of the Edit form's Values (D-307).
     *
     * @param name    the variable name a request reads
     * @param label   what the Edit form calls it
     * @param why     why a request needs it
     * @param display the value, a secret masked; absent when nothing gives it
     * @param from    where it comes from, in words; absent when nothing gives it
     * @param line    when the export holds the variable with no value, a line that says so
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValueDraft(String name, String label, String why, String display, String from, String line) {

        @Override
        public String toString() {
            return "ValueDraft[" + name + ", " + (display == null ? "not given" : "given") + "]";
        }
    }
}
