package devmesh.agent;

import devmesh.permission.PermissionResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public sealed interface AgentEvent {

    record StreamText(String text) implements AgentEvent {}

    record ThinkingText(String text) implements AgentEvent {}

    record ThinkingComplete(String thinking, String signature) implements AgentEvent {}

    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {}

    record ToolResultEvent(String toolId, String toolName, String output,
                           boolean isError, double elapsed) implements AgentEvent {}

    record CommandStarted(String command) implements AgentEvent {}

    record CommandOutput(String text, boolean stderr) implements AgentEvent {}

    record CommandCompleted(int exitCode, String status) implements AgentEvent {}

    record CommandTimedOut() implements AgentEvent {}

    record CommandCancelled() implements AgentEvent {}

    record RepairDiagnosing(String intent, String failureSignature) implements AgentEvent {}
    record RepairDiagnosisCompleted(String failureSignature, String failureType) implements AgentEvent {}
    record RepairPlanned(String taskId, java.util.List<String> diagnostics) implements AgentEvent {}
    record RepairApplying(String taskId) implements AgentEvent {}
    record RepairRetesting(String taskId) implements AgentEvent {}
    record RepairRetrying(String failureSignature, int attempt) implements AgentEvent {}
    record RepairSucceeded(String taskId) implements AgentEvent {}
    record RepairFailed(String failureSignature, String reason) implements AgentEvent {}
    record RepairCancelled(String taskId) implements AgentEvent {}

    record TurnComplete(int turn) implements AgentEvent {}

    record LoopComplete(int totalTurns) implements AgentEvent {}

    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {}

    record ErrorEvent(String message) implements AgentEvent {}

    record CompactEvent(String message) implements AgentEvent {}

    record RetryEvent(String reason, long waitMs) implements AgentEvent {}

    record CheckpointCreated(String checkpointId, String sessionId, String taskId,
                             String boundary, String status) implements AgentEvent {}

    record RecoveryBlocked(String sessionId, String checkpointId, String reason) implements AgentEvent {}

    record PermissionRequestEvent(String toolName, String description,
                                  CompletableFuture<PermissionResponse> future) implements AgentEvent {}

    record AskUserRequestEvent(
            java.util.List<devmesh.tui.dialog.AskUserDialog.Question> questions,
            CompletableFuture<Map<String, String>> future) implements AgentEvent {}
}
