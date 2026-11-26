package org.p32h.interop.epa.pave.client.lookup;

import org.p32h.interop.epa.pave.service.lookup.LookupKind;

/**
 * The words that differ between the two lookups. Every sentence about a member lookup and about a
 * provider lookup is built from one template with these filled in, so the two cannot drift apart
 * in what they say about the same state.
 *
 * @param api        the far side, as the subject of a sentence
 * @param lookup     the lookup, as the subject of a sentence
 * @param identifier what is looked up
 * @param record     what comes back
 * @param property   the prefix of this lookup's settings in application.yml
 * @param where      for a lookup saved on screen (R-11), the lookup and its page, such as "the member
 *                   lookup in Configuration > Members", so a sentence names the setting the tester
 *                   can change there; null for application.yml's lookup
 */
record LookupWords(String api, String lookup, String identifier, String record, String property, String where) {

    static final LookupWords MEMBER = new LookupWords("The member API", "Member lookup", "member ID", "member", "lookup.member", null);
    static final LookupWords PROVIDER = new LookupWords("The provider directory", "Provider lookup", "NPI", "provider", "lookup.provider",
            null);

    /** The same words for the lookup saved on screen. */
    LookupWords onScreen(LookupKind kind) {
        return new LookupWords(api, lookup, identifier, record, property, "the " + kind.word() + " lookup in " + kind.page());
    }

    boolean saved() {
        return where != null;
    }

    /** What a tester does when a lookup gives nothing, for the middle of a sentence. */
    String byHand() {
        return "type the " + record + " details by hand";
    }

    /** The same, to start a sentence. */
    String byHandSentence() {
        return "Type the " + record + " details by hand.";
    }

    /** The setting that holds the address, for the middle of a sentence. */
    String urlSetting() {
        return where == null ? property + ".url" : "the Address of " + where;
    }

    /** The setting that holds the headers. */
    String headersSetting() {
        return where == null ? property + ".headers" : "the Headers of " + where;
    }

    /** Why a lookup is switched off, in brackets. */
    String switchedOffWhere() {
        return where == null ? "(" + property + ".enabled is false)" : "(Lookup on is not ticked for " + where + ")";
    }

    /** What to check when the far side refused the credentials, before "Until then". */
    String refusedCredentialsAdvice() {
        return where == null ? "Check " + property + ".headers, and if this API takes the Onyx access token, " + property
                + ".use-onyx-auth and the onyx.auth settings."
                : "Check Sign in with and the Headers of " + where + ".";
    }

    /** What to check when no access token could be had, before "Until then". */
    String noTokenAdvice() {
        return where == null ? "Check the onyx.auth settings and the credentials in the active environment, or set " + property
                + ".use-onyx-auth to false if this API does not take the Onyx access token."
                : "Check Sign in with of " + where + ", and for the active environment's sign-in, Sign-in in Configuration > Environments.";
    }
}
