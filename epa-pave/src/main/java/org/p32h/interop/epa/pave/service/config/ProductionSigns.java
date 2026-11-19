package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanVariable;

/**
 * Looks for signs that an uploaded environment is a production environment.
 *
 * <p>The kind of an environment is declared at upload and never inferred, so these signs do not
 * decide anything. They are a second look: an environment that reads as production and was
 * declared as a sandbox is refused until the uploader confirms it, because production labelled as
 * a sandbox is the one mistake the write guardrail cannot recover from.
 */
final class ProductionSigns {

    /** A host with no scheme in front of it, such as api.example.org or api.example.org:8443/fhir. */
    private static final Pattern BARE_HOST = Pattern.compile("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d+)?(/.*)?");

    private ProductionSigns() {
    }

    /** What about the environment reads as production, each completing the sentence "It looks like production because ...". */
    static List<String> in(PostmanEnvironment environment) {
        List<String> signs = new ArrayList<>();
        if (saysProd(environment.name())) {
            signs.add("its name is \"" + environment.name() + "\"");
        }
        Set<String> named = new LinkedHashSet<>();
        // Disabled variables are looked at too. They are in the file, and the file is what is being judged.
        for (PostmanVariable variable : environment.variables()) {
            if (variable.hasValue() && looksLikeUrl(variable.value()) && saysProd(variable.value()) && named.add(variable.key())) {
                // The variable is named. Its value is not quoted, here or anywhere.
                signs.add("the variable " + variable.key() + " holds a url that contains \"prod\"");
            }
        }
        return signs;
    }

    private static boolean saysProd(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("prod");
    }

    static boolean looksLikeUrl(String value) {
        String text = value == null ? "" : value.strip();
        return text.contains("://") || text.toLowerCase(Locale.ROOT).startsWith("www.") || BARE_HOST.matcher(text).matches();
    }
}
