package devmesh.checkpoint;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
public final class RepositoryRecoveryValidator {
    public RepositoryRecoveryState validate(Checkpoint checkpoint) {
        Object raw = checkpoint.repositoryState().get("root"); if (raw == null || !Files.isDirectory(Path.of(raw.toString()))) return RepositoryRecoveryState.REPOSITORY_MISSING;
        Object expected = checkpoint.repositoryState().get("fileHashes"); if (!(expected instanceof Map<?, ?> hashes)) return RepositoryRecoveryState.UNCHANGED;
        for (var e : hashes.entrySet()) { try { Path file = Path.of(raw.toString()).resolve(e.getKey().toString()); if (!Files.exists(file) || !e.getValue().toString().equals(hash(file))) return RepositoryRecoveryState.EXTERNAL_CHANGES; } catch (IOException ex) { return RepositoryRecoveryState.EXTERNAL_CHANGES; } }
        return RepositoryRecoveryState.UNCHANGED;
    }
    private static String hash(Path file) throws IOException { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); } catch (Exception e) { throw new IOException(e); } }
}