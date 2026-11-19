package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * One extension on a questionnaire, an item or a response, shown by its url and its value. A
 * questionnaire package is mostly extensions, so they are listed where they sit and do not fill
 * the list of fields nobody anticipated.
 *
 * @param value the value as returned. For an extension made of nested extensions, those as JSON
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtensionView(Shown url, Shown value) {
}
