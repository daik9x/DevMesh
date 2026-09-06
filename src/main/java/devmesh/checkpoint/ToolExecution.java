package devmesh.checkpoint;
import java.time.Instant;
public record ToolExecution(String executionId, String tool, String intent, String inputHash,
                            ActionStatus status, String resultReference, String sideEffectLevel,
                            Instant startedAt, Instant completedAt) {}