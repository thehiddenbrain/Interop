package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.crd.CardView;
import org.p32h.interop.epa.pave.model.crd.SuggestionView;
import org.p32h.interop.epa.pave.model.dtr.ItemView;
import org.p32h.interop.epa.pave.model.pas.AdjudicationView;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasItemView;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The response detail (detail.js), the part of a result that says what the tool understood in an
 * answer. Where a view states a value or heads a list, it says whether the service did not return
 * it, returned it as null or returned it empty, from the presence the server sends; a renamed
 * property or a script that goes back to a yes-or-no flag fails here. Run with Node where it is
 * installed: the delegated vendor and the decision for every item stay in view inside the fold,
 * not behind a further one; a value a service wrote is text, never markup; a list nested as deep as
 * a service likes is drawn without overflowing; a returned field is withheld when the wire view
 * masked a secret; and a protocol with no detail of its own draws none.
 */
class DetailFrontendTest {

    /** One property detail.js reads: the text of the read, the record the server sends, and the property. */
    private record Read(String text, Class<?> record, String property) {
    }

    private static final List<Read> READS = List.of(
            new Read("card.source", CardView.class, "source"),
            new Read("card.lists", CardView.class, "lists"),
            new Read("suggestion.lists", SuggestionView.class, "lists"),
            new Read("response.preAuthPeriod", ClaimResponseView.class, "preAuthPeriod"),
            new Read("response.lists", ClaimResponseView.class, "lists"),
            new Read("decision.authorizedPeriod", PasDecision.class, "authorizedPeriod"),
            new Read("item.lists", PasItemView.class, "lists"),
            new Read("item.lists", ItemView.class, "lists"),
            new Read("adjudication.lists", AdjudicationView.class, "lists"),
            new Read("item.sentWithNoValue", org.p32h.interop.epa.pave.model.crd.CoverageInformationView.class, "sentWithNoValue"),
            new Read("item.delegatedVendor", org.p32h.interop.epa.pave.model.crd.CoverageInformationView.class, "delegatedVendor"));

    @Test
    void everyPresenceTheDetailDrawsIsOnTheRecordTheServerSends() throws IOException {
        String script = StaticSources.script("detail.js");
        for (Read read : READS) {
            assertThat(hasProperty(read.record(), read.property()))
                    .as(read.record().getSimpleName() + " has " + read.property() + ", which detail.js reads").isTrue();
            assertThat(script).as("detail.js reads " + read.text()).contains(read.text());
        }
        // The rule sees a property that is not there.
        assertThat(hasProperty(DeterminationRow.class, "noSuchPresence")).isFalse();
    }

