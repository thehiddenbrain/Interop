package com.thehiddenbrain.interop.epa.pave.model;

import java.util.List;
import java.util.Locale;

/**
 * The delegated utilization management vendors currently in use, and how a name is matched to one.
 *
 * <p>A name is matched ignoring case, spaces and punctuation, so "EVICORE" and "Optum PostAcute"
 * are recognised. A name that matches none of them is not corrected or dropped: it is passed
 * through as written, because a vendor this tool does not know is exactly what a tester needs to see.
 */
public final class DelegatedVendors {

    public static final List<String> CURRENT = List.of("eviCore", "Evolent", "Carelon", "Optum Post Acute");

    private DelegatedVendors() {
    }

    /** The canonical spelling of a known vendor, or the stripped text as given when it is not a known one. Null for blank. */
    public static String canonical(String name) {
        if (Texts.blank(name)) {
            return null;
        }
        String wanted = key(name);
        for (String vendor : CURRENT) {
            if (key(vendor).equals(wanted)) {
                return vendor;
            }
        }
        return name.strip();
    }

    /** True when the name is one of the current vendors. */
    public static boolean known(String name) {
        return Texts.present(name) && CURRENT.contains(canonical(name));
    }

    /** True when two names refer to the same vendor. Two blanks are not the same vendor. */
    public static boolean same(String a, String b) {
        return Texts.present(a) && Texts.present(b) && key(a).equals(key(b));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
