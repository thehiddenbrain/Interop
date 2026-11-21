package org.p32h.interop.epa.pave.model.compare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * The request this tool builds beside a request a tester pasted, with every difference between
 * the two listed and explained.
 *
 * <p>Nothing is sent and nothing is recorded to produce one. It shows where the two differ. It
 * cannot show which of the two the service expects, and no field of it says so.
 *
 * <p>Construct with {@link #builder()}.
 *
 * @param comparable  true when the two were compared field by field. False is an answer, not an
 *                    error: the pasted text is not a JSON object, or the tool could not build its
 *                    own request
 * @param whyNot      when not comparable, the reason in one plain sentence. Null otherwise
 * @param headline    one plain sentence a tester can read out
 * @param hook        the hook the tool's request was built for, or null when none was built
 * @param toolBody    the tool's request as indented JSON, or null when it could not be built
 * @param yourBody    the pasted request after its placeholders were filled in, indented the same
 *                    way with its keys in their own order. When it is not JSON, the text as written
 * @param caseSource  each value of the case the tool's request was built for, and whether it came
 *                    from the form or was read out of the pasted request
 * @param notFound    each value of the case that neither the form nor the pasted request supplied
 * @param differences every difference, in the document order of the tool's request. What only
 *                    the pasted request has follows what its neighbours in the tool's request have
 * @param counts      the number of differences in each group, every group listed
 * @param notes       what a tester should know about the comparison as a whole
 * @param caveat      the sentence that a comparison shows where two requests differ and cannot show
 *                    which one the service expects. Set on every comparison, comparable or not; a
 *                    comparable one also carries it first among the notes
 * @param maskedNote  one sentence saying how many values are shown masked because the name of the
 *                    element holding each reads as a credential. Null when nothing was masked
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"comparable", "whyNot", "headline", "caveat", "maskedNote", "hook", "counts", "notes", "caseSource", "notFound", "differences",
        "toolBody", "yourBody"})
public record RequestComparison(boolean comparable, String whyNot, String headline, String hook, String toolBody, String yourBody,
                                List<CaseValue> caseSource, List<NotFound> notFound, List<Difference> differences,
                                List<GroupCount> counts, List<String> notes, String caveat, String maskedNote) {

    public RequestComparison {
        if (headline == null || headline.isBlank()) {
            throw new IllegalArgumentException("A comparison needs a headline.");
        }
        if (!comparable && (whyNot == null || whyNot.isBlank())) {
            throw new IllegalArgumentException("A comparison that could not be made says why.");
        }
        caseSource = caseSource == null ? List.of() : List.copyOf(caseSource);
        notFound = notFound == null ? List.of() : List.copyOf(notFound);
        differences = differences == null ? List.of() : List.copyOf(differences);
        counts = counts == null ? List.of() : List.copyOf(counts);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because both bodies and every value hold member data. */
    @Override
    public String toString() {
        return "RequestComparison[comparable=" + comparable + ", differences=" + differences.size() + "]";
    }

    public static final class Builder {
        private boolean comparable;
        private String whyNot;
        private String headline;
        private String hook;
        private String toolBody;
        private String yourBody;
        private List<CaseValue> caseSource = List.of();
        private List<NotFound> notFound = List.of();
        private List<Difference> differences = List.of();
        private List<GroupCount> counts = List.of();
        private List<String> notes = List.of();
        private String caveat;
        private String maskedNote;

        private Builder() {
        }

        public Builder comparable(boolean value) {
            this.comparable = value;
            return this;
        }

        public Builder whyNot(String value) {
            this.whyNot = value;
            return this;
        }

        public Builder headline(String value) {
            this.headline = value;
            return this;
        }

        public Builder hook(String value) {
            this.hook = value;
            return this;
        }

        public Builder toolBody(String value) {
            this.toolBody = value;
            return this;
        }

        public Builder yourBody(String value) {
            this.yourBody = value;
            return this;
        }

        public Builder caseSource(List<CaseValue> value) {
            this.caseSource = value;
            return this;
        }

        public Builder notFound(List<NotFound> value) {
            this.notFound = value;
            return this;
        }

        public Builder differences(List<Difference> value) {
            this.differences = value;
            return this;
        }

        public Builder counts(List<GroupCount> value) {
            this.counts = value;
            return this;
        }

        public Builder notes(List<String> value) {
            this.notes = value;
            return this;
        }

        public Builder caveat(String value) {
            this.caveat = value;
            return this;
        }

        public Builder maskedNote(String value) {
            this.maskedNote = value;
            return this;
        }

        public RequestComparison build() {
            return new RequestComparison(comparable, whyNot, headline, hook, toolBody, yourBody, caseSource, notFound, differences,
                    counts, notes, caveat, maskedNote);
        }
    }
}
