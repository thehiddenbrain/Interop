package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * What the requests of a collection state about members, providers and procedure codes, and what
 * adding them to the saved lists did. Each record prints counts only: what was found is sample
 * member data.
 */
public final class FoundViews {

    private FoundViews() {
    }

    /**
     * A member a collection's requests state, every value as written there.
     *
     * @param from the name of the first request it was found in
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"memberId", "vendorPatientId", "firstName", "lastName", "dateOfBirth", "gender", "subscriberId", "coverageStatus",
            "coverageStart", "coverageEnd", "plan", "from"})
    public record FoundMember(String memberId, String vendorPatientId, String firstName, String lastName, String dateOfBirth,
            String gender, String subscriberId, String coverageStatus, String coverageStart, String coverageEnd, String plan,
            String from) {

        @Override
        public String toString() {
            return "FoundMember[]";
        }
    }

    /** A provider a collection's requests state. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"npi", "vendorPractitionerId", "firstName", "lastName", "from"})
    public record FoundProvider(String npi, String vendorPractitionerId, String firstName, String lastName, String from) {

        @Override
        public String toString() {
            return "FoundProvider[]";
        }
    }

    /**
     * A procedure code a collection's requests state.
     *
     * @param system      the code system as written, or absent
     * @param systemShort the code system as a tester names it (CPT, HCPCS), or the system as written
     * @param description the display of the coding, else the text of its concept, or absent
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"code", "system", "systemShort", "description", "from"})
    public record FoundCode(String code, String system, String systemShort, String description, String from) {

        @Override
        public String toString() {
            return "FoundCode[]";
        }
    }

    /**
     * Everything one collection states.
     *
     * @param notUsed what was seen and cannot be used, one sentence each, saying what it is and why
     */
    @JsonPropertyOrder({"members", "providers", "codes", "notUsed"})
    public record Finds(List<FoundMember> members, List<FoundProvider> providers, List<FoundCode> codes, List<String> notUsed) {

        public Finds {
            members = members == null ? List.of() : List.copyOf(members);
            providers = providers == null ? List.of() : List.copyOf(providers);
            codes = codes == null ? List.of() : List.copyOf(codes);
            notUsed = notUsed == null ? List.of() : List.copyOf(notUsed);
        }

        public boolean empty() {
            return members.isEmpty() && providers.isEmpty() && codes.isEmpty();
        }

        @Override
        public String toString() {
            return "Finds[members=" + members.size() + ", providers=" + providers.size() + ", codes=" + codes.size() + ", notUsed="
                    + notUsed.size() + "]";
        }
    }

    /**
     * The answer of {@code POST /api/lists/found}.
     *
     * @param enabled   false when test-lists.enabled is false: nothing is read and nothing can be added
     * @param line      one sentence when there is nothing to show: the collection could not be read,
     *                  nothing was found, or the lists are switched off; absent otherwise
     * @param reference for a collection this tool failed to read, the reference the log holds; absent otherwise
     * @param cause     for such a failure, the chain of exception types; absent otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"enabled", "line", "reference", "cause", "members", "providers", "codes", "notUsed"})
    public record Found(boolean enabled, String line, String reference, String cause, List<FoundMember> members,
            List<FoundProvider> providers, List<FoundCode> codes, List<String> notUsed) {

        public Found {
            members = members == null ? List.of() : List.copyOf(members);
            providers = providers == null ? List.of() : List.copyOf(providers);
            codes = codes == null ? List.of() : List.copyOf(codes);
            notUsed = notUsed == null ? List.of() : List.copyOf(notUsed);
        }

        @Override
        public String toString() {
            return "Found[enabled=" + enabled + ", members=" + members.size() + ", providers=" + providers.size() + ", codes="
                    + codes.size() + "]";
        }
    }

    /**
     * What adding to one list did.
     *
     * @param added   entries added
     * @param already entries the list held already, left as they were
     * @param full    entries that did not fit, the list holding the most it keeps
     * @param refused entries the list's rules refused
     */
    @JsonPropertyOrder({"added", "already", "full", "refused"})
    public record Counts(int added, int already, int full, int refused) {
    }

    /**
     * The answer of {@code POST /api/lists/found/add}.
     *
     * @param line    the one line that says what was added and what was there already
     * @param notUsed what was found and not added, one sentence each, with the reason
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"enabled", "line", "reference", "cause", "members", "providers", "codes", "notUsed"})
    public record Added(boolean enabled, String line, String reference, String cause, Counts members, Counts providers, Counts codes,
            List<String> notUsed) {

        public Added {
            notUsed = notUsed == null ? List.of() : List.copyOf(notUsed);
        }

        @Override
        public String toString() {
            return "Added[" + members + ", " + providers + ", " + codes + "]";
        }
    }

    /**
     * One Postman export as the page read it.
     *
     * @param fileName the name of the file the tester chose, or absent
     * @param text     its text, as the page decoded it
     */
    public record ExportText(String fileName, String text) {

        /** Never the text: an export holds credentials and sample member data. */
        @Override
        public String toString() {
            return "ExportText[" + (text == null ? "no text" : text.length() + " characters") + "]";
        }
    }

    /**
     * What {@code POST /api/lists/found} and {@code POST /api/lists/found/add} take.
     *
     * @param collectionExport      the collection export to read
     * @param environmentExport     the environment export chosen with it, whose variables fill the
     *                              collection's placeholders, or absent
     * @param forId                 the stored environment being edited, whose variables fill them
     *                              when no environment export is given, or absent
     * @param fromActiveEnvironment true where a collection is uploaded without the environment form
     *                              (Configuration > Collections): the active environment's
     *                              variables fill the placeholders
     */
    public record FoundRequest(ExportText collectionExport, ExportText environmentExport, String forId, Boolean fromActiveEnvironment) {

        @Override
        public String toString() {
            return "FoundRequest[" + (collectionExport == null ? "" : "collection") + (environmentExport == null ? "" : " environment") + "]";
        }
    }
}
