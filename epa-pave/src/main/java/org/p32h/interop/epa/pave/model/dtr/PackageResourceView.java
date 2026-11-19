package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * A resource that travelled with a questionnaire and is not itself a questionnaire or a response:
 * a CQL library, a value set, or anything else the package holds.
 *
 * @param foundAt  where in the response the resource sat
 * @param concepts for a value set, each concept it lists, in order. Empty for anything else
 * @param content  for a library, each piece of content it carries. Empty for anything else
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PackageResourceView(String foundAt, Shown resourceType, Shown id, Shown url, Shown version, Shown name, Shown title,
                                  Shown status, List<Shown> concepts, List<PackageContentView> content) {

    public PackageResourceView {
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
        content = content == null ? List.of() : List.copyOf(content);
    }
}
