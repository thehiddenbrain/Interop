package org.p32h.interop.epa.pave.service.scenario;

import java.nio.file.Path;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Checks every protocol's mock scenarios once, when the application starts.
 *
 * <p>On the mock profile a damaged set stops the start, naming each file and its reason: every
 * mock answer comes from these files, and a damaged one would otherwise be met only when a run
 * was answered by the wrong scenario.
 *
 * <p>The live profile never answers from scenarios, so a damaged set does not stop it. A tester
 * who keeps one settings file for both profiles would otherwise be locked out of live by files
 * live never reads. When {@code app.scenario-dir} is set and its files cannot be used, one WARN
 * line names the setting, so the problem is known before the next mock start refuses it.
 */
@Component
public class ScenarioCheckAtStart implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCheckAtStart.class);

    private final ScenarioRepository scenarios;
    private final AppProperties app;
    private final Environment environment;

    public ScenarioCheckAtStart(ScenarioRepository scenarios, AppProperties app, Environment environment) {
        this.scenarios = scenarios;
        this.app = app;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.matchesProfiles("mock")) {
            scenarios.checkEveryProtocol();
            return;
        }
        if (!Texts.present(app.scenarioDir())) {
            return;
        }
        try {
            scenarios.checkEveryProtocol();
        } catch (IllegalStateException e) {
            // The setting and its directory only, which are configuration. The problems are left out: this profile does not read the files.
            log.warn("The mock scenarios in {} (app.scenario-dir) cannot be used. The live profile does not answer from them, so the tool "
                    + "starts; on the mock profile it would refuse to start until they are put right.", Path.of(app.scenarioDir().strip()));
        }
    }
}