    @Test
    void theVendorAndEveryItemDecisionStayInViewAndEveryAbsenceHasItsWords(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the response detail", """
                import './browser.js';
                globalThis.fetch = async () => ({ok: true, status: 200, text: async () => JSON.stringify({outcomes: {
                    CRD: [{code: 'PA_REQUIRED', label: 'Prior authorization required', tone: 'ACTION'}],
                    PAS: [{code: 'APPROVED', label: 'Approved', tone: 'CLEAR'}, {code: 'DENIED', label: 'Denied', tone: 'STOP'}]}})});
                const {responseDetail, detailHint} = await import('./js/detail.js');
                const say = value => console.log(JSON.stringify(value));
                const v = value => ({presence: 'VALUE', value});
                const inClosedFold = node => {
                    for (let at = node.parentNode; at; at = at.parentNode) {
                        if (at.tagName === 'DETAILS' && !at.open) {
                            return true;
                        }
                    }
                    return false;
                };
                const find = (root, selector) => root.querySelectorAll(selector);
                const settle = () => new Promise(done => setTimeout(done, 20));

                const crd = {protocol: 'CRD', operation: 'CRD_ORDER_SIGN', detail: {responseKind: 'CDS_HOOKS',
                    cardsPresence: 'EMPTY', cards: [], systemActionsPresence: 'ABSENT', systemActions: [],
                    coverageInformation: [{foundAt: 'cards[0].extension[0]', appliesTo: {presence: 'ABSENT'}, procedureCodes: ['81162'],
                        subject: '81162', outcome: 'PA_REQUIRED', covered: {presence: 'VALUE', code: 'covered', display: 'Covered'},
                        paNeeded: {presence: 'NULL'}, docNeeded: null, docPurpose: [], infoNeeded: [],
                        satisfiedPaId: {presence: 'EMPTY'}, coverage: {presence: 'ABSENT'}, coverageAssertionId: v('<b>P32H-CA-1</b>'),
                        date: {presence: 'ABSENT'}, questionnaires: [], responses: [], reasons: [], details: [],
                        delegatedVendor: 'Vendor Q', delegatedVendorBasis: 'the coverage assertion id', otherSubExtensions: [],
                        sentWithNoValue: ['date']}], issues: [], requestNotes: ['One note.'], bodyBuiltByTool: true}};
                const crdNode = responseDetail(crd);
                await settle();
                const vendor = find(crdNode, '.detail__vendor')[0];
                say([vendor.textContent, inClosedFold(vendor)]);
                const text = crdNode.textContent;
                say([text.includes('returned as null'), text.includes('returned empty'), text.includes('sent with no value'),
                    text.includes('<b>P32H-CA-1</b>'), find(crdNode, 'b').length, text.includes('Prior authorization required')]);
                say([find(crdNode, 'p').map(p => p.textContent).filter(line => line.startsWith('cards') || line.startsWith('systemActions'))]);
                say(detailHint(crd));

                const decision = (subject, sequence, outcome) => ({subject, procedureCode: subject, sequence, foundAt: 'item[' + (sequence - 1) + ']',
                    reviewActions: [{presence: 'VALUE', code: outcome === 'APPROVED' ? 'A1' : 'A3', display: outcome}], source: 'ITEM', outcome,
                    reasonCodes: [], errorCodes: [], authorizationNumbers: outcome === 'APPROVED' ? ['AUTH-1'] : [], authorizedPeriod: 'NULL'});
                const pas = {protocol: 'PAS', operation: 'PAS_SUBMIT', wire: {response: {maskedPlaces: 1}}, detail: {responseKind: 'BUNDLE',
                    claimResponses: [{foundAt: 'entry[0]', id: v('cr-1'), decisions: [decision('81162', 1, 'APPROVED'), decision('81163', 2, 'DENIED')],
                        preAuthPeriod: 'EMPTY', lists: {error: 'NULL', processNote: 'ABSENT', item: 'EMPTY'}, items: [], errors: [], processNotes: [],
                        attachments: [{reference: v('CommunicationRequest/1'), open: true, carried: true, wanted: [], status: v('active'),
                            foundAt: 'entry[1]', rest: [{path: 'entry[1].resource.note', presence: 'VALUE', value: '"Bearer secret-held"'}]}]}],
                    otherResources: [], issues: [], requestNotes: [], bodyBuiltByTool: true, claimId: 'P32H-CLAIM-1'}};
                const pasNode = responseDetail(pas);
                await settle();
                const rows = find(pasNode, '.detail__decision');
                say([rows.length, rows.map(row => inClosedFold(row)), rows.map(row => row.querySelector('.chip__text').textContent)]);
                const pasText = pasNode.textContent;
                say([pasText.includes('returned empty'), pasText.includes('itemPreAuthPeriod valuePeriod returned as null'),
                    pasText.includes('error returned as null'), pasText.includes('item returned empty')]);
                const restFold = find(pasNode, 'details').find(each => each.querySelector('summary').textContent.startsWith('Show the 1 field'));
                restFold.open = true;
                restFold.dispatchEvent({type: 'toggle'});
                say([restFold.textContent.includes('secret-held'), restFold.textContent.includes('Withheld here')]);
                say(detailHint(pas));

                let deep = [];
                const top = deep;
                for (let i = 0; i < 4000; i++) {
                    const next = [];
                    deep.push({linkId: v('q' + i), text: v('Question ' + i), type: {presence: 'VALUE', code: 'group'}, required: v(false), items: next, lists: {}});
                    deep = next;
                }
                const dtr = {protocol: 'DTR', operation: 'DTR_QUESTIONNAIRE_PACKAGE', detail: {responseKind: 'BUNDLE', responseNote: 'Read as a package.',
                    requested: [], notReturned: [], bundles: [], resources: [], questionnaireResponses: [], issues: [], requestNotes: [],
                    questionnaires: [{position: 1, subject: 'Questionnaire/one', kind: 'ADAPTIVE', kindLabel: 'adaptive', foundAt: 'entry[0]',
                        problems: ['It has no item.'], questionCount: 4000, requiredCount: 0, items: top}], bodyBuiltByTool: true}};
                const dtrNode = responseDetail(dtr);
                say([find(dtrNode, '.table tbody tr').length, dtrNode.textContent.includes('This questionnaire cannot be answered.')]);

                const extensions = [];
                let at = extensions;
                for (let i = 0; i < 4000; i++) {
                    const parts = [];
                    at.push({url: 'u' + i, parts});
                    at = parts;
                }
                const sub = {protocol: 'SUBSCRIPTION', operation: 'SUBSCRIPTION_GET', detail: {responseKind: 'SUBSCRIPTION',
                    subscriptions: [{id: v('sub-1'), foundAt: '(root)', status: {presence: 'VALUE', code: 'active'}, headers: [v('Authorization')],
                        extensions}], issues: [], otherResources: []}};
                const subNode = responseDetail(sub);
                say([find(subNode, '.detail__extensions').length, subNode.textContent.includes('Authorization')]);

                say([responseDetail({protocol: 'LOOKUP', detail: {}}), responseDetail({protocol: 'CRD'}), responseDetail(null),
                    detailHint({protocol: 'RAW', detail: {}})]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"Delegated vendor: Vendor Q Recognised from the coverage assertion id.\",false]",
                "[true,true,true,true,0,true]",
                "[[\"cards returned empty\",\"systemActions not returned\"]]",
                "\"1 coverage information\"",
                "[2,[false,false],[\"Approved\",\"Denied\"]]",
                "[true,true,true,true]",
                "[false,true]",
                "\"2 item decisions, 1 ClaimResponse\"",
                "[4000,true]",
                "[4000,true]",
                "[null,null,null,\"\"]");
    }

    private static boolean hasProperty(Class<?> type, String name) {
        try {
            return type.getMethod(name).getParameterCount() == 0;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
