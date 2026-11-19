package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import java.util.List;
import java.util.Map;

/**
 * The fields every protocol's run request carries, under the same names and with the same meaning.
 *
 * <p>Each protocol posts its own record, and each of those records implements this, so the
 * compiler holds the three to one shape. {@link RunSupport} fills the ledger draft from it, which
 * is what keeps a CRD line, a DTR line and a PAS line of one chained run comparable.
 *
 * <p>The member and provider are sent exactly as the form shows them, so what the tester sees is
 * what goes out. The two override lists name the fields the tester typed or changed by hand; the
 * ledger then records that the run used details a fresh lookup would not reproduce.
 */
public interface RunRequest {

    MemberDetail member();

    /** Names of member fields typed or changed by hand. */
    List<String> memberOverrides();

    ProviderDetail provider();

    /** Names of provider fields typed or changed by hand. */
    List<String> providerOverrides();

    List<CodeInput> procedureCodes();

    /** ISO date, or blank to send none. */
    String dateOfService();

    /** A mock scenario to answer from. Refused on live. */
    String scenarioId();

    /** What the tester expects, or blank for none. */
    String expected();

    String testerRef();

    String note();

    String chainId();

    /** A specific collection request to send to, or null for the default. */
    RequestRef request();

    /** A body the tester edited, sent in place of the one the tool would build. Blank means none. */
    String rawBody();

    /** Placeholder values typed for this run. */
    Map<String, String> variables();
}
