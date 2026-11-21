package org.p32h.interop.epa.pave.client.lookup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.p32h.interop.epa.pave.model.MaskedReading;
import org.p32h.interop.epa.pave.model.VendorExchange;

/**
 * Where a mask fell on a value a lookup's result is read by, or on a name it finds its way by.
 *
 * <p>A lookup reads its answer by the spellings of its mapping, compared as {@link Spelling}
 * compares them, so the names are taken from those same tables and compared in the same way: a
 * second list of names would drift from the tables, and the tables are what is read.
 */
final class LookupMasks {

    private LookupMasks() {
    }

    /**
     * @param decidedBy the fields whose values the result is read by: an identifier compared with
     *                  the one asked for, a status or a yes or a no, a date compared with today.
     *                  The last name of each spelling is the name the value sits under
     * @param read      every field the mapping reads, each name of each spelling being a name it
     *                  finds its way by
     * @param wrappers  the names a record may be wrapped in, and those of an address object
     * @return null when no mask falls on any of them
     */
    static MaskedReading find(VendorExchange received, List<List<Spelling>> decidedBy, List<List<Spelling>> read, List<String> wrappers) {
        if (received == null || received.request() == null) {
            return null;
        }
        Set<String> values = new HashSet<>();
        for (List<Spelling> field : decidedBy) {
            for (Spelling spelling : field) {
                values.add(spelling.steps().get(spelling.steps().size() - 1).name());
            }
        }
        Set<String> names = new HashSet<>(values);
        for (List<Spelling> field : read) {
            for (Spelling spelling : field) {
                spelling.steps().forEach(step -> names.add(step.name()));
            }
        }
        wrappers.forEach(wrapper -> names.add(Spelling.normalise(wrapper)));
        // An error body is told apart by its resource type before any field is read.
        names.add(Spelling.normalise("resourceType"));
        values.add(Spelling.normalise("resourceType"));
        return MaskedReading.find(received.json(), received.request().secrets(), name -> values.contains(Spelling.normalise(name)),
                name -> names.contains(Spelling.normalise(name)));
    }
}
