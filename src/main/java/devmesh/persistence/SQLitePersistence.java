package devmesh.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import devmesh.checkpoint.Checkpoint;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Transactional SQLite persistence with explicit schema migrations and WAL mode. */
public final class SQLitePersistence implements PersistenceService {
    public static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final String url;
    private final Object lock = new Object();

    public SQLitePersistence(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        try { java.nio.file.Files.createDirectories(databasePath.toAbsolutePath().normalize().getParent()); }
        catch (Exception e) { throw new PersistenceException("Cannot create database directory", e); }
        this.url = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        migrate();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON"); statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL"); statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }
    private void migrate() {
        synchronized (lock) { try (Connection c = open(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            int version = 0; try (ResultSet r = s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) { if (r.next()) version = r.getInt(1); }
            if (version < 1) {
                s.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_active_at TEXT NOT NULL, status TEXT NOT NULL, workspace_path TEXT, repository_id TEXT, model TEXT, provider TEXT, mode TEXT, metadata TEXT)");
                s.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, parent_id INTEGER, role TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL, sequence INTEGER NOT NULL, message_type TEXT NOT NULL, metadata TEXT, UNIQUE(session_id, sequence))");
                s.execute("CREATE INDEX messages_session_sequence ON messages(session_id, sequence)");
                s.execute("CREATE INDEX sessions_updated ON sessions(updated_at DESC)");
                s.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, description TEXT, status TEXT, current_objective TEXT, metadata TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
                s.execute("CREATE TABLE todos (id TEXT PRIMARY KEY, task_id TEXT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, title TEXT NOT NULL, description TEXT, status TEXT NOT NULL, priority INTEGER, item_order INTEGER, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
                s.execute("CREATE TABLE checkpoints (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, task_id TEXT, parent_id TEXT, schema_version INTEGER NOT NULL, created_at TEXT NOT NULL, status TEXT NOT NULL, state_json TEXT NOT NULL, integrity TEXT NOT NULL)");
                s.execute("CREATE INDEX checkpoints_session_created ON checkpoints(session_id, created_at DESC)");
                s.execute("CREATE TABLE tool_executions (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, task_id TEXT, tool_name TEXT, category TEXT, implementation_type TEXT, arguments_metadata TEXT, started_at TEXT, ended_at TEXT, status TEXT, result_metadata TEXT, error TEXT, permission_decision TEXT, checkpoint_id TEXT)");
                s.execute("CREATE TABLE repair_attempts (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, task_id TEXT, checkpoint_id TEXT, failure_classification TEXT, diagnosis TEXT, repair_plan TEXT, attempt_number INTEGER, status TEXT, result TEXT, created_at TEXT NOT NULL)");
                s.execute("CREATE TABLE repositories (id TEXT PRIMARY KEY, root_path TEXT NOT NULL UNIQUE, build_system TEXT, languages TEXT, branch TEXT, commit_hash TEXT, last_indexed_at TEXT, metadata TEXT)");
                s.execute("CREATE TABLE evaluations (id TEXT PRIMARY KEY, session_id TEXT, task_id TEXT, scenario TEXT, model TEXT, score REAL, metrics TEXT, status TEXT, report TEXT, created_at TEXT NOT NULL)");
                try (PreparedStatement p = c.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)") ) { p.setInt(1, 1); p.setString(2, Instant.now().toString()); p.executeUpdate(); }
            }
            if (version > SCHEMA_VERSION) throw new PersistenceException("Database schema " + version + " is newer than supported " + SCHEMA_VERSION);
            c.commit();
        } catch (SQLException | PersistenceException e) { throw e instanceof PersistenceException p ? p : new PersistenceException("Database migration failed", e); } }
    }

    @Override public SessionRecord createSession(String id, String title, Path workspace, String model, String provider, String mode) {
        String now = Instant.now().toString(); String safeTitle = title == null || title.isBlank() ? "New chat" : title;
        String workspacePath = workspace == null ? null : workspace.toAbsolutePath().normalize().toString();
        return transaction(c -> { try (PreparedStatement p = c.prepareStatement("INSERT INTO sessions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) { p.setString(1, id); p.setString(2, PersistenceRedactor.redact(safeTitle)); p.setString(3, now); p.setString(4, now); p.setString(5, now); p.setString(6, SessionStatus.ACTIVE.name()); p.setString(7, workspacePath); p.setString(8, null); p.setString(9, model); p.setString(10, provider); p.setString(11, mode); p.setString(12, "{}"); p.executeUpdate(); } return new SessionRecord(id, PersistenceRedactor.redact(safeTitle), Instant.parse(now), Instant.parse(now), Instant.parse(now), SessionStatus.ACTIVE, workspacePath, null, model, provider, mode, "{}"); });
    }
    @Override public Optional<SessionRecord> session(String id) { return queryOne("SELECT * FROM sessions WHERE id = ?", p -> p.setString(1, id), this::readSession); }
    @Override public List<SessionRecord> listSessions(int limit, int offset) { return query("SELECT * FROM sessions WHERE status <> 'ARCHIVED' ORDER BY updated_at DESC LIMIT ? OFFSET ?", p -> { p.setInt(1, Math.max(1, limit)); p.setInt(2, Math.max(0, offset)); }, this::readSession); }
    @Override public List<SessionRecord> searchSessions(String query, int limit) { String q = "%" + PersistenceRedactor.redact(query == null ? "" : query) + "%"; return query("SELECT DISTINCT s.* FROM sessions s LEFT JOIN messages m ON m.session_id=s.id WHERE s.title LIKE ? OR s.workspace_path LIKE ? OR m.content LIKE ? ORDER BY s.updated_at DESC LIMIT ?", p -> { p.setString(1, q); p.setString(2, q); p.setString(3, q); p.setInt(4, Math.max(1, limit)); }, this::readSession); }
    @Override public void renameSession(String id, String title) { execute("UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?", PersistenceRedactor.redact(title), Instant.now().toString(), id); }
    @Override public void archiveSession(String id) { execute("UPDATE sessions SET status = ?, updated_at = ? WHERE id = ?", SessionStatus.ARCHIVED.name(), Instant.now().toString(), id); }
    @Override public void deleteSession(String id) { execute("DELETE FROM sessions WHERE id = ?", id); }
    @Override public MessageRecord appendMessage(String sessionId, String role, String type, String content, Long parentId, String metadata) { return transaction(c -> { long sequence; try (PreparedStatement q = c.prepareStatement("SELECT COALESCE(MAX(sequence), 0) + 1 FROM messages WHERE session_id = ?")) { q.setString(1, sessionId); try (ResultSet r = q.executeQuery()) { r.next(); sequence = r.getLong(1); } } String now = Instant.now().toString(); try (PreparedStatement p = c.prepareStatement("INSERT INTO messages(session_id,parent_id,role,content,created_at,sequence,message_type,metadata) VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) { p.setString(1, sessionId); if (parentId == null) p.setNull(2, Types.INTEGER); else p.setLong(2, parentId); p.setString(3, role); p.setString(4, PersistenceRedactor.redact(content)); p.setString(5, now); p.setLong(6, sequence); p.setString(7, type == null ? "MESSAGE" : type); p.setString(8, PersistenceRedactor.redact(metadata)); p.executeUpdate(); try (ResultSet r = p.getGeneratedKeys()) { r.next(); return new MessageRecord(r.getLong(1), sessionId, parentId, role, PersistenceRedactor.redact(content), Instant.parse(now), sequence, type == null ? "MESSAGE" : type, PersistenceRedactor.redact(metadata)); } } }); }
    @Override public List<MessageRecord> loadMessages(String sessionId, int limit, int offset) { return query("SELECT * FROM messages WHERE session_id = ? ORDER BY sequence LIMIT ? OFFSET ?", p -> { p.setString(1, sessionId); p.setInt(2, Math.max(1, limit)); p.setInt(3, Math.max(0, offset)); }, this::readMessage); }
    @Override public Checkpoint saveCheckpoint(Checkpoint checkpoint) { try { String json = JSON.writeValueAsString(checkpoint); return transaction(c -> { try (PreparedStatement p = c.prepareStatement("INSERT INTO checkpoints(id,session_id,task_id,parent_id,schema_version,created_at,status,state_json,integrity) VALUES(?,?,?,?,?,?,?,?,?)")) { p.setString(1, checkpoint.checkpointId()); p.setString(2, checkpoint.sessionId()); p.setString(3, checkpoint.taskId()); p.setString(4, checkpoint.parentCheckpointId()); p.setInt(5, checkpoint.version()); p.setString(6, checkpoint.createdAt().toString()); p.setString(7, checkpoint.status().name()); p.setString(8, json); p.setString(9, checkpoint.integrity()); p.executeUpdate(); return checkpoint; } }); } catch (Exception e) { throw new PersistenceException("Checkpoint persistence failed", e); } }
    @Override public Optional<Checkpoint> latestCheckpoint(String sessionId) { return queryOne("SELECT state_json FROM checkpoints WHERE session_id = ? AND status = 'VALID' ORDER BY created_at DESC LIMIT 1", p -> p.setString(1, sessionId), r -> { try { return JSON.readValue(r.getString(1), Checkpoint.class); } catch (Exception e) { throw new PersistenceException("Invalid persisted checkpoint", e); } }); }
    private SessionRecord readSession(ResultSet r) throws SQLException { return new SessionRecord(r.getString("id"), r.getString("title"), Instant.parse(r.getString("created_at")), Instant.parse(r.getString("updated_at")), Instant.parse(r.getString("last_active_at")), SessionStatus.valueOf(r.getString("status")), r.getString("workspace_path"), r.getString("repository_id"), r.getString("model"), r.getString("provider"), r.getString("mode"), r.getString("metadata")); }
    private MessageRecord readMessage(ResultSet r) throws SQLException { return new MessageRecord(r.getLong("id"), r.getString("session_id"), (Long) r.getObject("parent_id"), r.getString("role"), r.getString("content"), Instant.parse(r.getString("created_at")), r.getLong("sequence"), r.getString("message_type"), r.getString("metadata")); }
    private void execute(String sql, Object... args) { transaction(c -> { try (PreparedStatement p = c.prepareStatement(sql)) { for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]); p.executeUpdate(); } return null; }); }
    private <T> Optional<T> queryOne(String sql, SQLConsumer<PreparedStatement> binder, SQLReader<T> reader) { List<T> rows = query(sql, binder, reader); return rows.stream().findFirst(); }
    private <T> List<T> query(String sql, SQLConsumer<PreparedStatement> binder, SQLReader<T> reader) { synchronized (lock) { try (Connection c = open(); PreparedStatement p = c.prepareStatement(sql)) { binder.accept(p); try (ResultSet r = p.executeQuery()) { var out = new ArrayList<T>(); while (r.next()) out.add(reader.read(r)); return out; } } catch (SQLException e) { throw new PersistenceException("Database query failed", e); } } }
    private <T> T transaction(SQLWork<T> work) { synchronized (lock) { try (Connection c = open()) { c.setAutoCommit(false); try { T result = work.run(c); c.commit(); return result; } catch (Exception e) { c.rollback(); if (e instanceof PersistenceException p) throw p; throw new PersistenceException("Database transaction rolled back", e); } } catch (SQLException e) { throw new PersistenceException("Database transaction failed", e); } } }
    @FunctionalInterface private interface SQLWork<T> { T run(Connection c) throws Exception; }
    @FunctionalInterface private interface SQLConsumer<T> { void accept(T value) throws SQLException; }
    @FunctionalInterface private interface SQLReader<T> { T read(ResultSet value) throws SQLException; }
    @Override public void close() {}
}