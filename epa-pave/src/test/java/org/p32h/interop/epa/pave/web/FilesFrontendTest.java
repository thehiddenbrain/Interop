package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;
import org.p32h.interop.epa.pave.web.StaticSources.Source;

/**
 * A chosen file is read in the page when the button that uses it is pressed, through files.js
 * alone (plan R-33), and every way the read fails is one plain line: the file changed, moved or
 * deleted after it was chosen; a file cut inside its last character; a file saved in another
 * encoding; a file too large or empty. A byte order mark of UTF-16 is decoded, and the file input
 * is cleared after every refusal, so the next press reads what is chosen again. files.js is run
 * with Node where it is installed; the rule that no other script reads a file holds without it.
 */
class FilesFrontendTest {

    /** The ways a script can read a file's content or post it as a form. Only files.js reads a file, and nothing posts one. */
    private static final Pattern READS_A_FILE = Pattern.compile(
            "\\b(?:FileReader|readAsText|readAsDataURL|readAsArrayBuffer|FormData)\\b|\\.arrayBuffer\\s*\\(");

    @Test
    void onlyFilesJsReadsAChosenFileAndNoScriptPostsOne() throws IOException {
        List<String> found = new ArrayList<>();
        for (Source script : StaticSources.sources(".js")) {
            if (!script.name().equals("js/files.js") && READS_A_FILE.matcher(StaticSources.withoutComments(script.text())).find()) {
                found.add(script.name());
            }
        }
        assertThat(found).as("scripts that read or post a file themselves").isEmpty();
        assertThat(READS_A_FILE.matcher("const reader = new FileReader();").find()).isTrue();
        assertThat(READS_A_FILE.matcher("const form = new FormData(); form.append('file', chosen);").find()).isTrue();
        assertThat(READS_A_FILE.matcher("const buffer = await chosen.arrayBuffer();").find()).isTrue();
        assertThat(READS_A_FILE.matcher("const read = await readText(input, {maxBytes});").find()).isFalse();
    }

