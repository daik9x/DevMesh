package devmesh.checkpoint;
import java.util.*;
import java.util.regex.Pattern;
public final class CheckpointRedactor {
    private static final Pattern SECRET = Pattern.compile("(?i)(api[_-]?key|access[_-]?token|auth(orization)?|password|secret|private[_-]?key)");
    private CheckpointRedactor() {}
    public static Checkpoint redact(Checkpoint c) {
        return new Checkpoint(c.checkpointId(), c.sessionId(), c.taskId(), c.parentCheckpointId(), c.createdAt(), c.version(), c.status(),
                map(c.taskState()), map(c.todoState()), map(c.agentState()), map(c.contextState()), map(c.repositoryState()), map(c.toolState()),
                map(c.repairState()), map(c.verificationState()), map(c.executionState()), map(c.metadata()), c.integrity());
    }
    private static Map<String, Object> map(Map<String, Object> input) {
        var out = new LinkedHashMap<String, Object>();
        for (var e : input.entrySet()) out.put(e.getKey(), SECRET.matcher(e.getKey()).find() ? "[REDACTED]" : value(e.getValue()));
        return out;
    }
    private static Object value(Object value) {
        if (value instanceof Map<?, ?> raw) { var out = new LinkedHashMap<String, Object>(); raw.forEach((k, v) -> out.put(String.valueOf(k), SECRET.matcher(String.valueOf(k)).find() ? "[REDACTED]" : value(v))); return out; }
        if (value instanceof List<?> list) return list.stream().map(CheckpointRedactor::value).toList();
        return value;
    }
}