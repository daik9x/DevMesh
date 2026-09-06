package devmesh.agent;

import devmesh.config.ProviderConfig;
import devmesh.conversation.ConversationManager;
import devmesh.conversation.ThinkingBlock;
import devmesh.conversation.ToolResultBlock;
import devmesh.conversation.ToolUseBlock;
import devmesh.hook.HookEngine;
import devmesh.llm.LlmClient;
import devmesh.llm.StreamEvent;
import devmesh.observability.AgentTracer;
import devmesh.observability.ToolSchemaMetrics;
import devmesh.permission.PermissionChecker;
import devmesh.permission.PermissionMode;
import devmesh.plan.PlanFile;
import devmesh.prompt.PlanModePrompt;
import devmesh.tool.ToolRegistry;
import devmesh.toolresult.ContentReplacementRecord;
import devmesh.toolresult.ContentReplacementState;
import devmesh.toolresult.ReplacementRecordsIO;
import devmesh.toolresult.ToolResultBudget;
import devmesh.compact.ContextControlPlane;
import devmesh.compact.ContextCompactor;
import devmesh.compact.RecoveryState;
import devmesh.compact.ContextItem;
import devmesh.compact.ContextLayer;
import devmesh.compact.ContextPriority;
import devmesh.compact.ContextSnapshot;
import devmesh.compact.TokenEstimate;
import devmesh.task.TaskList;
import devmesh.tool.select.ToolIntent;
import devmesh.tool.select.ToolSelectionRequest;
import devmesh.tool.select.ToolSelector;
import devmesh.repository.AnalysisDepth;
import devmesh.repository.RepositoryIntelligence;
import devmesh.repository.RepositoryMap;
import devmesh.checkpoint.Checkpoint;
import devmesh.checkpoint.CheckpointManager;
import devmesh.checkpoint.LocalCheckpointStore;
import devmesh.checkpoint.RepositoryRecoveryState;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;
import java.util.concurrent.*;

public class Agent {

    private static final int MAX_TOKENS_CEILING = 64_000;
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    private final ProviderConfig providerConfig;
    private PermissionChecker checker;
    private HookEngine hookEngine;
    private int maxIterations;
    private String workDir;
    private String agentName = "devmesh";
    /**
     * Session log id for the on-disk transcript. Plumbed so that an in-loop
     * compaction can append a compact_boundary record into the same session file
     * (enabling resume to rebuild the compacted state). Null for sub-agents /
     * one-shot callers that should not write boundaries into the main session.
     */
    private String sessionId;
    private java.util.function.Supplier<List<String>> notificationFn;

    private java.util.function.Predicate<String> toolNameFilter;
    private String instructions = "";
    private String memoryContent = "";


    private CompletableFuture<String> memoryRecallFuture;
    private boolean memoryRecallConsumed;
    private final devmesh.compact.ContextCompactor.AutoCompactTrackingState compactTracking =
            new devmesh.compact.ContextCompactor.AutoCompactTrackingState();

    /**
     * Real API-usage anchor for the compaction decision. Refreshed after each
     * stream ends with the provider-reported usage; null until the first turn
     * reports usage, so the compactor falls back to character estimation on a
     * cold start. See {@link devmesh.compact.ContextCompactor.UsageAnchor}.
     */
    private devmesh.compact.ContextCompactor.UsageAnchor usageAnchor;
    private ContextControlPlane contextControlPlane;
    private RepositoryMap repositoryMap;
    private ToolSelector toolSelector;
    private String taskId;
    private CheckpointManager checkpointManager;

    /**
     * Per-conversation-thread tool-result decision log. Carries across
     * iterations so Anthropic's prompt cache sees byte-stable prefixes.
     * Forks (see {@code AgentTool}) clone this for their child agent.
     */
    private ContentReplacementState replacementState = new ContentReplacementState();

    public ContentReplacementState getReplacementState() { return replacementState; }
    public void setReplacementState(ContentReplacementState state) { this.replacementState = state; }

