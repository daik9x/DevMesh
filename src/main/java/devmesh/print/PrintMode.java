package devmesh.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import devmesh.agent.Agent;
import devmesh.agent.AgentEvent;
import devmesh.config.AppConfig;
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
import devmesh.worktree.WorktreeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 */
public class PrintMode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     */
    public enum OutputFormat {
        TEXT,
        STREAM_JSON
    }

    /**
     */
    public static void run(AppConfig config, String prompt, OutputFormat format) {
        long startTime = System.currentTimeMillis();

        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = config.getProviders().get(0);
        List<McpServerConfig> mcpConfigs = config.getMcpServers() != null ? config.getMcpServers() : List.of();
        List<HookConfig> hookConfigs = config.getHooks() != null ? config.getHooks() : List.of();


        MemoryManager memoryManager = new MemoryManager(workDir);
        String instructionsContent = MemoryManager.loadInstructions(workDir);


        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);


        LlmClient client = LlmClient.create(providerCfg, systemPrompt);
        String protocol = providerCfg.getProtocol();


        ToolRegistry registry = ToolRegistry.createDefault();
        registry.register(new ToolSearchTool(registry, protocol));

        var exitPlanTool = new devmesh.tool.impl.ExitPlanModeTool();
        exitPlanTool.setIsPlanMode(() -> false);
        exitPlanTool.setPlanExists(() -> false);
        registry.register(exitPlanTool);


        AskUserTool askUserTool = new AskUserTool();
        registry.register(askUserTool);


        var agentTool = new AgentTool(client, registry, protocol, providerCfg);
        SubAgentTaskManager subAgentTaskManager = new SubAgentTaskManager();
        agentTool.setTaskManager(subAgentTaskManager);
        registry.register(agentTool);


        var worktreeManager = new WorktreeManager(workDir, List.of(), 720);
        agentTool.setWorktreeManager(worktreeManager);
        String sessionId = SessionManager.newId();
        registry.register(new devmesh.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new devmesh.tool.impl.ExitWorktreeTool(worktreeManager));


        TaskList taskList = new TaskList("default", workDir);
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));


        TeamManager teamManager = new TeamManager();
        agentTool.setTeamManager(teamManager);
        registry.register(new devmesh.teams.TeamTools.TeamCreateTool(teamManager));
        registry.register(new devmesh.teams.TeamTools.TeamDeleteTool(teamManager));
        registry.register(new devmesh.teams.TeamTools.SendMessageTool(teamManager, "lead"));


        PermissionChecker permChecker = new PermissionChecker(PermissionMode.BYPASS, Path.of(workDir));


        FileHistory fileHistory = new FileHistory(workDir, sessionId);
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


        ConversationManager conversation = new ConversationManager();
        Agent agent = new Agent(client, registry, protocol, providerCfg);
        agent.setFileHistory(fileHistory);
        agent.setInstructions(instructionsContent);
        agent.setChecker(permChecker);
        agent.setWorkDir(workDir);
        agent.setSessionId(sessionId);


        agent.setNotificationFn(() -> {
            var notes = new ArrayList<String>();
            notes.addAll(devmesh.teams.TeammateRunner.drainLeadMailbox(teamManager));
            for (var n : subAgentTaskManager.drainNotifications()) {
                notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                        .formatted(n.taskId(), n.name(), n.status()));
            }
            return notes;
        });


        agent.setToolNameFilter(name -> {
            if (!config.isEnableCoordinatorMode()) return true;
            if (teamManager.listTeams().isEmpty()) return true;
            return devmesh.teams.Coordinator.isCoordinatorTool(name);
        });


        if (registry.get("Agent") instanceof AgentTool at) {
            at.setProgressListener(progress -> {});
        }


        HookEngine hookEngine = new HookEngine();
        if (!hookConfigs.isEmpty()) {
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


        SkillCatalog skillCatalog = new SkillCatalog();
        var skillDir = Path.of(workDir, ".devmesh", "skills");
        if (Files.isDirectory(skillDir)) {
            skillCatalog.loadFromDirectory(skillDir);
        }


        String mcpInstructions = "";
        if (!mcpConfigs.isEmpty()) {
            try {
                McpManager mcpManager = new McpManager(mcpConfigs);
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


        conversation.addUserMessage(prompt);
        if (!mcpInstructions.isEmpty()) {
            conversation.addSystemReminder(mcpInstructions);
        }

        BlockingQueue<AgentEvent> queue = agent.run(conversation);
        if (askUserTool != null) askUserTool.setEventQueue(queue);


        var resultText = new StringBuilder();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalTurns = 0;
        var toolCalls = new ArrayList<Map<String, Object>>();

        while (true) {
            AgentEvent event;
            try {
                event = queue.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                System.err.println("Stream timeout after 120s");
                System.exit(1);
                return;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    resultText.append(e.text());
                    if (format == OutputFormat.STREAM_JSON) {

                    }
                }

                case AgentEvent.ThinkingText e -> {

                }

                case AgentEvent.ThinkingComplete e -> {

                }

                case AgentEvent.ToolUseEvent e -> {
                    if (format == OutputFormat.STREAM_JSON && e.args() != null && !e.args().isEmpty()) {

                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_use");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("args", e.args());
                        printJson(obj);
                        toolCalls.add(Map.of("tool_name", e.toolName(), "tool_id", e.toolId()));
                    }
                }

                case AgentEvent.ToolResultEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_result");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("output", e.output() != null ? e.output() : "");
                        obj.put("is_error", e.isError());
                        obj.put("elapsed", e.elapsed());
                        printJson(obj);
                    }
                }

                case AgentEvent.CommandStarted e -> {}
                case AgentEvent.CommandOutput e -> {}
                case AgentEvent.CommandCompleted e -> {}
                case AgentEvent.CommandTimedOut e -> {}
                case AgentEvent.CommandCancelled e -> {}
                case AgentEvent.RepairDiagnosing e -> {}
                case AgentEvent.RepairDiagnosisCompleted e -> {}
                case AgentEvent.RepairPlanned e -> {}
                case AgentEvent.RepairApplying e -> {}
                case AgentEvent.RepairRetesting e -> {}
                case AgentEvent.RepairRetrying e -> {}
                case AgentEvent.RepairSucceeded e -> {}
                case AgentEvent.RepairFailed e -> {}
                case AgentEvent.RepairCancelled e -> {}

                case AgentEvent.PermissionRequestEvent e -> {

                    e.future().complete(PermissionResponse.ALLOW);
                }

                case AgentEvent.AskUserRequestEvent e -> {

                    e.future().complete(Map.of());
                }

                case AgentEvent.UsageEvent e -> {
                    totalInputTokens = e.inputTokens();
                    totalOutputTokens = e.outputTokens();
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "usage");
                        obj.put("input_tokens", e.inputTokens());
                        obj.put("output_tokens", e.outputTokens());
                        printJson(obj);
                    }
                }

                case AgentEvent.TurnComplete e -> {
                    totalTurns = e.turn();

                    if (format == OutputFormat.TEXT) {
                        resultText.setLength(0);
                    }
                }

                case AgentEvent.LoopComplete e -> {
                    if (e.totalTurns() > 0) totalTurns = e.totalTurns();
                    long durationMs = System.currentTimeMillis() - startTime;

                    if (format == OutputFormat.TEXT) {

                        System.out.print(resultText);

                        if (resultText.length() > 0 && resultText.charAt(resultText.length() - 1) != '\n') {
                            System.out.println();
                        }
                    } else {

                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "result");
                        obj.put("result", resultText.toString());
                        obj.put("duration_ms", durationMs);
                        obj.put("num_turns", totalTurns);
                        obj.put("tool_calls", toolCalls);
                        obj.put("usage", Map.of(
                                "input_tokens", totalInputTokens,
                                "output_tokens", totalOutputTokens
                        ));
                        printJson(obj);
                    }
                    System.out.flush();
                    return;
                }

                case AgentEvent.ErrorEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "error");
                        obj.put("message", e.message());
                        printJson(obj);
                    } else {
                        System.err.println("Error: " + e.message());
                    }
                }

                case AgentEvent.CompactEvent e -> {

                }

                case AgentEvent.RetryEvent e -> {

                }
                case AgentEvent.CheckpointCreated e -> {

                }
                case AgentEvent.RecoveryBlocked e -> {

                }
            }
        }
    }

    /**
     */
    private static void printJson(Object obj) {
        try {
            System.out.println(MAPPER.writeValueAsString(obj));
        } catch (Exception e) {
            System.err.println("JSON serialization error: " + e.getMessage());
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
