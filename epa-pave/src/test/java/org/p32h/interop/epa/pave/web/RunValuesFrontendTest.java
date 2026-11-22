package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Values for one run (plan 3.2), run with Node and a stand-in for the document: a refusal that names
 * placeholders with no value gives one field each, a name the server reads as a secret masked, each
 * refusal sentence at its field; what is typed goes with the next run as "variables" and a blank value
 * is never sent; "Remove these values" puts the block away and nothing more is sent.
 */
class RunValuesFrontendTest {

    /** A refused run, the block, the next run and the removal, run. */
    @Test
    void aPlaceholderWithNoValueGetsAFieldAndWhatIsTypedGoesWithTheNextRun(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the values for one run", """
                import './browser.js';
                import {createRunValues, runValues, withRunValues, placeholdersIn, operationOf, looksSecret} from './js/wb-values.js';
                import {sendRun, busyLine} from './js/wb-run.js';
                import {el} from './js/dom.js';
                const say = value => console.log(JSON.stringify(value));
                const tick = () => new Promise(resolve => setTimeout(resolve, 20));
                const asked = [];
                let answers = {};
                globalThis.fetch = async (address, init) => {
                  asked.push(init.method + ' ' + address + (init.body ? ' ' + init.body : ''));
                  const found = answers[init.method + ' ' + address] || {status: 200, body: {runId: 'run-1', operation: 'CRD_ORDER_SIGN'}};
                  return {ok: found.status < 300, status: found.status, text: async () => JSON.stringify(found.body)};
                };
                const block = createRunValues({changed: () => null});
                document.body.append(block.node);
                // Before a refusal names a placeholder, the block is hidden and a run's body goes as it was.
                say([block.node.hidden, withRunValues({member: {memberId: 'S123456789'}}), runValues()]);
                // A refusal that names two placeholders with no value: one field each, the secret one masked, each refusal sentence at its field.
                answers['POST /api/crd'] = {status: 422, body: {kind: 'CONFIGURATION', title: 'The request cannot be sent: the placeholders {{fhirServer}}, {{clientSecret}} have no value.',
                  fieldErrors: [{field: 'variables.fhirServer', message: 'No value for {{fhirServer}}.'}, {field: 'variables.clientSecret', message: 'No value for {{clientSecret}}.'},
                    {field: 'member.memberId', message: 'Enter a member ID.'}]}};
                answers['POST /api/config/resolve'] = {status: 200, body: {variables: [{name: 'fhirServer', secret: false, usedIn: ['the body']},
                  {name: 'clientSecret', secret: true, usedIn: ['the header Authorization']}]}};
                const placed = [];
                const ctx = {needValues: (errors, operation) => block.need(errors, operation),
                  placeErrors: errors => errors.filter(each => block.show(each.field, each.message) || (placed.push(each.field) && true)).map(each => each.field)};
                const seen = [];
                const spec = () => ({path: '/api/crd', body: {hook: 'order-sign', member: {memberId: 'S123456789'}}, busy: busyLine(), busyWords: 'Running the check...',
                  buttons: [el('button')], typed: '', ctx, onResult: envelope => seen.push('result ' + envelope.runId),
                  onRefused: (error, names) => seen.push('refused ' + names.join(',')), onFailed: () => seen.push('failed')});
                await sendRun(spec());
                await tick();
                const inputs = block.node.querySelectorAll('input');
                say([block.node.hidden, inputs.map(input => [input.name, input.type]), placed, seen]);
                say(block.node.querySelectorAll('.field__error').map(line => line.textContent));
                say(block.node.querySelectorAll('.field__hint').map(line => line.textContent));
                say(asked.filter(line => line.startsWith('POST /api/config/resolve')));
                // What is typed goes with the next run as "variables"; a blank value is never sent as empty.
                inputs[0].value = 'https://fhir.example.invalid/r4';
                inputs[1].value = '';
                answers['POST /api/crd'] = {status: 200, body: {runId: 'run-2', operation: 'CRD_ORDER_SIGN'}};
                asked.length = 0;
                await sendRun(spec());
                say([asked[0], seen[seen.length - 1]]);
                // A second refusal for the same placeholder keeps what was typed and adds no second field.
                block.need([{field: 'variables.fhirServer', message: 'x'}], 'CRD_ORDER_SIGN');
                say([block.node.querySelectorAll('input').length, runValues()]);
                // "Remove these values" puts the block away and nothing more is sent.
                block.node.querySelectorAll('button').find(button => button.textContent === 'Remove these values').click();
                say([block.node.hidden, runValues(), withRunValues({a: 1})]);
                say([placeholdersIn([{field: 'variables.a'}, {field: 'variables.a'}, {field: 'member.memberId'}, {field: 'variables.'}, null]),
                  operationOf('/api/crd', {hook: 'order-select'}), operationOf('/api/dtr/next-question', {}), operationOf('/api/pas/subscriptions/abc', {}),
                  operationOf('/api/members', {})]);
                say(['clientSecret', 'client_secret', 'apiKey', 'tokenUrl', 'authType', 'fhirServer', 'payerName', 'sessionId', 'PASSWORD'].map(looksSecret));

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[true,{\"member\":{\"memberId\":\"S123456789\"}},{}]",
                "[false,[[\"variables.fhirServer\",\"text\"],[\"variables.clientSecret\",\"password\"]],[\"member.memberId\"],[\"refused variables.fhirServer,variables.clientSecret,member.memberId\"]]",
                "[\"No value for {{fhirServer}}.\",\"No value for {{clientSecret}}.\"]",
                "[\"Used in the body.\",\"Used in the header Authorization. Read as a secret, so what is typed is not shown.\"]",
                "[\"POST /api/config/resolve {\\\"operation\\\":\\\"CRD_ORDER_SIGN\\\"}\"]",
                "[\"POST /api/crd {\\\"hook\\\":\\\"order-sign\\\",\\\"member\\\":{\\\"memberId\\\":\\\"S123456789\\\"},\\\"variables\\\":{\\\"fhirServer\\\":\\\"https://fhir.example.invalid/r4\\\"}}\",\"result run-2\"]",
                "[2,{\"fhirServer\":\"https://fhir.example.invalid/r4\"}]",
                "[true,{},{\"a\":1}]",
                "[[\"a\"],\"CRD_ORDER_SELECT\",\"DTR_NEXT_QUESTION\",\"SUBSCRIPTION_CREATE\",null]",
                "[true,true,true,false,false,false,false,true,true]");
    }
}
