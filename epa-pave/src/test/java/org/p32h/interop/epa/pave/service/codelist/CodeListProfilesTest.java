package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.PropertiesConfig;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.suite.CsvRows;
import org.p32h.interop.epa.pave.web.ProcedureCodeController;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The code list parts under each profile. The whole application cannot be started on the live
 * profile until the configuration layer of another wave exists, so this starts only these classes
 * and what they need, once as mock and once as live.
 */
class CodeListProfilesTest {

    private static ApplicationContextRunner as(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withUserConfiguration(PropertiesConfig.class, ProcedureCodeService.class, CsvRowsAdapter.class, ProcedureCodeController.class,
                        // A class that does carry a profile, to show that a profile decides something in this context.
                        ScenarioRepository.class, MockExchanges.class, MockOnyxCrdClient.class);
    }

    @Test
    void onTheMockProfileTheCodeListTheSuiteReaderAndThePickerAreBeans() {
        as("mock").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("mock");
            assertThat(context).hasSingleBean(MockOnyxCrdClient.class);
            assertThat(context).hasSingleBean(ProcedureCodeDirectory.class);
            assertThat(context.getBean(ProcedureCodeDirectory.class)).isInstanceOf(ProcedureCodeService.class);
            assertThat(context.getBean(CsvRows.class)).isInstanceOf(CsvRowsAdapter.class);
            assertThat(context).hasSingleBean(ProcedureCodeController.class);
        });
    }

    @Test
    void onTheLiveProfileTheSameThreeAreBeansAndTheMockClientIsNot() {
        as("live").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("live");
            // The profile is honoured here: the mock client is gone.
            assertThat(context).doesNotHaveBean(MockOnyxCrdClient.class);
            assertThat(context).hasSingleBean(ProcedureCodeDirectory.class);
            assertThat(context.getBean(ProcedureCodeDirectory.class)).isInstanceOf(ProcedureCodeService.class);
            // The same list is loaded: what the code list says does not depend on where requests go.
            assertThat(context.getBean(ProcedureCodeDirectory.class).selfCheck().placeholder()).isTrue();
            assertThat(context.getBean(CsvRows.class)).isInstanceOf(CsvRowsAdapter.class);
            assertThat(context).hasSingleBean(ProcedureCodeController.class);
        });
    }
}
