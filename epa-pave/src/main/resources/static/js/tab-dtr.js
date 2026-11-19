// The DTR tab. It arrives in a later pass of the build, with the endpoints under /api/dtr.
// Until then it shows the case it shares with CRD and PAS, and what a CRD result carried to it.

import {protocolPlaceholder} from './handover.js';

export default protocolPlaceholder({
    id: 'dtr',
    label: 'DTR',
    sentence: 'The DTR tab arrives in a later pass of the build. Answering the payer\'s questionnaire will be done here.'
});
