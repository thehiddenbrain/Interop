package org.p32h.interop.epa.pave.service.dtr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;

/**
 * The questionnaire addresses this tool's own bundled scenarios name. They are this tool's own
 * vocabulary, as its bundled case ids and outcome codes are: the mock surnames are made of the same
 * placeholder words, so a typed address that is exactly one of these is never refused as a member's
 * name. Every other typed address is held to the member-name check like any other free text the
 * ledger keeps (round2-left 2).
 */
public final class BundledQuestionnaires {

    private static final String[] PROTOCOLS = {"crd", "dtr"};

    private BundledQuestionnaires() {
    }

    /** Read once: the bundled files are part of the jar and do not change while it runs. */
    private static final class Held {
        static final Set<String> ADDRESSES = read();
    }

    /** Every questionnaire address the bundled scenarios name, exactly as written, with and without the version a Questionnaire states. */
    public static Set<String> addresses() {
        return Held.ADDRESSES;
    }

    /** True when the address is exactly one the bundled scenarios name. Only the spaces around it are not part of it. */
    public static boolean isBundled(String address) {
        return address != null && Held.ADDRESSES.contains(address.strip());
    }

    private static Set<String> read() {
        Set<String> found = new TreeSet<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String protocol : PROTOCOLS) {
            Resource[] files;
            try {
                files = resolver.getResources("classpath*:scenarios/" + protocol + "/*.json");
            } catch (IOException e) {
                // Then no address is exempt, which holds every typed address to the check: the safe side.
                continue;
            }
            for (Resource file : files) {
                try (InputStream in = file.getInputStream()) {
                    JsonSafe.Parsed parsed = JsonSafe.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (parsed.ok()) {
                        collect(parsed.node(), found);
                    }
                } catch (IOException e) {
                    // A file that cannot be read names no address, so nothing it holds is exempt.
                }
            }
        }
        return Set.copyOf(found);
    }

    /**
     * Every text that is a url of a Questionnaire, and the url with its version of each
     * Questionnaire resource. Walked with a stack of its own, so a deep file cannot exhaust the
     * call stack.
     */
    static void collect(JsonNode root, Set<String> found) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isString()) {
                String text = node.stringValue();
                if (text.startsWith("http") && text.contains("/Questionnaire/")) {
                    found.add(text);
                }
            } else if (node.isObject()) {
                if ("Questionnaire".equals(JsonSafe.text(node, "resourceType"))) {
                    String url = JsonSafe.presentText(node, "url");
                    String version = JsonSafe.presentText(node, "version");
                    if (url != null && version != null) {
                        found.add(url + "|" + version);
                    }
                }
                for (Map.Entry<String, JsonNode> member : JsonSafe.properties(node)) {
                    pending.push(member.getValue());
                }
            } else if (node.isArray()) {
                for (JsonNode element : JsonSafe.elements(node)) {
                    pending.push(element);
                }
            }
        }
    }
}
