package devmesh.checkpoint;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable logical execution snapshot. Implementation objects are deliberately absent. */
public record Checkpoint(String checkpointId, String sessionId, String taskId, String parentCheckpointId,
                         Instant createdAt, int version, Status status,
                         Map<String, Object> taskState, Map<String, Object> todoState,
                         Map<String, Object> agentState, Map<String, Object> contextState,
                         Map<String, Object> repositoryState, Map<String, Object> toolState,
                         Map<String, Object> repairState, Map<String, Object> verificationState,
                         Map<String, Object> executionState, Map<String, Object> metadata,
                         String integrity) {
    public enum Status { CREATED, VALID, RESUMING, RESUMED, INVALID, FAILED }

    @JsonCreator
    public Checkpoint(@JsonProperty("checkpointId") String checkpointId, @JsonProperty("sessionId") String sessionId,
                      @JsonProperty("taskId") String taskId, @JsonProperty("parentCheckpointId") String parentCheckpointId,
                      @JsonProperty("createdAt") Instant createdAt, @JsonProperty("version") int version,
                      @JsonProperty("status") Status status, @JsonProperty("taskState") Map<String, Object> taskState,
                      @JsonProperty("todoState") Map<String, Object> todoState, @JsonProperty("agentState") Map<String, Object> agentState,
                      @JsonProperty("contextState") Map<String, Object> contextState, @JsonProperty("repositoryState") Map<String, Object> repositoryState,
                      @JsonProperty("toolState") Map<String, Object> toolState, @JsonProperty("repairState") Map<String, Object> repairState,
                      @JsonProperty("verificationState") Map<String, Object> verificationState, @JsonProperty("executionState") Map<String, Object> executionState,
                      @JsonProperty("metadata") Map<String, Object> metadata, @JsonProperty("integrity") String integrity) {
        this.checkpointId = required(checkpointId, "checkpointId"); this.sessionId = required(sessionId, "sessionId"); this.taskId = required(taskId, "taskId");
        this.parentCheckpointId = parentCheckpointId; this.createdAt = createdAt == null ? Instant.now() : createdAt; this.version = version;
        this.status = status == null ? Status.CREATED : status; this.taskState = copy(taskState); this.todoState = copy(todoState);
        this.agentState = copy(agentState); this.contextState = copy(contextState); this.repositoryState = copy(repositoryState);
        this.toolState = copy(toolState); this.repairState = copy(repairState); this.verificationState = copy(verificationState);
        this.executionState = copy(executionState); this.metadata = copy(metadata); this.integrity = integrity;
    }
    public static Checkpoint draft(String sessionId, String taskId, String parent, Map<String, Object> executionState) {
        return new Checkpoint(java.util.UUID.randomUUID().toString(), sessionId, taskId, parent, Instant.now(), 1, Status.CREATED,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), executionState, Map.of(), null);
    }
    public Checkpoint withStatus(Status next, String checksum) {
        return new Checkpoint(checkpointId, sessionId, taskId, parentCheckpointId, createdAt, version, next, taskState, todoState,
                agentState, contextState, repositoryState, toolState, repairState, verificationState, executionState, metadata, checksum);
    }
    private static String required(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required"); return value; }
    private static Map<String, Object> copy(Map<String, Object> source) { return source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source)); }
}