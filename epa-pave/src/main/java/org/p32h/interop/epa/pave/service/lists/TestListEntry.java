package org.p32h.interop.epa.pave.service.lists;

/** An entry of a saved test list: what the list keeps, finds and replaces it by. */
public interface TestListEntry {

    /** Minted by this tool when the entry was added. */
    String id();
}
