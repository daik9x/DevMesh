package devmesh.checkpoint;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
/** Coordinates validation, persistence, recovery lookup, and bounded retention. */
public final class CheckpointManager {
    private final CheckpointStore store; private final int maxCheckpoints; private final Consumer<Checkpoint> listener;
    public CheckpointManager(CheckpointStore store) { this(store, 50, ignored -> {}); }
    public CheckpointManager(CheckpointStore store, int maxCheckpoints) { this(store, maxCheckpoints, ignored -> {}); }
    public CheckpointManager(CheckpointStore store, int maxCheckpoints, Consumer<Checkpoint> listener) { this.store = store; this.maxCheckpoints = Math.max(2, maxCheckpoints); this.listener = listener == null ? ignored -> {} : listener; }
    public Checkpoint create(Checkpoint checkpoint) { Checkpoint saved = store.save(checkpoint); retain(saved.sessionId()); listener.accept(saved); return saved; }
    public Optional<Checkpoint> latestValid(String sessionId) { return store.loadLatest(sessionId); }
    public Optional<Checkpoint> load(String id) { return store.load(id); }
    public List<Checkpoint> history(String sessionId) { return store.list(sessionId); }
    public RepositoryRecoveryState validateRepository(Checkpoint checkpoint) { return new RepositoryRecoveryValidator().validate(checkpoint); }
    public void invalidate(String id) { store.invalidate(id); }
    private void retain(String sessionId) { var history = store.list(sessionId); for (int i = 0; i < history.size() - maxCheckpoints; i++) if (history.get(i).status() == Checkpoint.Status.VALID) store.delete(history.get(i).checkpointId()); }
}