package org.p32h.interop.epa.pave.client.lookup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupPath;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;

/**
 * How one answer is read: the words its sentences use, and for a lookup saved on screen (R-11)
 * where its matches are and where a card field sits. A field with no saved path is read the usual
 * tolerant way, by the spellings of its mapping file.
 *
 * @param words      the words of every sentence about this lookup
 * @param listPath   where the matches are, or null for the usual places
 * @param fieldPaths card field name to the one spelling the tester saved for it
 * @param asked      the identifier as the lookup sent it, which a returned identifier may match as
 *                   well as the packed form (a lookup may send a member ID in its own form)
 */
record LookupReading(LookupWords words, List<Spelling.Step> listPath, Map<String, List<Spelling>> fieldPaths, String asked) {

    LookupReading {
        fieldPaths = fieldPaths == null ? Map.of() : Map.copyOf(fieldPaths);
    }

    /** application.yml's lookup, and the mock: the usual places, the usual spellings. */
    static LookupReading usual(LookupWords words) {
        return new LookupReading(words, null, Map.of(), null);
    }

    /** A lookup saved on screen. The paths were checked when they were saved and again when read back. */
    static LookupReading saved(LookupWords words, LookupKind kind, LookupSettings settings, String asked) {
        List<Spelling.Step> list = null;
        List<LookupPath.Step> listParts = LookupPath.parse(settings.listPath());
        if (listParts != null) {
            list = Spelling.ofPath(settings.listPath(), listParts).steps();
        }
        Map<String, List<Spelling>> fields = new LinkedHashMap<>();
        settings.fieldPaths().forEach((field, path) -> {
            List<LookupPath.Step> parts = LookupPath.parse(path);
            if (parts != null && kind.field(field) != null) {
                fields.put(field, List.of(Spelling.ofPath(path.strip(), parts)));
            }
        });
        return new LookupReading(words.onScreen(kind), list, fields, asked);
    }

    /** The spellings a field is read by: the saved path alone when there is one, else the usual list. */
    List<Spelling> spellings(String field, List<Spelling> usual) {
        List<Spelling> saved = fieldPaths.get(field);
        return saved == null ? usual : saved;
    }

    /** The one record, the matches, or why there is neither. */
    LookupEnvelope.Opened open(TrackedJson.Node root, List<String> wrappers) {
        return listPath == null ? LookupEnvelope.open(root, wrappers) : LookupEnvelope.at(root, listPath);
    }

    /** True when a returned member ID is the one asked for: packed as typed, or as the lookup sent it. */
    boolean sameMember(String returned, String packed) {
        String compact = MemberIds.noSpace(returned);
        return compact.equals(packed) || (Texts.present(asked) && compact.equals(MemberIds.noSpace(asked)));
    }

    /** Names the list path's presence and the fields with a saved path; a path is the tester's setting, not member data, but kept short. */
    @Override
    public String toString() {
        return "LookupReading[listPath=" + (listPath != null) + ", fieldPaths=" + fieldPaths.keySet() + "]";
    }
}
