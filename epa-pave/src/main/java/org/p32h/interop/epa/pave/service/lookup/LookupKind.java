package org.p32h.interop.epa.pave.service.lookup;

import java.util.List;
import java.util.Locale;

/**
 * The two lookups a tester can configure on screen (R-11), with what differs between them: the
 * token the identifier takes in a body template, the card fields an answer may be read into, and
 * the page the settings live on, which every sentence about a saved lookup names.
 */
public enum LookupKind {

    MEMBER("member", "memberId", "Configuration > Members", List.of(
            new Field("firstName", "First name"),
            new Field("lastName", "Last name"),
            new Field("dateOfBirth", "Date of birth"),
            new Field("gender", "Gender"),
            new Field("lineOfBusiness", "Line of business"),
            new Field("planName", "Plan"),
            new Field("coverageStatus", "Coverage status"),
            new Field("coverageStart", "Coverage start"),
            new Field("coverageEnd", "Coverage end"),
            new Field("subscriberId", "Subscriber ID"))),
    PROVIDER("provider", "npi", "Configuration > Providers", List.of(
            new Field("firstName", "First name"),
            new Field("lastName", "Last name"),
            new Field("organizationName", "Organization"),
            new Field("specialty", "Specialty"),
            new Field("phone", "Phone"),
            new Field("fax", "Fax")));

    /** A card field a field path may point at, by the name the answer's fields use and the label the form shows. */
    public record Field(String name, String label) {
    }

    /** The token the date of service takes in a body template, for both kinds. */
    public static final String DATE_TOKEN = "dateOfService";

    /** The token a GET address carries the identifier in, for both kinds. */
    public static final String ID_TOKEN = "id";

    private final String word;
    private final String token;
    private final String page;
    private final List<Field> fields;

    LookupKind(String word, String token, String page, List<Field> fields) {
        this.word = word;
        this.token = token;
        this.page = page;
        this.fields = fields;
    }

    /** "member" or "provider": the path segment of the settings endpoint and the key in lookups.json. */
    public String word() {
        return word;
    }

    /** The body template's token for the identifier, without braces. */
    public String token() {
        return token;
    }

    /** Where the settings are on screen. */
    public String page() {
        return page;
    }

    public List<Field> fields() {
        return fields;
    }

    /** The field of this name, or null. */
    public Field field(String name) {
        for (Field field : fields) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    /** The kind a path segment names, ignoring case, or null for any other word. */
    public static LookupKind parse(String word) {
        if (word == null) {
            return null;
        }
        String wanted = word.strip().toLowerCase(Locale.ROOT);
        for (LookupKind kind : values()) {
            if (kind.word.equals(wanted)) {
                return kind;
            }
        }
        return null;
    }
}
