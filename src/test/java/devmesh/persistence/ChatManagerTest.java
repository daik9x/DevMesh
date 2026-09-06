package devmesh.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class ChatManagerTest {
    @Test
    void supportsChatLifecycleAndSearch() throws Exception {
        var workspace = Files.createTempDirectory("devmesh-chat");
        try (var persistence = new SQLitePersistence(workspace.resolve("chat.db"))) {
            var manager = new ChatManager(persistence, workspace);
            var created = manager.create("Fix auth", "model", "provider", "default");
            assertEquals(1, manager.list(10, 0).size());
            assertEquals(created.id(), manager.search("auth", 10).getFirst().id());
            assertEquals("Renamed", manager.rename(created.id(), "Renamed").title());
            manager.archive(created.id());
            assertTrue(manager.list(10, 0).isEmpty());
            manager.delete(created.id());
            assertTrue(persistence.session(created.id()).isEmpty());
        }
    }
}