    /**
     * Holds the snapshots needed to rebuild working context after Layer 2
     * collapses the conversation: most-recent file reads + skill SOPs.
     * Recorded on each ReadFile / skill call; consumed by ContextCompactor
     * when the threshold trips.
     */
    private final devmesh.compact.RecoveryState recoveryState =
            new devmesh.compact.RecoveryState();

    public devmesh.compact.RecoveryState getRecoveryState() { return recoveryState; }
    public ContextControlPlane getContextControlPlane() { return contextControlPlane; }

    private devmesh.filehistory.FileHistory fileHistory;
    public void setFileHistory(devmesh.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public devmesh.filehistory.FileHistory getFileHistory() { return fileHistory; }

    public ToolRegistry getRegistry() { return registry; }
    public String getProtocol() { return protocol; }

    public Agent(LlmClient client, ToolRegistry registry, String protocol, ProviderConfig cfg) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.providerConfig = cfg;
        this.contextWindow = cfg.resolvedContextWindow();
        this.maxOutput = cfg.resolvedMaxOutputTokens();
    }

    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setMaxIterations(int max) { this.maxIterations = max; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public void setAgentName(String agentName) {
        if (agentName != null && !agentName.isBlank()) this.agentName = agentName;
    }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionId() { return sessionId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTaskId() { return taskId; }
    public void setNotificationFn(java.util.function.Supplier<List<String>> fn) { this.notificationFn = fn; }

    public void setToolNameFilter(java.util.function.Predicate<String> filter) { this.toolNameFilter = filter; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public void setMemoryContent(String memoryContent) { this.memoryContent = memoryContent; }
    public void setMemoryRecallFuture(CompletableFuture<String> future) {
        this.memoryRecallFuture = future;
        this.memoryRecallConsumed = false;
    }
    public HookEngine getHookEngine() { return hookEngine; }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        run(conv, queue);
        return queue;
    }


    public void run(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        Thread.startVirtualThread(() -> {
            try {
                agentLoop(conv, queue);
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
    }

    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        AgentTracer tracer = AgentTracer.start(workDir, providerConfig, sessionId, agentName);
        conv.injectLongTermMemory(instructions, memoryContent);
        contextControlPlane = new ContextControlPlane(
            Path.of(workDir == null ? "." : workDir), sessionId, contextWindow, maxOutput);
        repositoryMap = new RepositoryIntelligence(Path.of(workDir == null ? "." : workDir))
            .analyze(AnalysisDepth.STANDARD);
        toolSelector = new ToolSelector(registry, Path.of(workDir == null ? "." : workDir));
        refreshContextState(conv);
        String effectiveTaskId = taskId == null || taskId.isBlank() ? sessionId == null ? "task" : sessionId : taskId;
        checkpointManager = new CheckpointManager(new LocalCheckpointStore(Path.of(workDir == null ? "." : workDir)));
        saveCheckpoint(queue, effectiveTaskId, "TASK_STARTED", 0, "agent_loop_started");

        int totalInput = 0, totalOutput = 0;
        int outputRecoveries = 0;
        boolean maxTokensEscalated = false;

        int contextRetries = 0;
        boolean loopCompleted = false;

        try {
            injectCanarySkill(conv, tracer);
        } catch (RuntimeException e) {
            tracer.markError(e.getClass().getSimpleName());
            putSafe(queue, new AgentEvent.LoopComplete(0));
            tracer.close();
            throw e;
        }

        try {
        for (int iteration = 1; ; iteration++) {
            if (maxIterations > 0 && iteration > maxIterations) {
                tracer.markError("max_iterations");
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }

            if (Thread.currentThread().isInterrupted()) break;

            // Drain background task notifications and inject as system reminders
            if (notificationFn != null) {
                for (String note : notificationFn.get()) {
                    conv.addSystemReminder(note);
                }
            }

            refreshContextState(conv);

            // Compute the tool schemas once per iteration so the recovery
            // attachment (when compact fires) and the Stream call below see
            // the same set. Skill filters can only change between iterations.
            var iterToolSchemas = registry.getAllSchemas(protocol);
            if (toolNameFilter != null) {
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            return name == null || toolNameFilter.test(name.toString());
                        })
                        .toList();
            }

            // Keep loop-level guidance in replaceable context slots. These are
            // sent to the model but never appended to the durable transcript.
            var deferredNames = registry.getDeferredToolNames();
            if (!deferredNames.isEmpty()) {
                var sb = new StringBuilder();
                sb.append("The following deferred tools are available via ToolSearch. ");
                sb.append("Their schemas are NOT loaded - use ToolSearch with ");
                sb.append("query \"select:<name>[,<name>...]\" to load tool schemas before calling them:\n");
                for (var dn : deferredNames) {
                    sb.append(dn).append("\n");
                }
                conv.setEphemeralContext("available-deferred-tools", sb.toString());
            } else {
                conv.removeEphemeralContext("available-deferred-tools");
            }

            // Plan mode: inject structured workflow reminder
            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                String planPath = PlanFile.getOrCreatePlanPath(wd);
                checker.setPlanFilePath(planPath);
                boolean planExists = PlanFile.planExists();
                String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
                conv.setEphemeralContext("plan-mode", reminder);
            } else {
                conv.removeEphemeralContext("plan-mode");
            }


            Path sessionDir = Paths.get(workDir == null ? "." : workDir, ".devmesh/session");
            List<ContentReplacementRecord> newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);
            if (!newRecords.isEmpty()) {
                try {
                    ReplacementRecordsIO.append(sessionDir, newRecords);
                } catch (Exception ignored) {}
            }

            // Layer 2: auto-compact check

            try {
                long compactStarted = tracer.startTimer();
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                int sizeBefore = conv.size();
                String compactMsg = devmesh.compact.ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, compactTracking,
                        recoveryState, iterToolSchemas, usageAnchor,
                        conv.getMessages());
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                    tracer.record("compact_context", "compact_context", "INTERNAL",
                            tracer.rootSpanId(), compactStarted, "OK", Map.of(
                                    "devmesh.agent.iteration", iteration,
                                    "devmesh.context.message_count.before", sizeBefore,
                                    "devmesh.context.message_count.after", conv.size()));
                }

                if (conv.size() < sizeBefore) {
                    contextControlPlane.markCompaction();
                    usageAnchor = null;
                    conv.resetLtmInjected();
                    conv.injectLongTermMemory(instructions, memoryContent);

                    newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);
                }
            } catch (Exception ignored) {}

