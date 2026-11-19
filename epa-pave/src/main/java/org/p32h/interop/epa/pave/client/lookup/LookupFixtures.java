package org.p32h.interop.epa.pave.client.lookup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.model.Texts;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The synthetic members and providers the mock profile answers lookups from: one JSON file per
 * record under {@code fixtures/members} and {@code fixtures/providers} on the class path. A file
 * added to either directory is picked up with no change to any other file.
 *
 * <p><b>The mock profile is free of member data by construction, and this class is where that is
 * enforced.</b> A member fixture is refused unless its ID is a {@code P32H-} sentinel or a
 * placeholder in the {@code S123456789} style, and a provider fixture is refused unless its NPI
 * passes the application's own check. Every file is read when the application starts, and a file
 * that cannot be used stops the start and is named: a fixture that was quietly skipped would turn
 * into a "not found" nobody could explain.
 *
 * <p>These files are the whole of what the mock knows. Nothing that is looked up is added to it,
 * and nothing about a lookup is remembered.
 */
@Component
@Profile("mock")
public class LookupFixtures {

    /**
     * One file: an identifier and what a lookup API would answer for it.
     *
     * @param identifier the member ID in its no-space form, or the NPI
     * @param source     the file name, for the note on the wire view
     * @param httpStatus the status to answer with. Null unless the behavior is respond
     * @param body       the exact text to return, or null for no body
     * @param json       true when the body was written in the file as JSON and not as text
     */
    public record Fixture(String identifier, String source, String description, ScenarioBehavior behavior, Integer httpStatus,
                          String body, boolean json) {
    }

    static final String MEMBERS = "classpath*:fixtures/members/*.json";
    static final String PROVIDERS = "classpath*:fixtures/providers/*.json";

    /** This tool's own sentinel IDs. */
    static final Pattern SENTINEL = Pattern.compile("P32H-[A-Z0-9]+(?:-[A-Z0-9]+)*");
    /** The placeholder style: S and nine digits, or ten for an ID whose written form has a two digit suffix. */
    static final Pattern PLACEHOLDER = Pattern.compile("S\\d{9,10}");

    private static final String MEMBER_KEY = "memberId";
    private static final String PROVIDER_KEY = "npi";
    private static final Set<String> OTHER_KEYS = Set.of("description", "basis", "assumption", "expect", "behavior", "httpStatus",
            "response", "responseText");

    private final Map<String, Fixture> members;
    private final Map<String, Fixture> providers;

    public LookupFixtures() {
        this(MEMBERS, PROVIDERS);
    }

    /** For tests, which point at directories of their own. */
    LookupFixtures(String memberFiles, String providerFiles) {
        this.members = load(memberFiles, MEMBER_KEY);
        this.providers = load(providerFiles, PROVIDER_KEY);
    }

    /** The fixture for a member ID in its no-space form, or null when no file holds it. */
    public Fixture member(String sentMemberId) {
        return members.get(memberKey(sentMemberId));
    }

    /** The fixture for an NPI, or null when no file holds it. */
    public Fixture provider(String npi) {
        return providers.get(npi == null ? "" : npi.strip());
    }

    public Collection<Fixture> members() {
        return members.values();
    }

    public Collection<Fixture> providers() {
        return providers.values();
    }

    /** Sentinels are this tool's own identifiers, so a difference in letter case is forgiven, as the scenario selector forgives it. */
    private static String memberKey(String memberId) {
        return MemberIds.noSpace(memberId).toUpperCase(Locale.ROOT);
    }

