package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * The coverage check posts the Diagnosis codes of the Service card (D-310): the server puts them on
 * the order only where the collection's sample does, so the body carries them whenever typed and
 * leaves the key out when none is, which keeps every body without them as it was.
 */
class CrdBodyDiagnosisFrontendTest {

    @Test
    void theDiagnosisCodesGoAsCodesWhenTypedAndTheKeyIsAbsentOtherwise(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the CRD body", """
                import './browser.js';
                import {crdBody} from './js/wb-crd.js';
                const say = value => console.log(JSON.stringify(value));
                const own = {hook: 'order-sign', includePrefetch: true, expected: '', mock: false, scenarioId: ''};
                const base = {member: {memberId: 'S123456789'}, memberOverrides: [], provider: {npi: '1234567893'}, providerOverrides: [],
                  procedureCodes: [{code: 'E0470'}], chainId: 'new', configEpoch: 5};
                say(crdBody({...base, diagnosisCodes: ['G47.33', 'E66.01']}, own).diagnosisCodes);
                say(crdBody({...base, diagnosisCodes: [{code: 'G47.33', system: 'urn:placeholder:dx'}]}, own).diagnosisCodes);
                say('diagnosisCodes' in crdBody(base, own));
                say('diagnosisCodes' in crdBody({...base, diagnosisCodes: []}, own));
                say('diagnosisCodes' in crdBody({...base, diagnosisCodes: undefined}, own));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[{\"code\":\"G47.33\"},{\"code\":\"E66.01\"}]",
                "[{\"code\":\"G47.33\",\"system\":\"urn:placeholder:dx\"}]",
                "false",
                "false",
                "false");
    }
}