            int historyTokens = devmesh.compact.ContextCompactor.estimateTokens(conv.getMessages());
            int requestTokens = devmesh.compact.ContextCompactor.estimateTokens(conv.getMessagesForModel());
            int ephemeralTokens = Math.max(0, requestTokens - historyTokens);
            int toolSchemaTokens = estimateSchemaTokens(iterToolSchemas);
            int estimatedContextTokens = requestTokens + toolSchemaTokens;
            tracer.event("context_budget", "context_budget", Map.of(
                    "devmesh.agent.iteration", iteration,
                    "devmesh.context.history_tokens", historyTokens,
                    "devmesh.context.ephemeral_tokens", ephemeralTokens,
                    "devmesh.context.tool_schema_tokens", toolSchemaTokens,
                    "devmesh.context.estimated_tokens", estimatedContextTokens,
                    "devmesh.context.window_tokens", contextWindow,
                    "devmesh.context.pressure", contextWindow > 0
                            ? (double) estimatedContextTokens / contextWindow : 0.0));

            var tools = iterToolSchemas;
                if (toolSelector != null) {
                String latestRequest = conv.getMessages().stream()
                    .filter(message -> "user".equals(message.getRole()))
                    .map(devmesh.conversation.Message::getContent)
                    .filter(java.util.Objects::nonNull)
                    .reduce((first, second) -> second).orElse("");
                ToolIntent intent = latestRequest.toLowerCase().contains("test")
                    ? ToolIntent.RUN_TEST : latestRequest.toLowerCase().contains("build")
                    ? ToolIntent.RUN_BUILD : latestRequest.toLowerCase().contains("find")
                    || latestRequest.toLowerCase().contains("search") ? ToolIntent.SEARCH_CODE : ToolIntent.UNKNOWN;
                var selected = toolSelector.select(new ToolSelectionRequest(intent, Map.of(),
                    devmesh.platform.OperatingSystem.detect(), checker, java.util.Set.of()));
                if (selected != null) {
                    conv.setEphemeralContext("tool-selection", "Selected " + selected.tool().name()
                        + " for " + intent + ": " + selected.reason());
                }
                }
            long inferenceStarted = tracer.startTimer();
            var streamQueue = client.stream(conv, tools);

