// The hand-over between steps of a case.
//
// When a result says the next step is DTR or PAS, the button on it goes to that tab carrying two
// things: the result's nextStep.carry, which the next run posts back unchanged as "carry", and
// the case the run was sent with. app.carriedTo(tabId) returns {carry, fromRunId, fromProtocol,
// chainId, forCase} or null. The tab that receives it sends carry with its next run only while the
// case on screen is still forCase: values carried for one member must not go out with another,
// and sameCase(forCase, app.caseContext.summary()) is the one test of that, for every tab.

/**
 * True when two cases are the same member, provider and codes. one and other are each
 * {memberId, npi, codes}, as app.caseContext.summary() gives them and as forCase is carried. A
 * hand-over made for one case must not go out with another, and this is the one test of that.
 */
export function sameCase(one, other) {
    if (!one || !other) {
        return false;
    }
    return one.memberId === other.memberId && one.npi === other.npi && JSON.stringify(one.codes || []) === JSON.stringify(other.codes || []);
}
