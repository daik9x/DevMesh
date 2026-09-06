package devmesh.checkpoint;
import java.util.List;
import java.util.Optional;
public interface CheckpointStore {
    Checkpoint save(Checkpoint checkpoint);
    Optional<Checkpoint> load(String checkpointId);
    Optional<Checkpoint> loadLatest(String sessionId);
    List<Checkpoint> list(String sessionId);
    void invalidate(String checkpointId);
    void delete(String checkpointId);
}