package devmesh.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import devmesh.agent.Agent;
import devmesh.agent.AgentEvent;
import devmesh.command.Command;
import devmesh.command.CommandContext;
import devmesh.command.CommandRegistry;
import devmesh.compact.ContextCompactor;
import devmesh.config.HookConfig;
import devmesh.config.McpServerConfig;
import devmesh.config.ProviderConfig;
import devmesh.conversation.ConversationManager;
import devmesh.filehistory.FileHistory;
import devmesh.hook.HookEngine;
import devmesh.llm.LlmClient;
import devmesh.mcp.McpManager;
import devmesh.memory.MemoryManager;
import devmesh.permission.PermissionChecker;
import devmesh.permission.PermissionMode;
import devmesh.permission.PermissionResponse;
import devmesh.plan.PlanFile;
import devmesh.prompt.PromptBuilder;
import devmesh.session.SessionManager;
import devmesh.skill.SkillCatalog;
import devmesh.subagent.AgentTool;
import devmesh.subagent.SubAgentTaskManager;
import devmesh.task.TaskList;
import devmesh.task.TaskTools;
import devmesh.teams.TeamManager;
import devmesh.tool.ToolRegistry;
import devmesh.tool.impl.AskUserTool;
import devmesh.tool.impl.ToolSearchTool;
import devmesh.tui.dialog.AskUserDialog;
import devmesh.worktree.WorktreeManager;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 */
public class RemoteServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpConfigs;
    private final List<HookConfig> hookConfigs;
    private final String addr;


    private final ReentrantLock connLock = new ReentrantLock();
    private final Set<WsContext> connections = ConcurrentHashMap.newKeySet();


    private Agent agent;
    private ConversationManager conversation;
    private ToolRegistry registry;
    private LlmClient client;
    private String sessionId;
    private FileHistory fileHistory;
    private PermissionChecker permChecker;


    private volatile boolean streaming;
    private volatile Thread streamThread;
    private BlockingQueue<AgentEvent> agentQueue;


    private final ReentrantLock pendingPermLock = new ReentrantLock();
    private final Map<String, CompletableFuture<PermissionResponse>> pendingPerms = new ConcurrentHashMap<>();

    private final ReentrantLock pendingAskLock = new ReentrantLock();
    private final Map<String, CompletableFuture<Map<String, String>>> pendingAsks = new ConcurrentHashMap<>();


    private CommandRegistry cmdRegistry;
    private SkillCatalog skillCatalog;
    private MemoryManager memoryManager;
    private McpManager mcpManager;
    private TaskList taskList;
    private SubAgentTaskManager subAgentTaskManager;
    private TeamManager teamManager;
    private AskUserTool askUserTool;
    private HookEngine hookEngine;

    private String instructionsContent = "";
    private String memoryContent = "";
    private volatile String mcpInstructions = "";
    private final boolean enableCoordinatorMode;

    public RemoteServer(List<ProviderConfig> providers, List<McpServerConfig> mcpConfigs,
                        List<HookConfig> hookConfigs, String addr, boolean enableCoordinatorMode) {
        this.providers = providers;
        this.mcpConfigs = mcpConfigs;
        this.hookConfigs = hookConfigs;
        this.addr = addr;
        this.enableCoordinatorMode = enableCoordinatorMode;
    }

    /**
     */
    public void run() throws Exception {

        initAgent();

        initMcpServers();

        // Parse the listen address (for example, ":18888" or "0.0.0.0:18888").
        int port = parsePort(addr);

        Javalin app = Javalin.create()
                .get("/", ctx -> {
                    ctx.contentType("text/html; charset=utf-8");
                    ctx.result(WebContent.INDEX_HTML);
                })
                .ws("/ws", ws -> {
                    ws.onConnect(ctx -> {
                        connections.add(ctx);

                        broadcast(Map.of(
                                "type", "connected",
                                "data", Map.of(
                                        "session", sessionId
                                )
                        ));

                        broadcast(Map.of(
                                "type", "commands",
                                "data", buildCommandList()
                        ));
                    });
                    ws.onClose(ctx -> connections.remove(ctx));
                    ws.onMessage(ctx -> handleWsMessage(ctx, ctx.message()));
                })
                .start("0.0.0.0", port);

        System.out.printf("%n  Remote UI: http://localhost:%d%n%n", port);


        Thread.currentThread().join();
    }





    private void initAgent() {
        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = providers.get(0);


        memoryManager = new MemoryManager(workDir);
        instructionsContent = MemoryManager.loadInstructions(workDir);


        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);


        client = LlmClient.create(providerCfg, systemPrompt);
        String protocol = providerCfg.getProtocol();


        registry = ToolRegistry.createDefault();
        registry.register(new ToolSearchTool(registry, protocol));

        var exitPlanTool = new devmesh.tool.impl.ExitPlanModeTool();
        exitPlanTool.setIsPlanMode(() -> permChecker != null && permChecker.getMode() == PermissionMode.PLAN);
        exitPlanTool.setPlanExists(() -> PlanFile.planExists());
        registry.register(exitPlanTool);


        askUserTool = new AskUserTool();
        registry.register(askUserTool);


        var agentToolRef = new AgentTool(client, registry, protocol, providerCfg);
        subAgentTaskManager = new SubAgentTaskManager();
        agentToolRef.setTaskManager(subAgentTaskManager);
        registry.register(agentToolRef);


        var worktreeManager = new WorktreeManager(workDir, List.of(), 720);
        agentToolRef.setWorktreeManager(worktreeManager);
        sessionId = SessionManager.newId();
        registry.register(new devmesh.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new devmesh.tool.impl.ExitWorktreeTool(worktreeManager));


        taskList = new TaskList("default", workDir);
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));


        teamManager = new TeamManager();
        agentToolRef.setTeamManager(teamManager);
        registry.register(new devmesh.teams.TeamTools.TeamCreateTool(teamManager));
        registry.register(new devmesh.teams.TeamTools.TeamDeleteTool(teamManager));
        registry.register(new devmesh.teams.TeamTools.SendMessageTool(teamManager, "lead"));


        permChecker = new PermissionChecker(PermissionMode.DEFAULT, Path.of(workDir));


        fileHistory = new FileHistory(workDir, sessionId);
        var fileStateCache = new devmesh.tool.FileStateCache();
        for (var tool : registry.listTools()) {
            if (tool instanceof devmesh.tool.impl.EditFileTool ef) {
                ef.setFileHistory(fileHistory);
                ef.setFileStateCache(fileStateCache);
            }
            if (tool instanceof devmesh.tool.impl.WriteFileTool wf) {
                wf.setFileHistory(fileHistory);
                wf.setFileStateCache(fileStateCache);
            }
            if (tool instanceof devmesh.tool.impl.ReadFileTool rf) {
                rf.setFileStateCache(fileStateCache);
            }
        }


        conversation = new ConversationManager();
        agent = new Agent(client, registry, protocol, providerCfg);
        agent.setFileHistory(fileHistory);
        agent.setInstructions(instructionsContent);
        agent.setMemoryContent(memoryContent);
        agent.setChecker(permChecker);
        agent.setWorkDir(workDir);
        agent.setSessionId(sessionId);


        agent.setNotificationFn(() -> {
            var notes = new ArrayList<String>();
            notes.addAll(devmesh.teams.TeammateRunner.drainLeadMailbox(teamManager));
            if (subAgentTaskManager != null) {
                for (var n : subAgentTaskManager.drainNotifications()) {
                    notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                            .formatted(n.taskId(), n.name(), n.status()));
                }
            }
            return notes;
        });


        agent.setToolNameFilter(name -> {
            if (!enableCoordinatorMode) return true;
            if (teamManager.listTeams().isEmpty()) return true;
            return devmesh.teams.Coordinator.isCoordinatorTool(name);
        });


        if (registry.get("Agent") instanceof AgentTool at) {
            at.setProgressListener(progress -> {});
        }


        hookEngine = new HookEngine();
        if (hookConfigs != null && !hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());
                Duration timeout = hc.getTimeout() > 0
                        ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                        hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                        hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError());
            }).toList();
            hookEngine.loadHooks(hooks);
        }
        agent.setHookEngine(hookEngine);


        skillCatalog = new SkillCatalog();
        var skillDir = Path.of(workDir, ".devmesh", "skills");
        if (Files.isDirectory(skillDir)) {
            skillCatalog.loadFromDirectory(skillDir);
        }


        cmdRegistry = new CommandRegistry();
    }





    private void initMcpServers() {
        if (mcpConfigs == null || mcpConfigs.isEmpty()) return;

        try {
            mcpManager = new McpManager(mcpConfigs);
            var result = mcpManager.connectAll();
            for (var t : result.tools()) registry.register(t);
            for (var e : result.errors()) System.err.println("MCP error: " + e);


            if (!result.servers().isEmpty()) {
                var mcpParts = new ArrayList<String>();
                for (var s : result.servers()) {
                    var sb = new StringBuilder();
                    sb.append("## ").append(s.name()).append("\n");
                    if (s.instructions() != null && !s.instructions().isBlank()) {
                        sb.append(s.instructions()).append("\n");
                    }
                    var toolNames = registry.listTools().stream()
                            .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                            .map(devmesh.tool.Tool::name)
                            .toList();
                    if (!toolNames.isEmpty()) {
                        sb.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                    }
                    mcpParts.add(sb.toString());
                }
                mcpInstructions = "# MCP Server Instructions\n\n"
                        + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                        + String.join("\n\n", mcpParts);
            }
        } catch (Exception e) {
            System.err.println("MCP init failed: " + e.getMessage());
        }
    }





    @SuppressWarnings("unchecked")
    private void handleWsMessage(WsContext ctx, String raw) {
        try {
            var msg = MAPPER.readValue(raw, Map.class);
            String type = (String) msg.get("type");
            Object data = msg.get("data");

            switch (type) {
                case "user_message" -> {
                    if (data instanceof Map<?, ?> d) {
                        String content = (String) d.get("content");

                        Thread.startVirtualThread(() -> handleUserMessage(content));
                    }
                }
                case "permission_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        String response = (String) d.get("response");
                        handlePermissionResponse(id, response);
                    }
                }
                case "ask_user_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        @SuppressWarnings("unchecked")
                        Map<String, String> answers = (Map<String, String>) d.get("answers");
                        handleAskUserResponse(id, answers);
                    }
                }
                case "cancel" -> {

                    Thread t = streamThread;
                    if (t != null) t.interrupt();
                }
                case "ping" -> {

                    broadcast(Map.of("type", "pong"));
                }
            }
        } catch (Exception e) {
            System.err.println("WebSocket message parse error: " + e.getMessage());
        }
    }





    private void handleUserMessage(String content) {
        if (streaming) return;

        content = content != null ? content.strip() : "";
        if (content.isEmpty()) return;


        if (content.startsWith("/")) {
            handleSlashCommand(content);
            return;
        }

        streaming = true;
        streamThread = Thread.currentThread();
        String workDir = System.getProperty("user.dir");
        SessionManager.saveMessage(workDir, sessionId, "user", content);
        conversation.addUserMessage(content);


        if (!mcpInstructions.isEmpty()) {
            conversation.addSystemReminder(mcpInstructions);
            mcpInstructions = "";
        }


        agentQueue = agent.run(conversation);
        if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

        try {
            consumeAgentEvents();
        } finally {
            streaming = false;
            streamThread = null;
            agentQueue = null;
        }
    }





    private void handleSlashCommand(String input) {
        try {

            String trimmed = input.substring(1).strip();
            String name, args;
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx < 0) {
                name = trimmed;
                args = "";
            } else {
                name = trimmed.substring(0, spaceIdx);
                args = trimmed.substring(spaceIdx + 1).strip();
            }

            if (name.isEmpty()) return;

            var cmd = cmdRegistry.find(name);
            if (cmd.isEmpty()) {
                broadcast(Map.of("type", "error", "data",
                        Map.of("message", "Unknown command: /" + name + " -- type /help to see available commands")));
                broadcast(Map.of("type", "command_done"));
                return;
            }

            Command c = cmd.get();
            var ctx = buildCommandContext(args);

            switch (c.type()) {
                case LOCAL -> {
                    String result = cmdRegistry.execute(name, ctx);
                    if (result != null && !result.isEmpty()) {
                        broadcast(Map.of("type", "system", "data", Map.of("message", result)));
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case LOCAL_UI -> {
                    switch (name) {
                        case "clear" -> {
                            conversation = new ConversationManager();
                            broadcast(Map.of("type", "clear"));
                        }
                        case "compact" -> {
                            handleCompact();
                            return;
                        }
                        case "plan" -> handlePlan(args);
                        case "resume" -> {
                            handleResume(args);
                            return;
                        }
                        case "rewind" -> broadcast(Map.of("type", "system", "data",
                                Map.of("message", "Rewind is not yet supported in remote mode.")));
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case PROMPT -> {
                    String prompt = cmdRegistry.execute(name, ctx);
                    if (prompt == null || prompt.isBlank()) return;

                    String displayText = "/" + name;
                    if (!args.isEmpty()) displayText += " " + args;


                    streaming = true;
                    streamThread = Thread.currentThread();
                    String workDir = System.getProperty("user.dir");
                    SessionManager.saveMessage(workDir, sessionId, "user", displayText);
                    conversation.addUserMessage(prompt);

                    if (!mcpInstructions.isEmpty()) {
                        conversation.addSystemReminder(mcpInstructions);
                        mcpInstructions = "";
                    }

                    agentQueue = agent.run(conversation);
                    if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

                    try {
                        consumeAgentEvents();
                    } finally {
                        streaming = false;
                        streamThread = null;
                        agentQueue = null;
                    }
                }
            }
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Command error: " + e.getMessage())));
        }
    }

    private CommandContext buildCommandContext(String args) {
        String workDir = System.getProperty("user.dir");
        String model = providers.get(0).getModel();
        return new CommandContext(
                args,
                workDir,
                model,
                () -> permChecker != null ? permChecker.getMode().name().toLowerCase() : "default",
                () -> registry != null ? registry.listTools().size() : 0,
                () -> new int[]{0, 0},
                () -> memoryManager != null ? memoryManager.getMemories() : List.of(),
                () -> { if (memoryManager != null) memoryManager.clear(); },
                () -> sessionId != null ? "Session: " + sessionId : "No active session",
                () -> skillCatalog != null
                        ? skillCatalog.list().stream().map(s -> s.name()).toList()
                        : List.of(),
                () -> 0,
                () -> mcpManager != null ? "MCP connected" : "",
                () -> "Unavailable",
                null
        );
    }





    private void handleCompact() {
        if (client == null || conversation == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact requires an active provider.")));
            broadcast(Map.of("type", "command_done"));
            return;
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", "Compacting conversation...")));
        try {
            String workDir = System.getProperty("user.dir");
            int contextWindow = providers.get(0).resolvedContextWindow();
            var schemas = registry.getAllSchemas(providers.get(0).getProtocol());
            String msg = ContextCompactor.forceCompact(
                    conversation, client, contextWindow, workDir, sessionId,
                    agent.getRecoveryState(), schemas);
            broadcast(Map.of("type", "system", "data", Map.of("message", "Compacted: " + msg)));
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact failed: " + e.getMessage())));
        }
        broadcast(Map.of("type", "command_done"));
    }

    private void handlePlan(String args) {
        if (permChecker == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Agent not initialized.")));
            return;
        }
        String workDir = System.getProperty("user.dir");
        permChecker.setMode(PermissionMode.PLAN);
        String planPath = PlanFile.getOrCreatePlanPath(workDir);
        broadcast(Map.of("type", "system", "data",
                Map.of("message", "Entered Plan mode. Plan file: " + planPath
                        + "\nExplore the codebase and design your approach.")));


        if (args != null && !args.isEmpty()) {
            streaming = true;
            streamThread = Thread.currentThread();
            SessionManager.saveMessage(workDir, sessionId, "user", "/plan " + args);
            conversation.addUserMessage(args);

            agentQueue = agent.run(conversation);
            if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

            try {
                consumeAgentEvents();
            } finally {
                streaming = false;
                streamThread = null;
                agentQueue = null;
            }
        }
    }

    private void handleResume(String args) {
        String workDir = System.getProperty("user.dir");
        var sessions = SessionManager.listSessions(workDir);

        if (args == null || args.isEmpty()) {

            if (sessions.isEmpty()) {
                broadcast(Map.of("type", "system", "data", Map.of("message", "No previous sessions found.")));
                broadcast(Map.of("type", "command_done"));
                return;
            }
            var sb = new StringBuilder("Available sessions (%d):\n\n".formatted(sessions.size()));
            int limit = Math.min(sessions.size(), 20);
            for (int i = 0; i < limit; i++) {
                var sess = sessions.get(i);
                String first = sess.firstMessage();
                if (first.length() > 60) first = first.substring(0, 60) + "...";
                sb.append("  %d. [%s] %s (%d msgs)\n".formatted(i + 1, sess.id(), first, sess.messageCount()));
            }
            if (sessions.size() > 20) {
                sb.append("  ... and %d more\n".formatted(sessions.size() - 20));
            }
            sb.append("\nUsage: /resume <number> or /resume <session-id>");
            broadcast(Map.of("type", "system", "data", Map.of("message", sb.toString())));
            broadcast(Map.of("type", "command_done"));
            return;
        }


        String targetId = args.strip();
        try {
            int idx = Integer.parseInt(targetId);
            if (idx >= 1 && idx <= sessions.size()) {
                targetId = sessions.get(idx - 1).id();
            }
        } catch (NumberFormatException ignored) {}

        var messages = SessionManager.loadSession(workDir, targetId);
        if (messages.isEmpty()) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Session '%s' not found or empty.".formatted(targetId))));
            broadcast(Map.of("type", "command_done"));
            return;
        }


        conversation = SessionManager.rebuildConversation(messages);
        sessionId = targetId;
        if (agent != null) agent.setSessionId(sessionId);


        broadcast(Map.of("type", "clear"));
        var scan = SessionManager.findLastCompactBoundary(messages);
        List<SessionManager.SessionMessage> replay;
        if (scan.found()) {
            replay = new ArrayList<>();
            replay.add(new SessionManager.SessionMessage("user", scan.boundary().summary(), 0));
            for (var k : scan.boundary().keep()) {
                replay.add(new SessionManager.SessionMessage(k.role(), k.content(), 0));
            }
            replay.addAll(scan.after());
        } else {
            replay = messages;
        }

        for (var msg : replay) {
            switch (msg.role()) {
                case "user" -> broadcast(Map.of("type", "replay_user", "data",
                        Map.of("content", msg.content())));
                case "assistant" -> broadcast(Map.of("type", "replay_assistant", "data",
                        Map.of("content", msg.content())));
            }
        }

        String restored;
        if (scan.found()) {
            restored = "Session %s restored from compacted state (summary + %d kept + %d newer)."
                    .formatted(targetId, scan.boundary().keep().size(), scan.after().size());
        } else {
            restored = "Session %s restored (%d messages).".formatted(targetId, replay.size());
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", restored)));
        broadcast(Map.of("type", "command_done"));
    }





    private void consumeAgentEvents() {
        var streamBuf = new StringBuilder();
        long startTime = System.currentTimeMillis();

        while (true) {
            AgentEvent event;
            try {
                event = agentQueue.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                broadcast(Map.of("type", "error", "data", Map.of("message", "Stream timeout")));
                break;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    streamBuf.append(e.text());
                    broadcast(Map.of("type", "stream_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingText e -> {
                    broadcast(Map.of("type", "thinking_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingComplete e -> {

                }
                case AgentEvent.ToolUseEvent e -> {
                    broadcast(Map.of("type", "tool_use", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "args", e.args() != null ? e.args() : Map.of()
                    )));
                }
                case AgentEvent.ToolResultEvent e -> {

                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "tool_result", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "output", e.output() != null ? e.output() : "",
                            "isError", e.isError(),
                            "elapsed", e.elapsed()
                    )));
                }
                    case AgentEvent.CommandStarted e -> broadcast(Map.of("type", "command_started",
                        "data", Map.of("command", e.command())));
                    case AgentEvent.CommandOutput e -> broadcast(Map.of("type", "command_output",
                        "data", Map.of("text", e.text(), "stderr", e.stderr())));
                    case AgentEvent.CommandCompleted e -> broadcast(Map.of("type", "command_completed",
                        "data", Map.of("exitCode", e.exitCode(), "status", e.status())));
                    case AgentEvent.CommandTimedOut e -> broadcast(Map.of("type", "command_timeout", "data", Map.of()));
                    case AgentEvent.CommandCancelled e -> broadcast(Map.of("type", "command_cancelled", "data", Map.of()));
                    case AgentEvent.RepairDiagnosing e -> broadcast(Map.of("type", "repair_diagnosing",
                        "data", Map.of("intent", e.intent(), "failureSignature", e.failureSignature())));
                    case AgentEvent.RepairDiagnosisCompleted e -> broadcast(Map.of("type", "repair_diagnosis_completed",
                        "data", Map.of("failureSignature", e.failureSignature(), "failureType", e.failureType())));
                    case AgentEvent.RepairPlanned e -> broadcast(Map.of("type", "repair_planned",
                        "data", Map.of("taskId", e.taskId(), "diagnostics", e.diagnostics())));
                    case AgentEvent.RepairApplying e -> broadcast(Map.of("type", "repair_applying",
                        "data", Map.of("taskId", e.taskId())));
                    case AgentEvent.RepairRetesting e -> broadcast(Map.of("type", "repair_retesting",
                        "data", Map.of("taskId", e.taskId())));
                    case AgentEvent.RepairRetrying e -> broadcast(Map.of("type", "repair_retrying",
                        "data", Map.of("failureSignature", e.failureSignature(), "attempt", e.attempt())));
                    case AgentEvent.RepairSucceeded e -> broadcast(Map.of("type", "repair_succeeded",
                        "data", Map.of("taskId", e.taskId())));
                    case AgentEvent.RepairFailed e -> broadcast(Map.of("type", "repair_failed",
                        "data", Map.of("failureSignature", e.failureSignature(), "reason", e.reason())));
                    case AgentEvent.RepairCancelled e -> broadcast(Map.of("type", "repair_cancelled",
                        "data", Map.of("taskId", String.valueOf(e.taskId()))));
                case AgentEvent.PermissionRequestEvent e -> {

                    String id = "perm_" + System.nanoTime();
                    pendingPerms.put(id, e.future());
                    broadcast(Map.of("type", "permission_request", "data", Map.of(
                            "id", id,
                            "toolName", e.toolName(),
                            "description", e.description()
                    )));
                }
                case AgentEvent.AskUserRequestEvent e -> {
                    String id = "ask_" + System.nanoTime();
                    pendingAsks.put(id, e.future());

                    var questions = e.questions().stream().map(q -> Map.of(
                            "question", q.text() != null ? q.text() : "",
                            "options", q.options() != null
                                    ? q.options().stream().map(o -> Map.of(
                                    "label", o.label() != null ? o.label() : "",
                                    "description", o.description() != null ? o.description() : ""
                            )).toList()
                                    : List.of()
                    )).toList();
                    broadcast(Map.of("type", "ask_user", "data", Map.of(
                            "id", id,
                            "questions", questions
                    )));
                }
                case AgentEvent.TurnComplete e -> {
                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "turn_complete", "data", Map.of("turn", e.turn())));
                }
                case AgentEvent.LoopComplete e -> {

                    if (!streamBuf.isEmpty()) {
                        String workDir = System.getProperty("user.dir");
                        SessionManager.saveMessage(workDir, sessionId, "assistant", streamBuf.toString());
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                    broadcast(Map.of("type", "loop_complete", "data", Map.of(
                            "totalTurns", e.totalTurns(),
                            "elapsed", elapsed
                    )));
                    return;
                }
                case AgentEvent.UsageEvent e -> {
                    broadcast(Map.of("type", "usage", "data", Map.of(
                            "inputTokens", e.inputTokens(),
                            "outputTokens", e.outputTokens()
                    )));
                }
                case AgentEvent.ErrorEvent e -> {
                    broadcast(Map.of("type", "error", "data", Map.of("message", e.message())));
                }
                case AgentEvent.CompactEvent e -> {
                    broadcast(Map.of("type", "compact", "data", Map.of("message", e.message())));
                }
                case AgentEvent.RetryEvent e -> {
                    broadcast(Map.of("type", "retry", "data", Map.of(
                            "reason", e.reason(),
                            "waitMs", e.waitMs()
                    )));
                }
                case AgentEvent.CheckpointCreated e -> broadcast(Map.of("type", "checkpoint_created", "data", Map.of(
                    "checkpointId", e.checkpointId(), "sessionId", e.sessionId(), "taskId", e.taskId(),
                    "boundary", e.boundary(), "status", e.status())));
                case AgentEvent.RecoveryBlocked e -> broadcast(Map.of("type", "recovery_blocked", "data", Map.of(
                    "sessionId", e.sessionId(), "checkpointId", e.checkpointId(), "reason", e.reason())));
            }
        }
    }





    private void handlePermissionResponse(String id, String response) {
        var future = pendingPerms.remove(id);
        if (future == null) return;

        PermissionResponse resp = switch (response) {
            case "allow" -> PermissionResponse.ALLOW;
            case "allowAlways" -> PermissionResponse.ALLOW_ALWAYS;
            default -> PermissionResponse.DENY;
        };
        future.complete(resp);
    }

    private void handleAskUserResponse(String id, Map<String, String> answers) {
        var future = pendingAsks.remove(id);
        if (future == null) return;
        future.complete(answers != null ? answers : Map.of());
    }





    private List<Map<String, String>> buildCommandList() {
        var list = new ArrayList<Map<String, String>>();
        for (var cmd : cmdRegistry.listVisible()) {
            list.add(Map.of(
                    "name", cmd.name(),
                    "description", cmd.description()
            ));
        }
        return list;
    }





    private void broadcast(Map<String, Object> msg) {
        if (connections.isEmpty()) return;
        try {
            String json = MAPPER.writeValueAsString(msg);
            for (var ctx : connections) {
                try {
                    ctx.send(json);
                } catch (Exception e) {
                    System.err.println("[ws] send error: " + e.getMessage());
                }
            }
        } catch (JsonProcessingException e) {
            System.err.println("[ws] JSON serialize error: " + e.getMessage());
        }
    }





    /** Parse a port from an address string (supports ":18888" and "0.0.0.0:18888"). */
    private static int parsePort(String addr) {
        if (addr == null || addr.isEmpty()) return 18888;
        int colonIdx = addr.lastIndexOf(':');
        if (colonIdx >= 0) {
            try {
                return Integer.parseInt(addr.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                return 18888;
            }
        }
        try {
            return Integer.parseInt(addr);
        } catch (NumberFormatException e) {
            return 18888;
        }
    }


    private static HookEngine.EventName parseEventName(String s) {
        if (s == null) return HookEngine.EventName.SESSION_START;
        return switch (s.toLowerCase()) {
            case "session_start" -> HookEngine.EventName.SESSION_START;
            case "session_end" -> HookEngine.EventName.SESSION_END;
            case "turn_start" -> HookEngine.EventName.TURN_START;
            case "turn_end" -> HookEngine.EventName.TURN_END;
            case "pre_send" -> HookEngine.EventName.PRE_SEND;
            case "post_receive" -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use" -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use" -> HookEngine.EventName.POST_TOOL_USE;
            case "shutdown" -> HookEngine.EventName.SHUTDOWN;
            default -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null) return HookEngine.ActionType.COMMAND;
        return switch (s.toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "prompt" -> HookEngine.ActionType.PROMPT;
            case "http" -> HookEngine.ActionType.HTTP;
            case "agent" -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }
}
