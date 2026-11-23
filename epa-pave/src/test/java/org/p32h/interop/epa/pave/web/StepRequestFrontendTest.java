package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The Request line of a Workbench step (D-313), run with Node and the stand-in for the document:
 * the request in force by its name and folder and where it comes from, the list of every request
 * that fits with the one in force and a sample of an error marked, a request chosen there going
 * with the step's runs and previews, the sentence for a typed address, and "Use for every run" as
 * the mappings the form's pick makes.
 */
class StepRequestFrontendTest {

    private static final String EFFECTIVE = """
            const request = (id, name, folders, url, extra) => Object.assign({id, name, folderPath: folders, method: 'POST', urlTemplate: url}, extra || {});
            const effective = (typed) => ({operations: [{operation: 'CRD_ORDER_SIGN', label: 'CRD order-sign',
              target: typed ? {display: 'https://typed.example.invalid/cds-services/order-sign', typed: true, source: 'the address typed for CRD order-sign'}
                : {display: 'https://other.example.invalid/other/cds-services/order-sign', requestName: 'Coverage check', source: 'the request'},
              requests: [
                {collectionId: 'c1', collectionName: 'Other vendor', usedByDefault: !typed, request: request('r1', 'Coverage check', ['Other', 'CRD'], '{{base}}/other/cds-services/order-sign')},
                {collectionId: 'c1', collectionName: 'Other vendor', usedByDefault: false, errorSample: true, request: request('r2', 'Coverage check error no patient', ['Other', 'CRD'], '{{base}}/other/cds-services/order-sign')},
                {collectionId: 'c2', collectionName: 'Vendor', usedByDefault: false, request: request('r3', 'Coverage check', ['Vendor', 'CRD'], '{{base}}/epa/cds-services/order-sign?x=1')}]},
              {operation: 'PAS_SUBMIT', label: 'PAS submit', target: {source: 'application.yml: onyx.pas.url followed by /Claim/$submit'}, requests: []},
              {operation: 'PAS_INQUIRE', label: 'PAS inquire', requests: [{collectionId: 'c2', collectionName: 'Vendor', usedByDefault: true,
                request: request('r9', 'Inquiry', [], '{{base}}/Claim/$inquire', {correctedOperation: 'PAS_INQUIRE'})}]}]});
            """;

    @Test
    void theLineNamesTheRequestInForceTheListChoosesAnotherAndTheChoiceGoesWithTheRun(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the Request line", """
                import './browser.js';
                import {createRequestLine, useEffective, withChosenRequest, chosenFor, addressEnd, inForce} from './js/wb-request.js';
                const say = value => console.log(JSON.stringify(value));
                const text = node => node.textContent.replace(/\\s+/g, ' ').trim();
                """ + EFFECTIVE + """
                const line = createRequestLine({operation: () => 'CRD_ORDER_SIGN', label: 'the coverage check', environment: () => 'Vendor UAT'});
                document.body.append(line.summary, line.node);
                useEffective(effective(false));
                // In force: the first that fits, by folder and name, with the end of its address.
                say(text(line.node.querySelector('.request-line__head')));
                say(text(line.summary));
                // Change opens the list: three requests, the one in force and the sample of an error marked.
                line.node.querySelectorAll('button').find(each => each.textContent === 'Change').click();
                const items = line.node.querySelectorAll('.request-list__item');
                say(items.map(text));
                // Nothing chosen yet: a run goes as it was.
                say(withChosenRequest('/api/crd', {hook: 'order-sign'}));
                // The second coverage check is chosen: the line says so, and every run and preview of the step names it.
                items[2].click();
                say(text(line.node.querySelector('.request-line__head')));
                say([withChosenRequest('/api/crd', {hook: 'order-sign'}), withChosenRequest('/api/crd/preview', {hook: 'order-sign'}).request,
                  withChosenRequest('/api/crd', {hook: 'order-select'}), withChosenRequest('/api/members', {a: 1})]);
                say(line.node.querySelectorAll('button').map(each => each.textContent));
                // No address typed: no sentence about one.
                say(line.node.querySelectorAll('.request-line__note').length);
                // An address typed in the settings: in force when nothing is chosen, and a chosen request goes to its own address, said.
                useEffective(effective(true));
                say(text(line.node.querySelector('.request-line__note')));
                line.node.querySelectorAll('button').find(each => each.textContent === 'Use the request in force').click();
                say([chosenFor('CRD_ORDER_SIGN'), inForce('CRD_ORDER_SIGN').kind, text(line.node.querySelector('.request-line__head'))]);
                // One request, or none, offers no list and says so.
                const one = createRequestLine({operation: 'PAS_INQUIRE', label: 'the inquiry'});
                const none = createRequestLine({operation: 'PAS_SUBMIT', label: 'Submit'});
                say([text(one.node), one.node.querySelectorAll('button').length, one.summary.hidden]);
                say([text(none.node), none.node.querySelectorAll('button').length]);
                say([addressEnd('{{base}}/epa/cds-services/order-sign?x=1'), addressEnd('https://h.example.invalid/a'), addressEnd('')]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "\"Request Other / CRD / Coverage check POST .../other/cds-services/order-sign the first in the file that fits Change\"",
                "\"Request: \\\"Other / CRD / Coverage check\\\", the first in the file that fits. 3 requests of the stored collections fit this step;"
                        + " choose another under Options.\"",
                "[\"Other / CRD / Coverage check POST .../other/cds-services/order-sign Other vendor in force\","
                        + "\"Other / CRD / Coverage check error no patient POST .../other/cds-services/order-sign Other vendor sample of an error\","
                        + "\"Vendor / CRD / Coverage check POST .../epa/cds-services/order-sign Vendor\"]",
                "{\"hook\":\"order-sign\"}",
                "\"Request Vendor / CRD / Coverage check POST .../epa/cds-services/order-sign chosen here, for this page Change\"",
                "[{\"hook\":\"order-sign\",\"request\":{\"collectionId\":\"c2\",\"requestId\":\"r3\"}},{\"collectionId\":\"c2\",\"requestId\":\"r3\"},"
                        + "{\"hook\":\"order-select\"},{\"a\":1}]",
                "[\"Change\",\"Use for every run\",\"Use the request in force\"]",
                "0",
                "\"This run goes to the request's own address, not the one typed in the settings.\"",
                "[null,\"typed\",\"Request The address typed in the settings https://typed.example.invalid/cds-services/order-sign Change\"]",
                "[\"Request Inquiry POST .../Claim/$inquire the one chosen in Configuration It is the only request of the stored collections that"
                        + " fits this step.\",0,true]",
                "[\"Request application.yml: onyx.pas.url followed by /Claim/$submit No request of the stored collections fits this step, so there"
                        + " is none to choose.\",0]",
                "[\".../epa/cds-services/order-sign\",\".../a\",\"\"]");
    }

