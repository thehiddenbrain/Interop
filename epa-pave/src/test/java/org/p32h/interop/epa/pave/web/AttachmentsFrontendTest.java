package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The files of a submission (wb-attachments.js), run with Node: the kinds and the limits come from
 * the server's answer, not the page (a kind the server does not list is refused even when it is a
 * kind PAVE usually takes); a refused file is not added and its line says why; a file that can no
 * longer be read when the request is sent refuses the send, so nothing goes; and each file goes as
 * {title, contentType, data, size}, its data the file's bytes in base64.
 */
class AttachmentsFrontendTest {

    @Test
    void kindsAndLimitsAreTheServersARefusedFileSendsNothingAndAFileGoesAsDataAndSize(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the attachments", """
                import './browser.js';
                const {createAttachments, admit} = await import('./js/wb-attachments.js');
                const say = value => console.log(JSON.stringify(value));
                const limits = {kinds: [{extension: 'pdf', contentType: 'application/pdf', label: 'PDF'},
                    {extension: 'png', contentType: 'image/png', label: 'PNG image'}], perFileBytes: 100, totalBytes: 150};
                const file = (name, text, gone) => ({name, size: text.length, type: '',
                    arrayBuffer: async () => { if (gone) { throw new Error('gone'); } return new TextEncoder().encode(text).buffer; }});
                let changes = 0;
                const block = createAttachments({limits: () => limits, changed: () => changes++});
                const input = block.node.querySelector('input');
                say(input.getAttribute('accept'));
                input.files = [file('note.pdf', 'PDF-placeholder'), file('scan.tif', 'x'), file('big.png', 'y'.repeat(120)),
                    file('more.png', 'z'.repeat(90)), file('last.png', 'w'.repeat(60))];
                await input.dispatchEvent({type: 'change'});
                say([block.count(), block.bytes(), block.node.querySelectorAll('.field__error').map(each => each.textContent), changes]);
                const read = await block.read();
                say([read.ok, read.attachments.map(each => [each.title, each.contentType, each.size, atob(each.data).length])]);
                say(atob(read.attachments[0].data));
                // A file that can no longer be read refuses the whole send.
                block.clear();
                input.files = [file('moved.pdf', 'abc', true)];
                await input.dispatchEvent({type: 'change'});
                const refused = await block.read();
                say([refused.ok, typeof refused.line === 'string' && refused.line.length > 0, 'attachments' in refused]);
                // Without the server's kinds nothing is added.
                say(admit(file('a.pdf', 'x'), {kinds: [], perFileBytes: 10, totalBytes: 10}, 0).line);
                // A file just over a limit that rounds to the same words is told in bytes, never "5 MB; the limit is 5 MB".
                say(admit({name: 'over-limit.pdf', size: 5242881}, {kinds: limits.kinds, perFileBytes: 5242880, totalBytes: 10485760}, 0).line);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "\".pdf,.png\"",
                "[2,105,[\"scan.tif: PAVE sends PDF, PNG, JPEG, TIFF, GIF, text and Word files. Not added.\","
                        + "\"big.png is 120 bytes; the limit is 100 bytes. Not added.\","
                        + "\"These files come to 165 bytes; the limit is 150 bytes in all. Not added.\"],1]",
                "[true,[[\"note\",\"application/pdf\",15,15],[\"more\",\"image/png\",90,90]]]",
                "\"PDF-placeholder\"",
                "[false,true,false]",
                "\"a.pdf: PAVE sends PDF, PNG, JPEG, TIFF, GIF, text and Word files. Not added.\"",
                "\"over-limit.pdf is 5242881 bytes; the limit is 5242880 bytes. Not added.\"");
    }
}
