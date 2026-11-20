// The PAS tab. It arrives in a later pass of the build, with the endpoints under /api/pas.
// Until then it shows the case it shares with CRD and DTR, and what an earlier step carried to it.

import {protocolPlaceholder} from './handover.js';

export default protocolPlaceholder({
    id: 'pas',
    label: 'PAS',
    sentence: 'The PAS tab arrives in a later pass of the build. Submitting the prior authorization request and following its status will be done here.'
});
