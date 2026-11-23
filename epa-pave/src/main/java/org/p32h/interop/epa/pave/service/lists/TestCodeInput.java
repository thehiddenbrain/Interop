package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;
import org.p32h.interop.epa.pave.model.Texts;

/** A saved procedure code or diagnosis code as the form or a CSV row gives it, without the id this tool mints. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"label", "code", "system", "description"})
public record TestCodeInput(String label, String code, String system, String description) {

    /** Spaces at the ends are not part of a value, and a blank value is no value. */
    public TestCodeInput {
        label = Texts.trimToNull(label);
        code = Texts.trimToNull(code);
        system = Texts.trimToNull(system);
        description = Texts.trimToNull(description);
    }

    static TestCodeInput of(Map<String, String> values) {
        return new TestCodeInput(values.get("label"), values.get("code"), values.get("system"), values.get("description"));
    }

    TestCode withId(String id) {
        return new TestCode(id, label, code, system, description);
    }

    @Override
    public String toString() {
        return "TestCodeInput[values=" + withId(null).values().size() + "]";
    }
}
