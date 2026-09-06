package devmesh.checkpoint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointStoreTest {
    @Test
    void savesAtomicallyAndLoadsLatestValidCheckpoint() throws Exception {
        var root = Files.createTempDirectory("devmesh-checkpoint");
        var manager = new CheckpointManager(new LocalCheckpointStore(root), 10);
        var first = manager.create(Checkpoint.draft("session", "task", null, Map.of("phase", "planned")));
        var second = manager.create(Checkpoint.draft("session", "task", first.checkpointId(), Map.of("phase", "executing")));

        assertEquals(second.checkpointId(), manager.latestValid("session").orElseThrow().checkpointId());
        assertEquals(2, manager.history("session").size());
        assertEquals(first.checkpointId(), second.parentCheckpointId());
        assertTrue(Files.exists(root.resolve(".devmesh/checkpoints/session/" + second.checkpointId() + ".json")));
    }

    @Test
    void rejectsCorruptionAndRedactsSecrets() throws Exception {
        var root = Files.createTempDirectory("devmesh-checkpoint");
        var store = new LocalCheckpointStore(root);
        var saved = store.save(new Checkpoint("id", "session", "task", null, null, 1, Checkpoint.Status.CREATED,
                Map.of("apiKey", "do-not-store"), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null));
        var path = root.resolve(".devmesh/checkpoints/session/id.json");
        assertTrue(Files.readString(path).contains("[REDACTED]"));
        Files.writeString(path, Files.readString(path).replace(saved.integrity(), "bad"));
        assertTrue(store.load(saved.checkpointId()).isEmpty());
        assertTrue(store.loadLatest("session").isEmpty());
    }

    @Test
    void validatesRepositoryHashes() throws Exception {
        var root = Files.createTempDirectory("devmesh-repository");
        var file = root.resolve("source.txt");
        Files.writeString(file, "before");
        var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var checkpoint = new Checkpoint("id", "session", "task", null, null, 1, Checkpoint.Status.VALID,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of("root", root.toString(), "fileHashes", Map.of("source.txt", hash)),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "");
        var validator = new RepositoryRecoveryValidator();
        assertEquals(RepositoryRecoveryState.UNCHANGED, validator.validate(checkpoint));
        Files.writeString(file, "after");
        assertEquals(RepositoryRecoveryState.EXTERNAL_CHANGES, validator.validate(checkpoint));
    }

    @Test
    void blocksUnknownSideEffectsAndMissingRepositories() throws Exception {
        var root = Files.createTempDirectory("devmesh-recovery");
        var store = new LocalCheckpointStore(root);
        var checkpoint = new Checkpoint("id", "session", "task", null, null, 1, Checkpoint.Status.CREATED,
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of("root", root.toString()),
            Map.of(), Map.of(), Map.of(), Map.of("actionStatus", ActionStatus.STARTED_UNKNOWN_RESULT.name()), Map.of(), null);
        store.save(checkpoint);
        var result = new RecoveryManager(new CheckpointManager(store)).decide("session");
        assertEquals(RecoveryDecision.VERIFY_THEN_RESUME, result.decision());

        var missing = new Checkpoint("missing", "missing-session", "task", null, null, 1, Checkpoint.Status.CREATED,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of("root", root.resolve("gone").toString()), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
        store.save(missing);
        assertEquals(RecoveryDecision.ABORT, new RecoveryManager(new CheckpointManager(store)).decide("missing-session").decision());
    }
}
