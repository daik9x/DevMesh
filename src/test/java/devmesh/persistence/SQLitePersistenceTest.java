package devmesh.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;

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

    @Test
    void serializesMessageSequencesAcrossPersistenceInstances() throws Exception {
        var db = Files.createTempDirectory("devmesh-db").resolve("devmesh.db");
        try (var first = new SQLitePersistence(db); var second = new SQLitePersistence(db)) {
            first.createSession("s1", "Concurrent", db.getParent(), null, null, null);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<MessageRecord>>();
                for (int i = 0; i < 20; i++) {
                    var persistence = i % 2 == 0 ? first : second;
                    futures.add(executor.submit(() -> persistence.appendMessage("s1", "user", "MESSAGE", "message", null, null)));
                }
                for (var future : futures) future.get();
            }
            var messages = first.loadMessages("s1", 100, 0);
            assertEquals(20, messages.size());
                assertEquals(java.util.stream.IntStream.rangeClosed(1, 20).boxed().mapToLong(Integer::longValue).boxed().toList(),
                    messages.stream().map(MessageRecord::sequence).toList());
        }
    }
}
