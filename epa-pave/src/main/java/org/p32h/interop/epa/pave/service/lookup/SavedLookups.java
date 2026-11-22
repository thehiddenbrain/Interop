package org.p32h.interop.epa.pave.service.lookup;

/**
 * Where the live lookup clients ask for the lookup saved on screen (R-11). A question each time a
 * lookup is sent, so a save takes effect at the next lookup with nothing restarted.
 */
@FunctionalInterface
public interface SavedLookups {

    /** No lookup saved on screen: application.yml's lookups apply, as before. */
    SavedLookups NONE = kind -> null;

    /** The lookup saved for this kind and fit to send with, or null for application.yml's. */
    LookupSettings saved(LookupKind kind);
}
