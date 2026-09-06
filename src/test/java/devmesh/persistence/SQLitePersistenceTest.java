package devmesh.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLitePersistenceTest {
    @Test
    void migratesCreatesSearchesAndPaginatesSessions() throws Exception {
        var db = Files.createTempDirectory("devmesh-db").resolve("devmesh.db");
        try (var persistence = new SQLitePersistence(db)) {
            var session = persistence.createSession("s1", "Authentication fix", db.getParent(), "model", "provider", "default");
            persistence.appendMessage(session.id(), "user", "MESSAGE", "Fix authentication", null, null);
            persistence.appendMessage(session.id(), "assistant", "MESSAGE", "I will inspect the auth flow", null, null);
            assertEquals("Authentication fix", persistence.session("s1").orElseThrow().title());
            assertEquals(2, persistence.loadMessages("s1", 10, 0).size());
            assertEquals("Authentication fix", persistence.searchSessions("authentication", 10).getFirst().title());
            assertEquals(1, persistence.listSessions(1, 0).size());
            persistence.renameSession("s1", "Renamed chat");
            assertEquals("Renamed chat", persistence.session("s1").orElseThrow().title());
            persistence.archiveSession("s1");
            assertTrue(persistence.listSessions(10, 0).isEmpty());
        }
        assertTrue(Files.exists(db));
    }

    @Test
    void rollsBackFailedMessageTransactionAndRedactsSecrets() throws Exception {
        var db = Files.createTempDirectory("devmesh-db").resolve("devmesh.db");
        try (var persistence = new SQLitePersistence(db)) {
            persistence.createSession("s1", "Safe", db.getParent(), "model", "provider", "default");
            var message = persistence.appendMessage("s1", "user", "MESSAGE", "apiKey=never-store-this", null, "password=hidden");
            assertTrue(message.content().contains("[REDACTED]"));
            assertTrue(message.metadata().contains("[REDACTED]"));
            assertThrows(PersistenceException.class, () -> persistence.appendMessage("missing", "user", "MESSAGE", "x", null, null));
            assertEquals(1, persistence.loadMessages("s1", 10, 0).size());
        }
    }

    @Test
    void migrationIsIdempotentAndForeignKeysProtectSessions() throws Exception {
        var db = Files.createTempDirectory("devmesh-db").resolve("devmesh.db");
        try (var first = new SQLitePersistence(db)) {
            first.createSession("s1", "One", db.getParent(), null, null, null);
        }
        try (var second = new SQLitePersistence(db)) {
            assertEquals("One", second.session("s1").orElseThrow().title());
            assertThrows(PersistenceException.class, () -> second.appendMessage("missing", "user", "MESSAGE", "x", null, null));
        }
    }
}
