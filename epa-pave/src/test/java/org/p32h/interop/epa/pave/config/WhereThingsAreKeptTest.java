package org.p32h.interop.epa.pave.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/** m10: at start-up the tool says where it keeps what it writes, and says so again as a risk when that is inside a git working tree. */
@ExtendWith(OutputCaptureExtension.class)
class WhereThingsAreKeptTest {

    @TempDir
    Path directory;

    @Test
    void eachFolderIsNamedInFullAndOneInsideAWorkingTreeIsNamedAsARisk() throws Exception {
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Path tree = Files.createDirectories(directory.resolve("repo"));
        // A linked worktree has a .git file, not a folder. Either marks a working tree.
        Files.writeString(tree.resolve(".git"), "gitdir: elsewhere\n");
        Path store = outside.resolve("config-store");
        Path history = tree.resolve("deep/run-history");

        List<StartupChecks.Kept> lines = StartupChecks.whereThingsAreKept(store.toString(), history.toString());
        assertThat(lines).containsExactly(
                new StartupChecks.Kept("The configuration store, which holds uploaded environments and collections and can hold "
                        + "credentials, is at " + store, false),
                new StartupChecks.Kept("The run history is at " + history, false),
                new StartupChecks.Kept("The run history " + history + " lies inside the git working tree " + tree + ". Its files, with "
                        + "what the runs recorded, could be committed from there. Check that the tree's .gitignore covers it, or start the "
                        + "tool from a directory outside the tree.", true));

        Files.createDirectories(outside.resolve(".git"));
        assertThat(StartupChecks.whereThingsAreKept(store.toString(), null)).containsExactly(
                new StartupChecks.Kept("The configuration store, which holds uploaded environments and collections and can hold "
                        + "credentials, is at " + store, false),
                new StartupChecks.Kept("The configuration store " + store + " lies inside the git working tree " + outside + ". An "
                        + "uploaded environment, with any secret in it, could be committed from there. Check that the tree's .gitignore "
                        + "covers it, or start the tool from a directory outside the tree.", true),
                new StartupChecks.Kept("The run history is switched off (tracking.enabled is false), so no run is recorded.", false));
        // A relative folder is said in full.
        assertThat(StartupChecks.whereThingsAreKept("./config-store", null).get(0).text())
                .endsWith(Path.of("config-store").toAbsolutePath().normalize().toString());
    }

    @Test
    void theLinesAreWrittenToTheLogAtStartUp(CapturedOutput output) {
        Path tree = directory.resolve("repo");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mock");
        new StartupChecks(EpaProperties.of(Map.of("mode", "mock", "target", "onyx")), OnyxProperties.defaults(), LookupProperties.defaults(),
                TrackingProperties.of(Map.of("directory", tree.resolve("run-history").toString())), AppProperties.defaults(), environment,
                ConfigStoreProperties.of(Map.of("directory", directory.resolve("config-store").toString()))).afterPropertiesSet();
        assertThat(output.getOut()).contains("The configuration store, which holds uploaded environments and collections and can hold "
                + "credentials, is at " + directory.resolve("config-store")).contains("The run history is at " + tree.resolve("run-history"));
    }
}
