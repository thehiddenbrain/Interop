package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * One piece of content a Library in the package carries, such as its CQL source.
 *
 * <p>The encoded bytes are described by their size and not repeated here. They can run to
 * hundreds of kilobytes, they are in the wire view as received (a place repeating a secret the
 * request carried is masked there, as everywhere), and a tester reading the
 * result needs to know that the logic arrived, not to read it in base64.
 *
 * @param dataPresence   whether the content carried inline data at all, and if so whether it was empty
 * @param dataCharacters how many characters of encoded data arrived, or null when there were none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PackageContentView(Shown contentType, Shown url, Presence dataPresence, Integer dataCharacters) {
}
