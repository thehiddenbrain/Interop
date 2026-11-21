package org.p32h.interop.epa.pave.client.lookup;

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
 */
record LookupWords(String api, String lookup, String identifier, String record, String property) {

    static final LookupWords MEMBER = new LookupWords("The member API", "Member lookup", "member ID", "member", "lookup.member");
    static final LookupWords PROVIDER = new LookupWords("The provider directory", "Provider lookup", "NPI", "provider", "lookup.provider");

    /** What a tester does when a lookup gives nothing, for the middle of a sentence. */
    String byHand() {
        return "type the " + record + " details by hand";
    }

    /** The same, to start a sentence. */
    String byHandSentence() {
        return "Type the " + record + " details by hand.";
    }
}