    private static Map<String, Fixture> load(String pattern, String identifierKey) {
        Map<String, String> files = new TreeMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                try (InputStream in = resource.getInputStream()) {
                    files.put(resource.getFilename(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The lookup fixtures under " + pattern + " could not be read.");
        }
        Map<String, Fixture> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Fixture fixture;
            try {
                fixture = parse(file.getKey(), file.getValue(), identifierKey);
            } catch (IllegalArgumentException e) {
                // The message is this class's own and names the file and the rule. It quotes nothing from the file but its keys.
                throw new IllegalStateException("Lookup fixture " + file.getKey() + ": " + e.getMessage());
            }
            String key = identifierKey.equals(MEMBER_KEY) ? memberKey(fixture.identifier()) : fixture.identifier();
            Fixture earlier = out.putIfAbsent(key, fixture);
            if (earlier != null) {
                throw new IllegalStateException("Lookup fixture " + file.getKey() + ": its " + identifierKey + " is already used by "
                        + earlier.source() + ". One identifier returns one record.");
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Reads one fixture file. Package-private so the rules can be tested on text alone. */
    static Fixture parse(String source, String text, String identifierKey) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (!parsed.ok()) {
            throw new IllegalArgumentException(parsed.error());
        }
        JsonNode root = parsed.node();
        if (!root.isObject()) {
            throw new IllegalArgumentException("a fixture file must hold one JSON object.");
        }
        // A misspelt key would otherwise be dropped, and "respnse" would become an API that answers with nothing.
        List<String> unknown = JsonSafe.properties(root).stream().map(Map.Entry::getKey)
                .filter(key -> !key.equals(identifierKey) && !OTHER_KEYS.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("it has keys this tool does not know: " + String.join(", ", unknown) + ".");
        }
        String identifier = JsonSafe.text(root, identifierKey);
        if (Texts.blank(identifier)) {
            throw new IllegalArgumentException("it has no " + identifierKey + ".");
        }
        if (identifierKey.equals(MEMBER_KEY)) {
            if (!identifier.equals(MemberIds.noSpace(identifier))) {
                throw new IllegalArgumentException("its memberId must be written in the no-space form, which is the form that is sent.");
            }
            if (!SENTINEL.matcher(identifier).matches() && !PLACEHOLDER.matcher(identifier).matches()) {
                throw new IllegalArgumentException("its memberId is neither a P32H- sentinel nor a placeholder in the S123456789 "
                        + "style. Nothing that could be a real member ID may be served by the mock profile.");
            }
        } else if (!Npi.isValid(identifier)) {
            throw new IllegalArgumentException("its npi does not pass the NPI check, so no lookup could ever reach it. Make one with "
                    + "Npi.withCheckDigit.");
        }

        String behaviorText = JsonSafe.presentText(root, "behavior");
        ScenarioBehavior behavior;
        try {
            // A missing behavior means respond, as it does in a scenario file.
            behavior = behaviorText == null ? ScenarioBehavior.RESPOND : ScenarioBehavior.parse(behaviorText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("its behavior must be respond, timeout or unavailable.");
        }
        Integer status = JsonSafe.integer(root, "httpStatus");
        String body = null;
        boolean json = false;
        if (root.has("response") && root.has("responseText")) {
            throw new IllegalArgumentException("it has both a response and a responseText. Only one of them can be the body.");
        }
        if (behavior != ScenarioBehavior.RESPOND && (root.has("response") || root.has("responseText") || root.has("httpStatus"))) {
            throw new IllegalArgumentException("its behavior is " + behavior.wire() + ", which never answers, so it cannot have a "
                    + "status or a body.");
        }
        if (behavior == ScenarioBehavior.RESPOND) {
            if (status == null || status < 100 || status > 599) {
                throw new IllegalArgumentException("it responds but has no httpStatus that is an HTTP status.");
            }
            if (root.has("responseText")) {
                // For a body that is deliberately not JSON, such as an error page.
                body = JsonSafe.text(root, "responseText");
                if (body == null) {
                    throw new IllegalArgumentException("its responseText is not text.");
                }
            } else if (root.has("response") && !root.get("response").isNull()) {
                body = JsonSafe.pretty(root.get("response"));
                json = true;
            }
        } else {
            status = null;
        }
        return new Fixture(identifier.strip(), source, JsonSafe.presentText(root, "description"), behavior, status, body, json);
    }
}
