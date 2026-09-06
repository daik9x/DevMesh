package devmesh.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class InitCliTest {
    @Test
    void initializesWorkspaceWithoutOverwritingExistingConfig() throws Exception {
        var workspace = Files.createTempDirectory("devmesh-init");
        assertEquals(0, InitCli.tryRun(new String[]{"init", "--workspace", workspace.toString()}));
        var config = workspace.resolve(".devmesh/config.yaml");
        assertTrue(Files.exists(config));
        assertTrue(Files.isDirectory(workspace.resolve(".devmesh/checkpoints")));
        assertTrue(Files.readString(config).contains("protocol: openrouter"));

        assertEquals(2, InitCli.tryRun(new String[]{"init", "--workspace", workspace.toString()}));
        assertEquals(0, InitCli.tryRun(new String[]{"init", "--workspace", workspace.toString(), "--force"}));
    }
}