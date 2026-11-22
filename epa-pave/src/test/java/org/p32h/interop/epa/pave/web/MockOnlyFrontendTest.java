package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The mock member groups and "Mock scenario" are built on the mock profile only (plan section 6 and
 * the hard rule): run with Node, the builders are given the scenario lists in hand with mode live and
 * build nothing of them, and given mode mock they build the groups by what each member plays.
 */
class MockOnlyFrontendTest {

    /** The member card's picker and the coverage check's options, on each profile. */
    @Test
    void withModeLiveAndTheListsInHandNoMockMemberAndNoMockScenarioIsBuilt(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the mock-only builders", """
                import './browser.js';
                import {mockGroups, createMemberCard} from './js/wb-member.js';
                import {mount} from './js/wb-crd.js';
                import {el} from './js/dom.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.fetch = async () => ({ok: false, status: 404, text: async () => ''});
                const list = scenarios => ({available: true, scenarios});
                const lists = {
                  crd: list([{id: 'crd-04-doc-dtr', number: 4, label: 'Documentation required', short: 'documentation required', sentinelMemberId: 'P32H-DOC-DTR',
                    sampleCase: {npi: '1234567893', procedureCodes: ['E0470']}}, {id: 'crd-99', number: 99, label: 'No member', sentinelMemberId: null}]),
                  dtr: list([{id: 'dtr-01', number: 1, short: 'linear questionnaire', sentinelMemberId: 'P32H-DOC-DTR'},
                    {id: 'dtr-03', number: 3, short: 'prefilled answers', sentinelMemberId: 'P32H-DTR-PREPOP'}]),
                  pas: list([{id: 'pas-01', number: 1, label: 'Approved', sentinelMemberId: 'P32H-PAS-APPROVED'}])
                };
                say(mockGroups(true, lists).map(group => [group.label, group.options.map(option => option.text)]));
                // With mode live and the lists in hand, nothing of them is built.
                say([mockGroups(false, lists), mockGroups('mock', lists), mockGroups(true, null)]);
                const card = mock => createMemberCard({isMock: () => mock, mockLists: () => lists, savedMembers: () => [], dateOfService: () => '', useSample: () => null});
                const live = card(false).node;
                say([live.textContent.includes('P32H-'), live.querySelectorAll('optgroup').length, live.textContent.includes('Mock member'),
                  live.querySelector('select').hidden]);
                const mocked = card(true).node;
                say([mocked.querySelectorAll('optgroup').map(group => group.label), mocked.textContent.includes('Mock member, named by what it plays')]);
                // "Mock scenario" of the coverage check: built on mock only, whatever the lists say on live.
                const ctx = mock => ({isMock: () => mock, crdScenarios: () => lists.crd, request: () => ({}), blocked: () => null, readinessLine: () => null,
                  latest: () => null, card: () => null, typedCode: () => '', placeErrors: () => [], refused: () => null, resulted: () => null});
                const liveRoot = el('section');
                mount(liveRoot, ctx(false));
                const mockRoot = el('section');
                mount(mockRoot, ctx(true));
                say([liveRoot.textContent.includes('Mock scenario'), liveRoot.querySelectorAll('#crd-scenario').length,
                  mockRoot.textContent.includes('Mock scenario'), mockRoot.querySelector('#crd-scenario').textContent]);

                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"Coverage check\",[\"P32H-DOC-DTR \u00b7 documentation required\"]],[\"Questionnaire\",[\"P32H-DTR-PREPOP \u00b7 prefilled answers\"]],[\"Submit and status\",[\"P32H-PAS-APPROVED \u00b7 Approved\"]]]",
                "[[],[],[]]",
                "[false,0,false,true]",
                "[[\"Coverage check\",\"Questionnaire\",\"Submit and status\"],true]",
                "[false,0,true,\"The member ID decides4. documentation required99. No member\"]");
    }
}
