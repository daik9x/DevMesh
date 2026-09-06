package devmesh.persistence;
import java.time.Instant;
public record SessionRecord(String id, String title, Instant createdAt, Instant updatedAt, Instant lastActiveAt,
                            SessionStatus status, String workspacePath, String repositoryId, String model,
                            String provider, String mode, String metadata) {}