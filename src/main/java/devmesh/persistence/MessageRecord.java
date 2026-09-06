package devmesh.persistence;
import java.time.Instant;
public record MessageRecord(long id, String sessionId, Long parentId, String role, String content,
                            Instant createdAt, long sequence, String messageType, String metadata) {}