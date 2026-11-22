package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A saved procedure code: a code the vendor's sandbox answers for, kept at hand to fill the Service
 * card. Every value is as the tester gave it, or as the collection it was read from wrote it.
 *
 * @param id          minted by this tool when the entry was added
 * @param system      the code system as written, sent with the code; absent when not stated
 * @param description what the code stands for, shown beside it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "label", "code", "system", "description"})
public record TestCode(String id, String label, String code, String system, String description) implements TestListEntry {

    /** The columns of a procedure codes CSV file, in the order the template writes them. */
    public static final List<String> COLUMNS = List.of("label", "code", "system", "description");

    Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        TestMember.put(values, "label", label);
        TestMember.put(values, "code", code);
        TestMember.put(values, "system", system);
        TestMember.put(values, "description", description);
        return values;
    }

    /** The id only, as every list prints its entries. */
    @Override
    public String toString() {
        return "TestCode[id=" + id + "]";
    }
}