    @Test
    void everyWayAReadFailsIsOneLineAndTheChosenFileIsLetGo(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "files.js", """
                import {readText, readBase64, sizeText} from './js/files.js';
                const say = value => console.log(JSON.stringify(value));
                const bytes = list => new Uint8Array(list);
                const utf8 = text => new TextEncoder().encode(text);
                const join = (...parts) => {
                    const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
                    let at = 0;
                    for (const part of parts) {
                        out.set(part, at);
                        at += part.length;
                    }
                    return out;
                };
                const utf16 = (text, bigEnd) => {
                    const out = new Uint8Array(2 + text.length * 2);
                    out[0] = bigEnd ? 0xFE : 0xFF;
                    out[1] = bigEnd ? 0xFF : 0xFE;
                    for (let i = 0; i < text.length; i++) {
                        const unit = text.charCodeAt(i);
                        out[2 + i * 2] = bigEnd ? unit >> 8 : unit & 0xFF;
                        out[3 + i * 2] = bigEnd ? unit & 0xFF : unit >> 8;
                    }
                    return out;
                };
                const file = (content, name, type) => new File([content], name, {type: type || ''});
                const input = chosen => ({files: chosen ? [chosen] : [], value: 'C:/fakepath/chosen'});
                const json = '{"name": "Caf' + String.fromCharCode(0xE9) + ' sandbox", "values": []}';
                const read = async (...args) => {
                    const answer = await readText(...args);
                    return answer.ok ? [answer.ok, answer.text === json, answer.fileName, answer.size] : answer;
                };

                say(await read(file(utf8(json), 'plain.json')));
                say(await read(file(join(bytes([0xEF, 0xBB, 0xBF]), utf8(json)), 'with-mark.json')));
                say(await read(file(utf16(json, false), 'powershell.json')));
                say(await read(file(utf16(json, true), 'big-end.json')));
                const whole = utf8(json);
                const cut = whole.slice(0, whole.indexOf(0xC3) + 1);
                const cutBox = input(file(cut, 'cut.json'));
                say([await read(cutBox), cutBox.value]);
                say(await read(file(utf16(json, false).slice(0, 9), 'odd.json')));
                const ansi = join(utf8('{"name": "Caf'), bytes([0xE9]), utf8(' sandbox", "values": []}'));
                const ansiBox = input(file(ansi, 'notepad.json'));
                say([await read(ansiBox), ansiBox.value]);
                say(await read(file(ansi, 'excel.csv'), {source: 'csv'}));
                say(await read(file(cut, 'cut.csv'), {source: 'csv'}));

                let reads = 0;
                const changed = {name: 'changed.json', size: 120, type: 'application/json',
                    arrayBuffer: async () => { reads++; throw new DOMException('The file changed.', 'NotReadableError'); }};
                const changedBox = input(changed);
                say([await read(changedBox), changedBox.value, reads]);
                const deleted = {name: 'deleted.json', size: 0,
                    arrayBuffer: async () => { throw new DOMException('A requested file could not be found.', 'NotFoundError'); }};
                const deletedBox = input(deleted);
                say([await read(deletedBox), deletedBox.value]);
                const huge = {name: 'huge.json', size: 25 * 1024 * 1024, arrayBuffer: async () => { reads++; return new ArrayBuffer(0); }};
                say([await read(huge), reads]);
                const justOver = {name: 'over.json', size: 20 * 1024 * 1024 + 1, arrayBuffer: async () => { reads++; return new ArrayBuffer(0); }};
                say([await read(justOver), reads]);
                say(await read(file(new Uint8Array(0), 'empty.json')));
                say([await read(input(null)), await read(null), await read({}), await read(42)]);

                const all = new Uint8Array(256);
                for (let i = 0; i < 256; i++) {
                    all[i] = i;
                }
                const attachment = await readBase64(file(all, 'scan.pdf', 'application/pdf'));
                say([attachment.ok, attachment.data === Buffer.from(all).toString('base64'), attachment.fileName, attachment.size, attachment.contentType]);
                const large = {name: 'scan.tiff', size: 6 * 1024 * 1024, arrayBuffer: async () => new ArrayBuffer(0)};
                const largeBox = input(large);
                say([await readBase64(largeBox, {maxBytes: 5 * 1024 * 1024}), largeBox.value]);
                say(await readBase64(file(new Uint8Array(0), 'blank.png', 'image/png')));
                const big = new Uint8Array(3 * 1024 * 1024 + 7);
                for (let i = 0; i < big.length; i++) {
                    big[i] = (i * 31) & 0xFF;
                }
                const bigRead = await readBase64(file(big, 'photo.png', 'image/png'), {maxBytes: 5 * 1024 * 1024});
                say([bigRead.ok, bigRead.data === Buffer.from(big).toString('base64'), bigRead.size]);
                say([sizeText(0), sizeText(1), sizeText(1023), sizeText(1024), sizeText(1260), sizeText(5 * 1024 * 1024), sizeText(1258291),
                    sizeText(20 * 1024 * 1024)]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // UTF-8 with and without its mark, and UTF-16 either way round by its mark, read to the same text.
                "[true,true,\"plain.json\",39]",
                "[true,true,\"with-mark.json\",42]",
                "[true,true,\"powershell.json\",78]",
                "[true,true,\"big-end.json\",78]",
                // Cut inside its last character, in UTF-8 and in UTF-16; the input is let go.
                "[{\"ok\":false,\"line\":\"This file is not complete. Export it again from Postman.\"},\"\"]",
                "{\"ok\":false,\"line\":\"This file is not complete. Export it again from Postman.\"}",
                // Saved in another encoding.
                "[{\"ok\":false,\"line\":\"notepad.json is saved in another encoding (for example by PowerShell or Notepad). Save it as UTF-8, "
                        + "or export it again from Postman.\"},\"\"]",
                // A CSV is never sent to Postman.
                "{\"ok\":false,\"line\":\"excel.csv is saved in another encoding (for example by Excel or Notepad). Save it as CSV UTF-8, then "
                        + "choose it again.\"}",
                "{\"ok\":false,\"line\":\"This file is not complete. Save it again, then choose it again.\"}",
                // Changed, moved or deleted after it was chosen: the browser cannot read it.
                "[{\"ok\":false,\"line\":\"The file you chose can no longer be read: it was moved, renamed, deleted or changed after you chose "
                        + "it. Choose it again.\"},\"\",1]",
                // Deleted after it was chosen: the browser reports a size of 0, and it is a file gone, not an empty one.
                "[{\"ok\":false,\"line\":\"The file you chose can no longer be read: it was moved, renamed, deleted or changed after you chose "
                        + "it. Choose it again.\"},\"\"]",
                // Too large: refused before it is read, by its size, and by its bytes where the rounded size would read as the limit.
                "[{\"ok\":false,\"line\":\"huge.json is 25 MB; PAVE reads exports up to 20 MB.\"},1]",
                "[{\"ok\":false,\"line\":\"over.json is 20,971,521 bytes; PAVE reads exports up to 20 MB.\"},1]",
                "{\"ok\":false,\"line\":\"empty.json is empty.\"}",
                // Nothing chosen, or something that is not a file: a line, never a throw.
                "[{\"ok\":false,\"line\":\"Choose a file first.\"},{\"ok\":false,\"line\":\"Choose a file first.\"},"
                        + "{\"ok\":false,\"line\":\"Choose a file first.\"},{\"ok\":false,\"line\":\"Choose a file first.\"}]",
                // An attachment: every byte, as base64, with its size and the browser's reading of its kind.
                "[true,true,\"scan.pdf\",256,\"application/pdf\"]",
                "[{\"ok\":false,\"line\":\"scan.tiff is 6 MB; the limit is 5 MB. Not added.\"},\"\"]",
                "{\"ok\":false,\"line\":\"blank.png is empty. Not added.\"}",
                "[true,true,3145735]",
                "[\"0 bytes\",\"1 byte\",\"1023 bytes\",\"1 KB\",\"1.2 KB\",\"5 MB\",\"1.2 MB\",\"20 MB\"]");
    }
}