            // Consume stream events, collect tool calls
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolCalls = new ArrayList<ToolCallInfo>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            boolean streamError = false;

            while (true) {
                StreamEvent event;
                try {
                    event = streamQueue.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (event == null) {
                    tracer.markError("stream_timeout");
                    tracer.record("chat", "chat " + providerConfig.getModel(), "CLIENT",
                            tracer.rootSpanId(), inferenceStarted, "ERROR", Map.of(
                                    "error.type", "stream_timeout",
                                    "devmesh.agent.iteration", iteration));
                    putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                    return;
                }

                switch (event) {
                    case StreamEvent.TextDelta td -> {
                        text.append(td.text());
                        putSafe(queue, new AgentEvent.StreamText(td.text()));
                    }
                    case StreamEvent.ThinkingDelta td ->
                            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                    }
                    case StreamEvent.ToolCallStart tcs ->
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), Map.of()));
                    case StreamEvent.ToolCallDelta tcd -> {}
                    case StreamEvent.ToolCallComplete tcc -> {
                        toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        putSafe(queue, new AgentEvent.ToolUseEvent(
                                tcc.toolId(), tcc.toolName(), tcc.arguments()));
                    }
                    case StreamEvent.StreamEnd se -> {
                        stopReason = se.stopReason();
                        turnInput = se.inputTokens();
                        turnOutput = se.outputTokens();
                        turnCacheRead = se.cacheReadTokens();
                        turnCacheCreation = se.cacheCreationTokens();
                    }
                    case StreamEvent.Error err -> {
                        lastStreamError = err.message();
                        putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
                        streamError = true;
                    }
                }

                if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
            }

            var inferenceAttributes = new LinkedHashMap<String, Object>();
            inferenceAttributes.put("devmesh.agent.iteration", iteration);
            inferenceAttributes.put("gen_ai.usage.input_tokens", turnInput + turnCacheRead + turnCacheCreation);
            inferenceAttributes.put("gen_ai.usage.output_tokens", turnOutput);
            inferenceAttributes.put("gen_ai.usage.cache_read.input_tokens", turnCacheRead);
            inferenceAttributes.put("gen_ai.usage.cache_creation.input_tokens", turnCacheCreation);
            inferenceAttributes.put("gen_ai.response.finish_reasons", List.of(stopReason));
            if (streamError) inferenceAttributes.put("error.type", "model_stream_error");
            tracer.record("chat", "chat " + providerConfig.getModel(), "CLIENT",
                    tracer.rootSpanId(), inferenceStarted, streamError ? "ERROR" : "OK",
                    inferenceAttributes);

            // Error recovery
            if (streamError) {
                var lastErr = events_drain_last_error(queue);
                if (lastErr != null && (lastErr.contains("context") || lastErr.contains("too long")
                        || lastErr.contains("prompt"))) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        tracer.event("retry", "retry context_overflow", Map.of(
                                "devmesh.agent.iteration", iteration,
                                "devmesh.retry.reason", "context_overflow",
                                "devmesh.retry.attempt", contextRetries));
                        putSafe(queue, new AgentEvent.RetryEvent("Context too long, compacting...", 0));

                        Path forceSessionDir = Paths.get(workDir == null ? "." : workDir, ".devmesh/session");
                        List<ContentReplacementRecord> forceRecords = ToolResultBudget.apply(conv, forceSessionDir, replacementState);
                        if (!forceRecords.isEmpty()) {
                            try { ReplacementRecordsIO.append(forceSessionDir, forceRecords); } catch (Exception ignored) {}
                        }
                        int sizeBeforeForce = conv.size();
                        try {
                            String wdForce = workDir != null ? workDir : System.getProperty("user.dir");
                            devmesh.compact.ContextCompactor.forceCompact(
                                    conv, client, contextWindow, wdForce, sessionId,
                                    recoveryState, iterToolSchemas,
                                    conv.getMessages());
                        } catch (Exception ignored) {}
                        // forceCompact shrinks the conversation (summary + kept
                        // tail), so the prior anchor's message count no longer
                        // lines up; drop it and re-anchor on the next stream.
                        if (conv.size() < sizeBeforeForce) {
                            usageAnchor = null;
                            conv.resetLtmInjected();
                            conv.injectLongTermMemory(instructions, memoryContent);
                        }
                        continue;
                    }
                }
                if (lastErr != null && lastErr.toLowerCase().contains("rate limit")) {
                    tracer.event("retry", "retry rate_limit", Map.of(
                            "devmesh.agent.iteration", iteration,
                            "devmesh.retry.reason", "rate_limit",
                            "devmesh.retry.wait_ms", 5000));
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                    continue;
                }
                tracer.markError("model_stream_error");
                break;
            }

            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));

            // Max tokens handling
            if ("max_tokens".equals(stopReason)) {
                if (!maxTokensEscalated) {
                    maxTokensEscalated = true;
                    client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                    if (!text.isEmpty()) {
                        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Do not apologize or repeat previous content. Pick up mid-thought if needed.");
                    }
                    putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
                    tracer.event("retry", "retry max_tokens", Map.of(
                            "devmesh.agent.iteration", iteration,
                            "devmesh.retry.reason", "max_tokens_escalation"));
                    continue;
                } else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                    outputRecoveries++;
                    conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                    conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Break remaining work into smaller pieces.");
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "max_tokens recovery %d/%d".formatted(outputRecoveries, MAX_OUTPUT_RECOVERIES), 0));
                    tracer.event("retry", "retry max_tokens", Map.of(
                            "devmesh.agent.iteration", iteration,
                            "devmesh.retry.reason", "max_tokens_recovery",
                            "devmesh.retry.attempt", outputRecoveries));
                    continue;
                }
                // Exhausted: fall through to normal completion
            } else {
                outputRecoveries = 0;
            }

            // Save assistant message to conversation
            var toolUseBlocks = toolCalls.stream()
                    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
                    .toList();
            conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);

            // Re-anchor the compaction estimate on this turn's real usage. The
            // baseline = input + cacheRead + cacheCreation + output covers the
            // sent context and the assistant message just appended; messages
            // added after this point (tool results, next user turn) are
            // estimated incrementally on top. A cache hit reports a small real
            // input, so the anchor tracks the true window far better than the
            // raw character estimate.
            if (turnInput > 0 || turnOutput > 0 || turnCacheRead > 0 || turnCacheCreation > 0) {
                int baseline = turnInput + turnCacheRead + turnCacheCreation + turnOutput;
                usageAnchor = new devmesh.compact.ContextCompactor.UsageAnchor(
                        baseline, conv.size());
            }


            if (toolCalls.isEmpty()) {
                if (fileHistory != null) {
                    String summary = text.length() > 60 ? text.substring(0, 60) + "..." : text.toString();
                    fileHistory.makeSnapshot(conv.size(), summary);
                }
                saveCheckpoint(queue, effectiveTaskId, "TASK_COMPLETED", iteration, "assistant_completed");

                // (agent.go emits only LoopComplete here). The TUI's TurnComplete
                // handler flushes+clears streamBuf without persisting; if we emitted
                // it first, LoopComplete would see an empty buffer and the final
                // assistant message would never be saved to the session file.
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            // Execute tool calls
            var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState,
                    tracer, iteration);
            var callInfos = toolCalls.stream()
                    .map(tc -> new StreamingExecutor.ToolCallInfo(tc.toolId, tc.toolName, tc.args))
                    .toList();
            var results = executor.executeAll(callInfos);

            // Add results to conversation
            var resultBlocks = results.stream()
                    .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
                    .toList();
            conv.addToolResultsMessage(resultBlocks);
            saveCheckpoint(queue, effectiveTaskId, "TOOL_COMPLETED", iteration, "tool_batch_completed");



            if (memoryRecallFuture != null && !memoryRecallConsumed) {
                if (memoryRecallFuture.isDone()) {
                    try {
                        String recall = memoryRecallFuture.getNow("");
                        if (recall != null && !recall.isEmpty()) {
                            conv.addSystemReminder(recall);
                        }
                    } catch (Exception ignored) {}
                    memoryRecallConsumed = true;
                }
            }

            boolean exitPlanCalled = toolCalls.stream()
                    .anyMatch(tc -> "ExitPlanMode".equals(tc.toolName));
            if (exitPlanCalled) {
                saveCheckpoint(queue, effectiveTaskId, "TASK_COMPLETED", iteration, "plan_exited");
                putSafe(queue, new AgentEvent.TurnComplete(iteration));
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            putSafe(queue, new AgentEvent.TurnComplete(iteration));
            saveCheckpoint(queue, effectiveTaskId, "TURN_COMPLETED", iteration, "turn_completed");
        }
        } catch (RuntimeException e) {
            tracer.markError(e.getClass().getSimpleName());
            saveCheckpoint(queue, effectiveTaskId, "TASK_FAILED", 0, e.getClass().getSimpleName());
            throw e;
        } finally {
            if (!loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
            tracer.close();
        }
    }

    private void saveCheckpoint(BlockingQueue<AgentEvent> queue, String effectiveTaskId,
                                String boundary, int iteration, String reason) {
        if (checkpointManager == null || sessionId == null || sessionId.isBlank()) return;
        var execution = new LinkedHashMap<String, Object>();
        execution.put("boundary", boundary); execution.put("iteration", iteration); execution.put("reason", reason);
        execution.put("workDir", workDir == null ? System.getProperty("user.dir") : workDir);
        var repository = new LinkedHashMap<String, Object>();
        repository.put("root", workDir == null ? System.getProperty("user.dir") : workDir);
        if (repositoryMap != null) { repository.put("projectType", repositoryMap.projectType()); repository.put("importantFiles", repositoryMap.importantFiles()); }
        var context = new LinkedHashMap<String, Object>();
        if (contextControlPlane != null) { context.put("snapshot", contextControlPlane.snapshot()); context.put("compactions", contextControlPlane.compactions()); }
        var todo = new LinkedHashMap<String, Object>();
        todo.put("tasks", new TaskList("default", workDir == null ? System.getProperty("user.dir") : workDir).list());
        Checkpoint parent = checkpointManager.latestValid(sessionId).orElse(null);
        var draft = new Checkpoint(java.util.UUID.randomUUID().toString(), sessionId, effectiveTaskId,
                parent == null ? null : parent.checkpointId(), java.time.Instant.now(), 1, Checkpoint.Status.CREATED,
                Map.<String, Object>of("taskId", effectiveTaskId), todo, Map.<String, Object>of("phase", boundary, "iteration", iteration), context,
                repository, Map.of(), Map.of(), Map.of(), execution, Map.<String, Object>of("reason", reason), null);
        Checkpoint saved = checkpointManager.create(draft);
        putSafe(queue, new AgentEvent.CheckpointCreated(saved.checkpointId(), saved.sessionId(), saved.taskId(), boundary, saved.status().name()));
    }

    private static int estimateSchemaTokens(List<Map<String, Object>> schemas) {
        return ToolSchemaMetrics.estimateTokens(schemas);
    }

        private void refreshContextState(ConversationManager conv) {
        if (contextControlPlane == null) return;
        var tasks = new TaskList("default", workDir == null ? System.getProperty("user.dir") : workDir).list();
        String task = conv.getMessages().stream()
            .filter(message -> "user".equals(message.getRole()))
            .map(devmesh.conversation.Message::getContent)
            .filter(content -> content != null && !content.isBlank())
            .findFirst().orElse("");
        var completed = tasks.stream().filter(t -> TaskList.Status.COMPLETED.value().equals(t.getStatus()))
            .map(TaskList.Task::getSubject).toList();
        var pending = tasks.stream().filter(t -> !TaskList.Status.COMPLETED.value().equals(t.getStatus()))
            .map(TaskList.Task::getSubject).toList();
        var active = tasks.stream().filter(t -> TaskList.Status.IN_PROGRESS.value().equals(t.getStatus()))
            .map(TaskList.Task::getSubject).findFirst().orElse("");
        var repairHistory = tasks.stream()
            .filter(t -> t.getMetadata() != null && Boolean.TRUE.equals(t.getMetadata().get("repair")))
            .map(t -> t.getSubject() + "=" + t.getStatus()).toList();
        var files = recoveryState.snapshotFiles(ContextCompactor.RECOVERY_FILE_LIMIT).stream()
            .map(RecoveryState.FileReadRecord::path).toList();
        contextControlPlane.setSnapshot(new ContextSnapshot(task, active, List.of(), completed, pending,
            files, List.of(), repairHistory, List.of(), List.of(), Map.of("model", providerConfig.getModel())));
        int estimate = ContextCompactor.estimateTokens(conv.getMessagesForModel());
        contextControlPlane.put(new ContextItem("conversation", ContextLayer.TOOL, ContextPriority.P3_LOW,
            "conversation", new TokenEstimate(estimate, TokenEstimate.Confidence.ESTIMATED), false));
        if (repositoryMap != null) {
            String repositorySummary = "Project: " + repositoryMap.projectType()
                + "\nLanguages: " + String.join(", ", repositoryMap.languages())
                + "\nBuild systems: " + String.join(", ", repositoryMap.buildSystems())
                + "\nSource roots: " + String.join(", ", repositoryMap.sourceRoots())
                + "\nTest roots: " + String.join(", ", repositoryMap.testRoots())
                + "\nImportant files: " + String.join(", ", repositoryMap.importantFiles());
            contextControlPlane.put(new ContextItem("repository-map", ContextLayer.REPOSITORY,
                ContextPriority.P2_MEDIUM, repositorySummary,
                new TokenEstimate(ContextCompactor.estimateTokens(List.of(new devmesh.conversation.Message("user", repositorySummary))),
                    TokenEstimate.Confidence.ESTIMATED), false));
        }
        conv.setEphemeralContext("persistent-execution-state", contextControlPlane.renderPersistentState());
        }

    private void injectCanarySkill(ConversationManager conv, AgentTracer tracer) {
        String candidateId = System.getProperty("devmesh.canary.skill");
        if (candidateId == null || candidateId.isBlank()) {
            candidateId = System.getenv("DEVMESH_CANARY_SKILL");
        }
        if (candidateId == null || candidateId.isBlank()) return;
        try {
            String wd = workDir == null || workDir.isBlank() ? System.getProperty("user.dir") : workDir;
            var store = new devmesh.evolution.SkillEvolutionStore(Path.of(wd));
            var candidate = store.loadCandidate(candidateId);
            var status = store.statusOf(candidateId);
            if (status == devmesh.evolution.SkillCandidate.Status.PROMOTED
                    || status == devmesh.evolution.SkillCandidate.Status.ROLLED_BACK) {
                throw new IllegalStateException("Candidate is in terminal status " + status
                        + "; use the active Skill or create a new candidate version");
            }
            conv.setEphemeralContext("canary-skill:" + candidateId, candidate.renderCanaryContext());
            tracer.event("skill_canary", "skill_canary " + candidate.name(), Map.of(
                    "devmesh.skill.candidate_id", candidate.id(),
                    "devmesh.skill.name", candidate.name(),
                    "devmesh.skill.version", candidate.version(),
                    "devmesh.skill.content_hash", candidate.contentHash(),
                    "devmesh.skill.status", status.name()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot inject canary Skill '" + candidateId + "': " + e.getMessage(), e);
        }
    }

    private String lastStreamError;

    private String events_drain_last_error(BlockingQueue<AgentEvent> queue) {
        return lastStreamError;
    }

    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    private record ToolCallResult(String toolId, String output, boolean isError) {}
}
