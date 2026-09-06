package devmesh.persistence;

import devmesh.checkpoint.Checkpoint;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface PersistenceService extends AutoCloseable {
    SessionRecord createSession(String id, String title, Path workspace, String model, String provider, String mode);
    Optional<SessionRecord> session(String id);
    List<SessionRecord> listSessions(int limit, int offset);
    List<SessionRecord> searchSessions(String query, int limit);
    void renameSession(String id, String title);
    void archiveSession(String id);
    void deleteSession(String id);
    MessageRecord appendMessage(String sessionId, String role, String type, String content, Long parentId, String metadata);
    List<MessageRecord> loadMessages(String sessionId, int limit, int offset);
    Checkpoint saveCheckpoint(Checkpoint checkpoint);
    Optional<Checkpoint> latestCheckpoint(String sessionId);
    @Override void close();
}