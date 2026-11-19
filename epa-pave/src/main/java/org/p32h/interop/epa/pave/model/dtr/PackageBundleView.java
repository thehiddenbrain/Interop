package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One bundle of a questionnaire package: a questionnaire travelling with the libraries, value sets
 * and answers that belong to it. Everything found inside it says where it sat, so the screen can
 * group a questionnaire with what came with it.
 *
 * @param foundAt    where in the response the bundle sat
 * @param entryCount how many entries it holds
 * @param fullUrls   the fullUrl of each entry that states one, in order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PackageBundleView(String foundAt, Shown type, Shown id, int entryCount, List<Shown> fullUrls) {

    public PackageBundleView {
        fullUrls = fullUrls == null ? List.of() : List.copyOf(fullUrls);
    }
}
