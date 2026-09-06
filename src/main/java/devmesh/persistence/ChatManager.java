package devmesh.persistence;

import devmesh.session.SessionManager;
import java.nio.file.Path;
import java.util.List;

/** User-facing chat lifecycle facade over the persistence service. */
public final class ChatManager {
    private final PersistenceService persistence;
    private final Path workspace;
    public ChatManager(PersistenceService persistence, Path workspace) { this.persistence = persistence; this.workspace = workspace; }
    public SessionRecord create(String title, String model, String provider, String mode) { return persistence.createSession(SessionManager.newId(), title, workspace, model, provider, mode); }
    public List<SessionRecord> list(int limit, int offset) { return persistence.listSessions(limit, offset); }
    public List<SessionRecord> search(String query, int limit) { return persistence.searchSessions(query, limit); }
    public SessionRecord rename(String id, String title) { persistence.renameSession(id, title); return persistence.session(id).orElseThrow(() -> new PersistenceException("Session not found: " + id)); }
    public void archive(String id) { persistence.archiveSession(id); }
    public void delete(String id) { persistence.deleteSession(id); }
}