    @Test
    void useForEveryRunTakesTheEarlierFitsOffTheOperationAndMapsTheChosenOne(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "Use for every run", """
                import './browser.js';
                import {createRequestLine, useEffective, chosenFor} from './js/wb-request.js';
                const say = value => console.log(JSON.stringify(value));
                const text = node => node.textContent.replace(/\\s+/g, ' ').trim();
                const tick = () => new Promise(resolve => setTimeout(resolve, 20));
                const asked = [];
                globalThis.fetch = async (address, init) => {
                  asked.push(init.method + ' ' + address + (init.body ? ' ' + init.body : ''));
                  return {ok: true, status: 200, text: async () => JSON.stringify({changed: true, notes: [], epoch: 9})};
                };
                """ + EFFECTIVE + """
                for (const typed of [false, true]) {
                  asked.length = 0;
                  const line = createRequestLine({operation: () => 'CRD_ORDER_SIGN', label: 'the coverage check', environment: () => 'Vendor UAT'});
                  document.body.append(line.node);
                  useEffective(effective(typed));
                  if (line.node.querySelectorAll('button').some(each => each.textContent === 'Change')) {
                    line.node.querySelectorAll('button').find(each => each.textContent === 'Change').click();
                  }
                  line.node.querySelectorAll('.request-list__item')[2].click();
                  line.node.querySelectorAll('button').find(each => each.textContent === 'Use for every run').click();
                  await tick();
                  // The sample of an error before it is left mapped: it never stands for the operation.
                  say(asked);
                  say([text(line.node.querySelector('.request-line__said')), line.node.querySelectorAll('.request-line__said a').map(a => a.href),
                    chosenFor('CRD_ORDER_SIGN')]);
                  line.node.remove();
                }
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"PUT /api/config/collections/c1/mapping {\\\"requestId\\\":\\\"r1\\\",\\\"operation\\\":\\\"UNMAPPED\\\"}\","
                        + "\"PUT /api/config/collections/c2/mapping {\\\"requestId\\\":\\\"r3\\\",\\\"operation\\\":\\\"CRD_ORDER_SIGN\\\"}\"]",
                "[\"Every run of CRD order-sign that names no request now uses \\\"Vendor / CRD / Coverage check\\\", and the request before it in"
                        + " the file no longer stands for it.\",[],null]",
                "[\"PUT /api/config/collections/c1/mapping {\\\"requestId\\\":\\\"r1\\\",\\\"operation\\\":\\\"UNMAPPED\\\"}\","
                        + "\"PUT /api/config/collections/c2/mapping {\\\"requestId\\\":\\\"r3\\\",\\\"operation\\\":\\\"CRD_ORDER_SIGN\\\"}\"]",
                "[\"Every run of CRD order-sign that names no request now uses \\\"Vendor / CRD / Coverage check\\\", and the request before it in"
                        + " the file no longer stands for it. The address typed in the settings for CRD order-sign still wins until it is taken away:"
                        + " Edit.\",[\"#configuration/environments/Vendor%20UAT/addresses\"],{\"collectionId\":\"c2\",\"requestId\":\"r3\"}]");
    }
}
