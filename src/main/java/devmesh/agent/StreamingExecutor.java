package devmesh.agent;

import devmesh.compact.RecoveryState;
import devmesh.hook.HookEngine;
import devmesh.observability.AgentTracer;
import devmesh.permission.PermissionChecker;
import devmesh.permission.PermissionResponse;
import devmesh.tool.Tool;
import devmesh.tool.ToolCategory;
import devmesh.tool.ToolRegistry;
import devmesh.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent tool executor that partitions tool calls into read-only (parallel)
 * and write/command (sequential) batches.
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    private final PermissionChecker checker;

    private final HookEngine hookEngine;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final RecoveryState recoveryState;
    private final AgentTracer tracer;
    private final int iteration;

    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    public record ToolExecResult(String toolId, String output, boolean isError) {}

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue) {
        this(registry, checker, hookEngine, eventQueue, null, null, 0);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState) {
        this(registry, checker, hookEngine, eventQueue, recoveryState, null, 0);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState, AgentTracer tracer, int iteration) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
        this.tracer = tracer;
        this.iteration = iteration;
    }

    public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {

        var batches = partitionToolCalls(calls);
        var results = new ArrayList<ToolExecResult>();

        for (var batch : batches) {
            if (batch.concurrent && batch.calls.size() > 1) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = batch.calls.stream()
                            .map(call -> executor.submit(() -> executeSingle(call)))
                            .toList();
                    for (int i = 0; i < futures.size(); i++) {
                        var future = futures.get(i);
                        try { results.add(future.get()); }
                        catch (Exception e) {
                            var call = batch.calls.get(i);
                            String message = "Tool execution failed: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), message, true, 0));
                            results.add(new ToolExecResult(call.toolId(), message, true));
                        }
                    }
                }
            } else {
                for (var call : batch.calls) results.add(executeSingle(call));
            }
        }

        return results;
    }

    private record ToolBatch(boolean concurrent, List<ToolCallInfo> calls) {}

    private List<ToolBatch> partitionToolCalls(List<ToolCallInfo> calls) {
        var batches = new ArrayList<ToolBatch>();
        for (var call : calls) {
            var tool = registry.get(call.toolName());
            boolean safe = tool != null && tool.category() == ToolCategory.READ;

            if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
                batches.getLast().calls().add(call);
            } else {
                batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    private ToolExecResult executeSingle(ToolCallInfo call) {
        long traceStarted = tracer != null ? tracer.startTimer() : 0L;
        Tool tool = registry.get(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "Unknown tool", true, 0));
            traceTool(call, null, traceStarted, true, "unknown_tool");
            return new ToolExecResult(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
        }


        if (checker != null) {
            var check = checker.check(tool, call.args());
            switch (check.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + check.reason();
                    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                    traceTool(call, tool, traceStarted, true, "permission_denied");
                    return new ToolExecResult(call.toolId(), msg, true);
                }
                case ASK -> {
                    var future = new CompletableFuture<PermissionResponse>();
                    String desc = checker.describeToolAction(call.toolName(), call.args());
                    putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
                    PermissionResponse response;
                    try {
                        response = future.get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        response = PermissionResponse.DENY;
                    }
                    if (response == PermissionResponse.DENY) {
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(), "Permission denied by user", true, 0));
                        traceTool(call, tool, traceStarted, true, "permission_denied_by_user");
                        return new ToolExecResult(call.toolId(), "User denied permission", true);
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = extractContent(call.toolName(), call.args());
                        if (content != null) {
                            checker.addAllowAlwaysRule(call.toolName(), content);
                        }
                    }
                }
                case ALLOW -> {}
            }
        }


        if (hookEngine != null) {
            var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.args());
            if (hookResult.rejected()) {
                String msg = "Rejected by hook: " + hookResult.message();
                putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                traceTool(call, tool, traceStarted, true, "hook_rejected");
                return new ToolExecResult(call.toolId(), msg, true);
            }
        }

        long start = System.nanoTime();
        ToolResult result;
        try {
            if (tool instanceof devmesh.tool.CommandEventEmitter emitter) {
                emitter.setEventSink(this::putSafe);
            }
            result = tool.execute(call.args());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        snapshotForRecovery(call, result);

        String output = result.output();
        if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
            output = output.substring(0, ToolRegistry.MAX_OUTPUT_CHARS) + "\n... (truncated)";
        }

        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));
        traceTool(call, tool, traceStarted, result.isError(),
                result.isError() ? "tool_execution_error" : "");

        // Post-tool hooks
        if (hookEngine != null) {
            var ctx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.args(), null, null, null);
            hookEngine.runHooks(ctx);
        }

        return new ToolExecResult(call.toolId(), output, result.isError());
    }

    private void traceTool(ToolCallInfo call, Tool tool, long started, boolean error, String errorType) {
        if (tracer == null) return;
        var attrs = new java.util.LinkedHashMap<String, Object>();
        attrs.put("gen_ai.tool.name", call.toolName());
        attrs.put("gen_ai.tool.call.id", call.toolId());
        attrs.put("gen_ai.tool.type", call.toolName().startsWith("mcp__") ? "mcp" : "agent_side");
        attrs.put("devmesh.agent.iteration", iteration);
        attrs.put("devmesh.tool.category", tool != null ? tool.category().name().toLowerCase() : "unknown");
        attrs.put("devmesh.tool.argument_keys", call.args() == null
                ? List.of() : call.args().keySet().stream().sorted().toList());
        if (error) attrs.put("error.type", errorType);
        tracer.record("execute_tool", "execute_tool " + call.toolName(), "INTERNAL",
                tracer.rootSpanId(), started, error ? "ERROR" : "OK", attrs);
    }

    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Capture what ReadFile just returned so the compact recovery block
     * can replay it after a Layer 2 summary wipes the transcript.
     * Re-reads from disk to keep the snapshot independent of how the tool
     * formats its output (e.g. line-number prefixes).
     */
    private void snapshotForRecovery(ToolCallInfo call, ToolResult result) {
        if (recoveryState == null || result.isError()) return;
        if (!"ReadFile".equals(call.toolName())) return;
        Object pathObj = call.args() == null ? null : call.args().get("file_path");
        if (!(pathObj instanceof String) || ((String) pathObj).isEmpty()) return;
        String path = (String) pathObj;
        try {
            String content = Files.readString(Path.of(path));
            recoveryState.recordFileRead(path, content);
        } catch (IOException ignored) {
            // Best-effort snapshot; if the file vanished between the tool

            // already saw.
        }
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = switch (toolName) {
            case "Bash" -> "command";
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            default -> null;
        };
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }
}
