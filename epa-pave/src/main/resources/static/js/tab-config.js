// The Config tab. It arrives in a later pass of the build, with the endpoints under /api/config.
// Until then the tab says so in one sentence and offers no form that could not do anything.

import {el, fill} from './dom.js';

export default {
    id: 'config',
    label: 'Config',
    mount(panel) {
        fill(panel, el('p', {class: 'plain-statement'},
            'The Config tab arrives in a later pass of the build. Uploading environments and collections, '
            + 'switching the active environment and unlocking production writes will be done here.'));
    }
};
