package devmesh.checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic, append-only checkpoint files with a lock per session. */
public final class LocalCheckpointStore implements CheckpointStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    public LocalCheckpointStore(Path workDir) { root = workDir.toAbsolutePath().normalize().resolve(".devmesh/checkpoints"); }
    @Override public Checkpoint save(Checkpoint input) {
        validateIdentifier(input.sessionId(), "sessionId");
        validateIdentifier(input.checkpointId(), "checkpointId");
        synchronized (LOCKS.computeIfAbsent(input.sessionId(), ignored -> new Object())) {
            try {
                Path dir = root.resolve(input.sessionId()); Files.createDirectories(dir);
                Checkpoint redacted = CheckpointRedactor.redact(input); Checkpoint valid = redacted.withStatus(Checkpoint.Status.VALID, checksum(redacted));
                Path target = dir.resolve(valid.checkpointId() + ".json"); Path temp = Files.createTempFile(dir, valid.checkpointId(), ".tmp");
                try { MAPPER.writeValue(temp.toFile(), valid); try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); } Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temp); } return valid;
            } catch (IOException e) { throw new CheckpointStoreException("Failed to save checkpoint", e); }
        }
    }
    @Override public Optional<Checkpoint> load(String id) {
        try { if (!Files.exists(root)) return Optional.empty(); try (var files = Files.walk(root)) { return files.filter(p -> p.getFileName().toString().equals(id + ".json")).findFirst().flatMap(this::readValid); } }
        catch (IOException e) { return Optional.empty(); }
    }
    @Override public Optional<Checkpoint> loadLatest(String sessionId) { validateIdentifier(sessionId, "sessionId"); return list(sessionId).stream().filter(c -> c.status() == Checkpoint.Status.VALID).max(Comparator.comparing(Checkpoint::createdAt)); }
    @Override public List<Checkpoint> list(String sessionId) {
        validateIdentifier(sessionId, "sessionId");
        Path dir = root.resolve(sessionId); if (!Files.isDirectory(dir)) return List.of();
        try (var files = Files.list(dir)) { return files.filter(p -> p.toString().endsWith(".json")).map(this::readValid).flatMap(Optional::stream).sorted(Comparator.comparing(Checkpoint::createdAt)).toList(); }
        catch (IOException e) { return List.of(); }
    }
    @Override public void invalidate(String id) { load(id).ifPresent(c -> { try { MAPPER.writeValue(pathOf(c).toFile(), c.withStatus(Checkpoint.Status.INVALID, c.integrity())); } catch (IOException e) { throw new CheckpointStoreException("Failed to invalidate checkpoint", e); } }); }
    @Override public void delete(String id) { load(id).ifPresent(c -> { try { Files.deleteIfExists(pathOf(c)); } catch (IOException e) { throw new CheckpointStoreException("Failed to delete checkpoint", e); } }); }
    private Optional<Checkpoint> readValid(Path path) { try { Checkpoint c = MAPPER.readValue(path.toFile(), Checkpoint.class); return c.version() == 1 && c.status() == Checkpoint.Status.VALID && Objects.equals(c.integrity(), checksum(c)) ? Optional.of(c) : Optional.empty(); } catch (Exception e) { return Optional.empty(); } }
    private Path pathOf(Checkpoint c) { return root.resolve(c.sessionId()).resolve(c.checkpointId() + ".json"); }
    private static void validateIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new CheckpointStoreException("Invalid checkpoint " + field);
        }
    }
    private static String checksum(Checkpoint c) throws IOException { byte[] bytes = MAPPER.writeValueAsBytes(c.withStatus(Checkpoint.Status.CREATED, null)); try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IOException(e); } }
}