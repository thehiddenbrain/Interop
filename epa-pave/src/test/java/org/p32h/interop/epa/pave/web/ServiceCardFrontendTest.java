package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The Service card sends quantity, unit, place of service and the Submit fields as the plan names
 * them (9.2), and nothing that was not given; the words of every choice are the server's (GET
 * /api/pas/options), and before it gives them a choice offers "Not stated" only; a code's description
 * is cut in the page; the fields the ledger keeps as typed carry the hint.
 */
class ServiceCardFrontendTest {

    /** The Service card, run. */
    @Test
    void theServiceIsSentAsThePlanNamesItWithTheServersWords(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Service card", """
                import './browser.js';
                import {createServiceCard} from './js/wb-service.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => JSON.stringify({codes: [{code: '95810', description: 'Polysomnography, sleep staging with four or more additional parameters', authRequired: 'REQUIRED', delegatedVendor: 'Placeholder vendor'}]})});
                const tick = () => new Promise(resolve => setTimeout(resolve, 20));
                let options = null;
                const card = createServiceCard({lineOfBusiness: () => '', options: () => options});
                document.body.append(card.node);
                // Before the server gives its words, every choice offers "Not stated" only.
                const selects = () => card.node.querySelectorAll('select').map(each => each.textContent);
                say(selects());
                options = {levelsOfService: [{code: 'U', label: 'Urgent'}], certificationTypes: [{code: 'I', label: 'Initial'}],
                  requestTypes: [{code: 'HS', label: 'Health services review'}], serviceTypes: [{code: '1', label: 'Medical care'}],
                  placesOfService: [{code: '11', label: 'Office'}], units: [{code: 'VS', label: 'Visits'}]};
                card.useCodes([{code: '95810'}, {code: 'E0470'}]);
                card.drawOptions();
                await tick();
                say(selects());
                say(card.node.querySelectorAll('datalist option').map(each => [each.value, each.label]));
                // The description is cut in the page to its first 40 characters, whole in its title.
                const line = card.node.querySelector('.code-line__text');
                say([line.textContent, line.title]);
                // Quantity, unit, place of service and the Submit fields are sent as the plan names them; nothing not given is sent.
                say(card.request());
                const set = (name, value) => { const input = card.node.querySelector('[data-case-field="' + name + '"] input, [data-case-field="' + name + '"] select');
                  input.value = value; input.dispatchEvent({type: 'input'}); };
                const rows = card.node.querySelectorAll('.per-code__row');
                const qty = rows[0].querySelector('input'); qty.value = '2'; qty.dispatchEvent({type: 'input'});
                const unit = rows[0].querySelector('select'); unit.value = 'VS'; unit.dispatchEvent({type: 'change'});
                set('placeOfService', '11'); set('levelOfService', 'U'); set('certificationType', 'I'); set('requestType', 'HS'); set('serviceType', '1');
                set('dateOfService', '2026-11-23'); set('diagnosisCodes', 'Z80.3, G47.33'); set('testerRef', 'T-1');
                say(card.request());
                // The hint under the fields the ledger keeps as typed.
                say(card.node.querySelectorAll('[data-case-field="testerRef"] .field__hint, [data-case-field="note"] .field__hint').map(each => each.textContent));

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"Not stated\",\"Not stated\",\"Not stated\"]",
                "[\"Not statedVisits\",\"Not statedVisits\",\"Not statedU Urgent\",\"Not statedI Initial\",\"Not statedHS Health services review\"]",
                "[[\"11\",\"11 Office\"],[\"1\",\"1 Medical care\"]]",
                "[\"Polysomnography, sleep staging with four...\",\"Polysomnography, sleep staging with four or more additional parameters\"]",
                "{\"procedureCodes\":[{\"code\":\"95810\"},{\"code\":\"E0470\"}]}",
                "{\"procedureCodes\":[{\"code\":\"95810\",\"quantity\":2,\"unit\":\"VS\"},{\"code\":\"E0470\"}],\"dateOfService\":\"2026-11-23\",\"diagnosisCodes\":[\"Z80.3\",\"G47.33\"],\"placeOfService\":\"11\",\"levelOfService\":\"U\",\"certificationType\":\"I\",\"requestType\":\"HS\",\"serviceType\":\"1\",\"testerRef\":\"T-1\"}",
                "[\"Kept in the ledger as typed. Never type member data here.\",\"Kept in the ledger as typed. Never type member data here.\"]");
    }
}
