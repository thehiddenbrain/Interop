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

    /** The chain id a first step posts to have the service start a new chain and return its id. */
    String NEW_CHAIN = "new";

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

    /**
     * The chain this run belongs to. Blank means none. {@link #NEW_CHAIN} asks the service to mint
     * one, which comes back on the result. Any other value must be an id this tool minted: free
     * text here would be written to the ledger as it stands.
     */
    String chainId();

    /** A specific collection request to send to, or null for the default. */
    RequestRef request();

    /** A body the tester edited, sent in place of the one the tool would build. Blank means none. */
    String rawBody();

    /** Placeholder values typed for this run. */
    Map<String, String> variables();

    /**
     * What the previous step handed on: its result's {@code nextStep.carry}, posted back unchanged,
     * keyed by the {@link org.p32h.interop.epa.pave.model.NextStepView} CARRY constants. Empty for
     * a first step. A run that used any of it is marked in the ledger as having used something an
     * earlier step produced, so a replay does not run it without them and call it the same request.
     */
    Map<String, List<String>> carry();

    /**
     * The configuration epoch the screen was drawn from, as {@code /api/mode} gave it, or null to
     * not check. With it, a run is refused if the configuration has changed since, so it can never
     * go to an environment other than the one the tester was looking at.
     */
    Long configEpoch();

    /**
     * Every field of the posted request that this tool does not know. A request that has any is
     * refused, naming them: a misspelt {@code expected} would otherwise be dropped without a word
     * and the run recorded as unverified. Each implementing record declares this as a component
     * that collects unknown properties.
     */
    Map<String, Object> unrecognised();
}
