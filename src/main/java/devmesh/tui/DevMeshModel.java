package devmesh.tui;

import devmesh.agent.Agent;
import devmesh.agent.AgentEvent;
import devmesh.command.CommandRegistry;
import devmesh.config.HookConfig;
import devmesh.config.McpServerConfig;
import devmesh.config.ProviderConfig;
import devmesh.conversation.ConversationManager;
import devmesh.history.HistoryStore;
import devmesh.hook.HookEngine;
import devmesh.llm.LlmClient;
import devmesh.llm.CapabilitySupport;
import devmesh.llm.ModelCapabilities;
import devmesh.llm.ModelRuntimeSettings;
import devmesh.llm.ReasoningEffort;
import devmesh.mcp.McpManager;
import devmesh.memory.MemoryManager;
import devmesh.memory.MemoryRecall;
import devmesh.permission.PermissionChecker;
import devmesh.permission.PermissionMode;
import devmesh.permission.PermissionResponse;
import devmesh.prompt.PlanModePrompt;
import devmesh.prompt.PromptBuilder;
import devmesh.session.SessionManager;
import devmesh.skill.SkillCatalog;
import devmesh.plan.PlanFile;
import devmesh.subagent.AgentTool;
import devmesh.subagent.SubAgentProgress;
import devmesh.subagent.SubAgentTaskManager;
import devmesh.task.TaskList;
import devmesh.task.TaskTools;
import devmesh.task.SmartOrchestrator;
import devmesh.task.AgentTodoSynchronizer;
import devmesh.tool.ToolRegistry;
import devmesh.tool.impl.AskUserTool;
import devmesh.tool.impl.ToolSearchTool;
import devmesh.teams.TeammateProgress;
import devmesh.tui.dialog.PlanApprovalDialog;

import devmesh.tui.tea.Command;
import devmesh.tui.tea.KeyPressMessage;
import devmesh.tui.tea.Message;
import devmesh.tui.tea.Model;
import devmesh.tui.tea.MouseMessage;
import devmesh.tui.tea.Program;
import devmesh.tui.tea.QuitMessage;
import devmesh.tui.tea.UpdateResult;
import devmesh.tui.tea.WindowSizeMessage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Main TUI model for DevMesh, implementing the Bubble Tea Model interface.
 * <p>
 * P1 MVP: provider selection + streaming chat.
 * Uses a simple inputBuffer with manual key handling (Textarea component deferred to P3).
 */
public class DevMeshModel implements Model {

    private static final String VERSION = "DevMesh v" + devmesh.DevMesh.VERSION;

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);


    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpServers;
    private final List<HookConfig> hookConfigs;
    private final boolean enableCoordinatorMode;
    private HookEngine hookEngine;
    private int providerCursor;
    private ProviderConfig selectedProvider;


    private AppState state;
    private LlmClient client;
    private ModelCapabilities modelCapabilities = ModelCapabilities.unknown();
    private ModelRuntimeSettings runtimeSettings = new ModelRuntimeSettings();
    private ConversationManager conversation;
    private Agent agent;
    private ToolRegistry registry;
    private PermissionChecker permChecker;


    private final List<ChatMessage> chatMessages;
    private final StringBuilder streamBuf;
    private final StringBuilder inputBuffer;
    private int inputCursor;
    private boolean streaming;
    private boolean userHasSentMessage;




    private int committedUpTo;
    private boolean bannerPrinted;


    private BlockingQueue<AgentEvent> agentQueue;
    private CompletableFuture<PermissionResponse> pendingPermission;
    private boolean permDialog;
    private String permToolName;
    private String permDesc;
    private int permCursor;
    private Program program;


    private boolean rewindDialog;
    private int rewindPhase;       // 0=snapshot list, 1=restore options
    private int rewindCursor;
    private int rewindOptionCursor;
    private java.util.List<devmesh.filehistory.FileHistory.Snapshot> rewindSnapshots;
    private devmesh.filehistory.FileHistory fileHistory;

    private static final String[] REWIND_OPTIONS = {
            "Restore code and conversation",
            "Restore conversation only",
            "Restore code only",
            "Never mind"
    };


    private final devmesh.tui.dialog.AskUserDialog askUserDialogState = new devmesh.tui.dialog.AskUserDialog();
    private CompletableFuture<Map<String, String>> askUserFuture;
    private AskUserTool askUserTool;


    private McpManager mcpManager;
    private SkillCatalog skillCatalog;
    private TaskList taskList;
    private SmartOrchestrator smartOrchestrator;
    private AgentTodoSynchronizer todoSynchronizer;
    private MemoryManager memoryManager;
    private String instructionsContent = "";
    private String memoryContentField = "";


    private CommandRegistry cmdRegistry;
    private boolean slashMenuOpen;
    private List<devmesh.command.Command> slashMatches = new ArrayList<>();
    private int slashCursor;
    private PopupController slashPopup = new PopupController(List.of());
    private TuiMode inputMode = TuiMode.NORMAL;
    private boolean runtimePopupOpen;
    private String runtimePopupKind = "";
    private List<String> runtimePopupOptions = List.of();
    private int runtimePopupCursor;
    private boolean todoPopupOpen;
    private int todoPopupCursor;


    private final HistoryStore historyStore = new HistoryStore();
    private int historyIndex = -1;
    private String historyDraft = "";


    private boolean atMenuOpen;
    private List<String> atMatches = new ArrayList<>();

    private int atCursor;


    private List<devmesh.session.SessionManager.SessionInfo> resumeSessions = new ArrayList<>();
    private List<devmesh.session.SessionManager.SessionInfo> resumeFiltered = new ArrayList<>();
    private int resumeCursor;
    private String resumeSearch = "";


    private String sessionId;


    private PermissionMode prePlanMode = PermissionMode.DEFAULT;
    private final PlanApprovalDialog planApprovalDialog = new PlanApprovalDialog();
    private boolean hasExitedPlanMode;


    private final List<ChatMessage.ToolBlockInfo> toolBlocks = new ArrayList<>();
    private ChatMessage.SubAgentBlockState activeSubAgent;


    private SubAgentTaskManager subAgentTaskManager;
    private AgentTool agentToolRef;


    private final devmesh.teams.TeamManager teamManager = new devmesh.teams.TeamManager();
    private final ConcurrentLinkedQueue<SubAgentProgress> subAgentProgressQueue = new ConcurrentLinkedQueue<>();


    private devmesh.sandbox.Sandbox sandboxInstance;
    private devmesh.sandbox.SandboxConfig sandboxConfig;


    private volatile boolean mcpConnecting;
    private boolean pendingSingleProviderInit;
    private volatile String mcpInstructions = "";
    private volatile boolean mcpInstructionsOk;
    private volatile String mcpServerInfo = "";


    private boolean ready;


    private int scrollOffset;
    private boolean userScrolled;
    private int totalContentLines;


    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;

    private String thinkingVerb = "";
    private long thinkingStartMs;


    private int width;
    private int height;


    private int totalInput;
    private int totalOutput;


    public record AgentEventMessage() implements Message {}
    public record MailboxPollMessage() implements Message {}

    public DevMeshModel(List<ProviderConfig> providers, List<McpServerConfig> mcpServers,
                        List<HookConfig> hookConfigs, boolean enableCoordinatorMode) {
        this.providers = providers != null ? providers : List.of();
        this.mcpServers = mcpServers != null ? mcpServers : List.of();
        this.hookConfigs = hookConfigs != null ? hookConfigs : List.of();
        this.enableCoordinatorMode = enableCoordinatorMode;
        if (this.providers.size() == 1) {
            this.selectedProvider = this.providers.get(0);
            this.state = AppState.CHAT;
            this.pendingSingleProviderInit = true;
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
        this.chatMessages = new ArrayList<>();
        this.streamBuf = new StringBuilder();
        this.inputBuffer = new StringBuilder();
        this.inputCursor = 0;
        this.conversation = new ConversationManager();
        this.cmdRegistry = new CommandRegistry();
        this.historyStore.load();
        this.width = 80;
        this.height = 24;


        this.hookEngine = new HookEngine();
        if (!this.hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = this.hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());

                Duration timeout = hc.getTimeout() > 0
                        ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                        hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                        hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError());
            }).toList();

            List<String> errors = HookEngine.validate(hooks);
            if (!errors.isEmpty()) {
                System.err.println("[hook] config validation errors:");
                errors.forEach(e -> System.err.println("  - " + e));
            }
            this.hookEngine.loadHooks(hooks);
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
            case "prompt"  -> HookEngine.ActionType.PROMPT;
            case "http"    -> HookEngine.ActionType.HTTP;
            case "agent"   -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }

    /**
     * Stores a reference to the running Program so we can send messages from
     * background threads (e.g., the streaming poller).
     */
    public void setProgram(Program program) {
        this.program = program;
    }

    /**
     * Called from the SIGINT handler. If streaming, interrupts the response;
     * otherwise sends a QuitMessage to exit the application.
     */
    public void handleSigint() {
        if (streaming) {
            savePartialResponse();
            if (program != null) {
                program.send(new AgentEventMessage());
            }
        } else {
            if (program != null) {
                program.send(new QuitMessage());
            }
        }
    }


    // Model interface


    @Override
    public Command init() {
        return Command.checkWindowSize();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (pendingSingleProviderInit) {
            pendingSingleProviderInit = false;
            initializeProvider();
        }


        if (msg instanceof WindowSizeMessage wsm) {
            this.width = wsm.width();
            this.height = wsm.height();
            ready = true;

            if (!bannerPrinted && state == AppState.CHAT) {
                bannerPrinted = true;
                return UpdateResult.from(this, Command.println(renderBanner() + "\n"));
            }
            return UpdateResult.from(this);
        }


        if (msg instanceof QuitMessage) {
            if (streaming) {
                savePartialResponse();
                return UpdateResult.from(this);
            }
        }
        if (msg instanceof KeyPressMessage kpm && kpm.key().equals("ctrl+c")) {
            if (streaming) {
                savePartialResponse();
                return UpdateResult.from(this);
            }
            return UpdateResult.from(this, QuitMessage::new);
        }


        if (msg instanceof MouseMessage mm) {
            var btn = mm.getButton();
            if (btn == MouseMessage.MouseButton.MouseButtonWheelUp) {
                scrollOffset = Math.min(scrollOffset + 3, Math.max(totalContentLines - 3, 0));
                userScrolled = true;
            } else if (btn == MouseMessage.MouseButton.MouseButtonWheelDown) {
                scrollOffset = Math.max(scrollOffset - 3, 0);
                userScrolled = scrollOffset > 0;
            }
            return UpdateResult.from(this);
        }


        if (msg instanceof AgentEventMessage) {
            if (streaming) spinnerFrame++;
            return handleAgentEvents();
        }

        if (msg instanceof MailboxPollMessage) {
            if (streaming) {
                var poll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                return UpdateResult.from(this, poll);
            }
            var notes = new java.util.ArrayList<String>();
            notes.addAll(devmesh.teams.TeammateRunner.drainLeadMailbox(teamManager));
            if (subAgentTaskManager != null) {
                for (var n : subAgentTaskManager.drainNotifications()) {
                    notes.add("<task-notification>\n<task_id>" + n.taskId() + "</task_id>\n"
                            + "<status>" + n.status() + "</status>\n"
                            + "<result>" + n.output() + "</result>\n</task-notification>");
                }
            }
            if (notes.isEmpty()) {
                var poll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                return UpdateResult.from(this, poll);
            }
            for (String note : notes) {
                conversation.addSystemReminder(note);
            }
            streaming = true;
            thinkingStartMs = System.currentTimeMillis();
            thinkingVerb = SpinnerVerbs.random();
            streamBuf.setLength(0);
            toolBlocks.clear();
            agentQueue = agent.run(conversation);
            var pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
            return UpdateResult.from(this, pollCmd);
        }


        if (msg instanceof KeyPressMessage kpm && permDialog) {
            return handlePermDialogKey(kpm);
        }


        if (msg instanceof KeyPressMessage kpm && rewindDialog) {
            return handleRewindKey(kpm);
        }


        if (msg instanceof KeyPressMessage kpm && planApprovalDialog.isActive()) {
            return handlePlanApprovalKey(kpm);
        }


        if (msg instanceof KeyPressMessage kpm && askUserDialogState.isActive()) {
            return handleAskUserDialogKey(kpm);
        }


        if (msg instanceof KeyPressMessage kpm) {
            return switch (state) {
                case PROVIDER_SELECT -> handleProviderSelectKey(kpm);
                case CHAT -> handleChatKey(kpm);
                case RESUME -> handleResumeKey(kpm);
            };
        }

        return UpdateResult.from(this);
    }

    @Override
    public String view() {
        if (!ready) return "";
        return switch (state) {
            case PROVIDER_SELECT -> viewProviderSelect();
            case CHAT -> viewChat();
            case RESUME -> viewResume();
        };
    }


    // Provider selection


    private UpdateResult<DevMeshModel> handleProviderSelectKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> {
                if (providerCursor > 0) providerCursor--;
                yield UpdateResult.from(this);
            }
            case "down", "j" -> {
                if (providerCursor < providers.size() - 1) providerCursor++;
                yield UpdateResult.from(this);
            }
            case "enter" -> {
                if (providers.isEmpty()) yield UpdateResult.from(this);
                selectedProvider = providers.get(providerCursor);
                initializeProvider();
                state = AppState.CHAT;
                inputMode = TuiMode.INSERT;
                bannerPrinted = true;
                yield UpdateResult.from(this, Command.println(renderBanner() + "\n"));
            }
            default -> UpdateResult.from(this);
        };
    }

    private void initializeProvider() {
        try {
            String workDir = System.getProperty("user.dir");

            memoryManager = new MemoryManager(workDir);

            var env = PromptBuilder.detectEnvironment(selectedProvider.getModel());
            instructionsContent = MemoryManager.loadInstructions(workDir);
            memoryContentField = buildMemorySection();
            String systemPrompt = rebuildSystemPrompt(workDir);

            client = LlmClient.create(selectedProvider, systemPrompt);
            modelCapabilities = client.capabilities();
            runtimeSettings.resetUnsupported(modelCapabilities);
            client.setRuntimeSettings(runtimeSettings);
            String protocol = selectedProvider.getProtocol();

            registry = ToolRegistry.createDefault();

            registry.register(new ToolSearchTool(registry, protocol));
            var exitPlanTool = new devmesh.tool.impl.ExitPlanModeTool();
            exitPlanTool.setIsPlanMode(() -> permChecker != null && permChecker.getMode() == PermissionMode.PLAN);
            exitPlanTool.setPlanExists(() -> devmesh.plan.PlanFile.planExists());
            registry.register(exitPlanTool);
            askUserTool = new AskUserTool();
            registry.register(askUserTool);
            agentToolRef = new AgentTool(client, registry, protocol, selectedProvider);
            subAgentTaskManager = new SubAgentTaskManager();
            agentToolRef.setProgressListener(this::handleSubAgentProgress);
            agentToolRef.setTaskManager(subAgentTaskManager);
            registry.register(agentToolRef);

            // Worktree tools and integration
            var worktreeManager = new devmesh.worktree.WorktreeManager(
                    workDir, java.util.List.of(), 720);
            agentToolRef.setWorktreeManager(worktreeManager);
            registry.register(new devmesh.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
            registry.register(new devmesh.tool.impl.ExitWorktreeTool(worktreeManager));
            // Restore session from previous crash
            var savedSession = devmesh.worktree.WorktreeSessionStore.load(workDir);
            if (savedSession != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(savedSession.worktreePath()))) {
                devmesh.worktree.WorktreeSessionStore.restoreSession(savedSession);
            }

            taskList = new TaskList("default", workDir);
            smartOrchestrator = new SmartOrchestrator(taskList);
            todoSynchronizer = new AgentTodoSynchronizer(taskList);
            registry.register(new TaskTools.TaskCreateTool(taskList));
            registry.register(new TaskTools.TaskGetTool(taskList));
            registry.register(new TaskTools.TaskListTool(taskList));
            registry.register(new TaskTools.TaskUpdateTool(taskList));

            // Team tools (ch15)
            agentToolRef.setTeamManager(teamManager);
            registry.register(new devmesh.teams.TeamTools.TeamCreateTool(teamManager));
            registry.register(new devmesh.teams.TeamTools.TeamDeleteTool(teamManager));
            registry.register(new devmesh.teams.TeamTools.SendMessageTool(teamManager, "lead"));

            permChecker = new PermissionChecker(
                    PermissionMode.DEFAULT, Path.of(workDir));


            sandboxInstance = devmesh.sandbox.SandboxFactory.create();
            if (sandboxInstance != null && sandboxInstance.isAvailable()) {

                sandboxConfig = new devmesh.sandbox.SandboxConfig(
                        java.util.List.of(workDir, "/tmp"),
                        permChecker.getDenyWrite(),
                        true
                );

                for (var t : registry.listTools()) {
                    if (t instanceof devmesh.tool.impl.BashTool bt) {
                        bt.setSandbox(sandboxInstance);
                        bt.setSandboxConfig(sandboxConfig);
                    }
                }
            }

            sessionId = devmesh.session.SessionManager.newId();

            devmesh.session.SessionManager.cleanExpiredSessions(workDir);
            fileHistory = new devmesh.filehistory.FileHistory(workDir, sessionId);
            var fileStateCache = new devmesh.tool.FileStateCache();
            for (var t : registry.listTools()) {
                if (t instanceof devmesh.tool.impl.EditFileTool ef) { ef.setFileHistory(fileHistory); ef.setFileStateCache(fileStateCache); }
                if (t instanceof devmesh.tool.impl.WriteFileTool wf) { wf.setFileHistory(fileHistory); wf.setFileStateCache(fileStateCache); }
                if (t instanceof devmesh.tool.impl.ReadFileTool rf) rf.setFileStateCache(fileStateCache);
            }
            agent = new Agent(client, registry, protocol, selectedProvider);
            agent.setFileHistory(fileHistory);
            agent.setInstructions(instructionsContent);
            agent.setMemoryContent(memoryContentField);
            agent.setChecker(permChecker);
            agent.setWorkDir(workDir);
            agent.setSessionId(sessionId);

            // Team notification drain: team mailbox + sub-agent task notifications
            final var teamMgrCapture = teamManager;
            agent.setNotificationFn(() -> {
                var notes = new java.util.ArrayList<String>();
                // Drain team mailbox (ch15)
                notes.addAll(devmesh.teams.TeammateRunner.drainLeadMailbox(teamMgrCapture));
                // Drain background task notifications (ch13)
                if (subAgentTaskManager != null) {
                    for (var n : subAgentTaskManager.drainNotifications()) {
                        notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                                .formatted(n.taskId(), n.name(), n.status()));
                    }
                }
                return notes;
            });

            // Coordinator mode: restrict Lead's tools when teams exist and config enabled
            final boolean coordEnabled = enableCoordinatorMode;
            agent.setToolNameFilter(name -> {
                if (!coordEnabled) return true;
                if (teamMgrCapture.listTeams().isEmpty()) return true;
                return devmesh.teams.Coordinator.isCoordinatorTool(name);
            });

            if (!mcpServers.isEmpty()) {
                mcpConnecting = true;
                final var registryRef = registry;
                Thread.ofVirtual().name("mcp-connect").start(() -> {
                    try {
                        mcpManager = new McpManager(mcpServers);
                        var result = mcpManager.connectAll();
                        for (var t : result.tools()) registryRef.register(t);
                        mcpServerInfo = "Connected to %d MCP server(s), %d tools registered".formatted(
                            result.servers().size(), result.tools().size());
                        synchronized (chatMessages) {
                            for (var e : result.errors()) {
                                chatMessages.add(new ChatMessage("error", "MCP: " + e));
                            }
                        }
                        var mcpParts = new ArrayList<String>();
                        for (var s : result.servers()) {
                            var sb2 = new StringBuilder();
                            sb2.append("## ").append(s.name()).append("\n");
                            if (s.instructions() != null && !s.instructions().isBlank()) {
                                sb2.append(s.instructions()).append("\n");
                            }
                            var toolNames = registryRef.listTools().stream()
                                    .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                                    .map(devmesh.tool.Tool::name)
                                    .toList();
                            if (!toolNames.isEmpty()) {
                                sb2.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                            }
                            mcpParts.add(sb2.toString());
                        }
                        if (!mcpParts.isEmpty()) {
                            mcpInstructions = "# MCP Server Instructions\n\n"
                                    + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                                    + String.join("\n\n", mcpParts);
                        }
                    } catch (Exception e) {
                        synchronized (chatMessages) {
                            chatMessages.add(new ChatMessage("error", "MCP init failed: " + e.getMessage()));
                        }
                    } finally {
                        mcpConnecting = false;
                    }
                });
            }

            skillCatalog = SkillCatalog.loadCatalog(workDir);


            var installSkillTool = new devmesh.tool.impl.InstallSkillTool();
            installSkillTool.setCatalog(skillCatalog);
            installSkillTool.setOnInstalled(name -> {
                registerSkillCommand(name);
                if (client != null) {
                    client.setSystemPrompt(rebuildSystemPrompt(System.getProperty("user.dir")));
                }
            });
            registry.register(installSkillTool);

            wireSkillsToAgent();

            agent.setHookEngine(hookEngine);
            fireHook(HookEngine.EventName.SESSION_START, null, null);

        } catch (Exception e) {
            chatMessages.add(new ChatMessage("error",
                    "Failed to initialize: " + e.getMessage()));
        }
    }

    private void wireSkillsToAgent() {
        if (skillCatalog == null || cmdRegistry == null) return;
        for (var meta : skillCatalog.list()) {
            registerSkillCommand(meta.name());
        }
    }

    private String buildSkillSection(String workDir) {
        if (skillCatalog == null) return "";
        var metas = skillCatalog.list();
        if (metas.isEmpty()) return "";
        var skillsDir = Path.of(workDir, ".devmesh", "skills");
        var sb = new StringBuilder();
        sb.append("## Available Skills\n\n");
        sb.append("Skills are installed at: ").append(skillsDir).append("\n");
        sb.append("When creating new skills, always place them under this directory as <skill-name>/SKILL.md.\n\n");
        sb.append("Only Skill names and one-line descriptions are listed below. To activate a Skill on demand call the LoadSkill tool with {name: \"<skill-name>\"}. After activation the Skill's full SOP gets pinned to the environment context, and any tools the Skill declares get registered. Users can also invoke a Skill directly with /<name>.\n\n");
        sb.append("If the user pastes a Skill URL (skills.sh, github.com tree URL, or raw SKILL.md URL) and asks to install / add / get it, call the InstallSkill tool with {url: \"<url>\"} — the new Skill becomes available immediately afterwards.\n\n");
        for (var meta : metas) {
            var desc = meta.description();
            if (desc.length() > 200) desc = desc.substring(0, 200) + "…";
            sb.append("- /").append(meta.name()).append(": ").append(desc).append("\n");
        }
        return sb.toString();
    }

    private String rebuildSystemPrompt(String workDir) {
        var env = PromptBuilder.detectEnvironment(selectedProvider != null ? selectedProvider.getModel() : "");
        instructionsContent = MemoryManager.loadInstructions(workDir);
        memoryContentField = buildMemorySection();
        String skillSection = buildSkillSection(workDir);
        var options = new PromptBuilder.BuildOptions(skillSection, instructionsContent, memoryContentField);
        return PromptBuilder.buildSystemPrompt(env, options);
    }

    private void refreshSkillsIfNeeded() {
        if (skillCatalog == null || client == null) return;
        if (!skillCatalog.needsReload()) return;
        String workDir = System.getProperty("user.dir");
        skillCatalog.reload(workDir);
        for (var meta : skillCatalog.list()) {
            registerSkillCommand(meta.name());
        }
        client.setSystemPrompt(rebuildSystemPrompt(workDir));
    }

    private void registerSkillCommand(String name) {
        if (cmdRegistry.find(name).isPresent()) return;
        var skill = skillCatalog.get(name);
        if (skill.isEmpty()) return;

        var meta = skill.get().meta();
        var cmd = new devmesh.command.Command(name, meta.description() + " [skill]",
                              new String[]{}, devmesh.command.Command.CommandType.PROMPT, false);

        String captured = name;
        cmdRegistry.register(cmd, ctx -> {
            var s = skillCatalog.get(captured);
            if (s.isEmpty()) return "[skill error] not found: " + captured;
            return s.get().promptBody();
        });
    }

    private static final java.util.regex.Pattern ANSI_RE =
            java.util.regex.Pattern.compile("\033\\[[0-9;]*[a-zA-Z]|\033][^\007\033]*(?:\007|\033\\\\)");

    /**
     */
    private static String clipToPhysicalLines(String text, int maxPhysical, int cols) {
        if (cols <= 0) cols = 80;
        String[] lines = text.split("\n", -1);
        int physicalCount = 0;
        int cutIndex = lines.length;
        for (int i = lines.length - 1; i >= 0; i--) {
            int w = devmesh.tui.tea.Program.displayWidth(ANSI_RE.matcher(lines[i]).replaceAll(""));
            int wrapped = Math.max(1, (int) Math.ceil((double) w / cols));
            if (physicalCount + wrapped > maxPhysical) break;
            physicalCount += wrapped;
            cutIndex = i;
        }
        if (cutIndex <= 0) return text;
        var sb = new StringBuilder("…\n");
        for (int i = cutIndex; i < lines.length; i++) {
            if (i > cutIndex) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private String renderBanner() {
        String providerName = selectedProvider != null && selectedProvider.getName() != null
            ? selectedProvider.getName() : "No provider selected";
        String modelName = selectedProvider != null ? selectedProvider.getModel() : "";
        String protocol = selectedProvider != null ? selectedProvider.getProtocol() : "";
        String workDir = System.getProperty("user.dir");
        String modelLine = modelName.isEmpty() ? providerName : providerName + " · " + modelName;
        if (!protocol.isEmpty()) modelLine += " · " + protocol;
        String rule = Styles.bannerRule.render("─".repeat(Math.max(width - 2, 24)));
        return Styles.banner.render(" DevMesh ") + Styles.bannerDim.render(VERSION) + "\n"
            + rule + "\n"
            + Styles.bannerMeta.render(" " + modelLine) + "\n"
            + Styles.bannerDim.render(" " + workDir);
    }

    private String viewProviderSelect() {
        var sb = new StringBuilder();
        sb.append("\n");
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(Styles.selectLabel.render("  Select a Provider"));
        sb.append("\n\n");

        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            String label = p.getName() + " (" + p.getModel() + ")";
            if (i == providerCursor) {
                sb.append(Styles.selectedItem.render("  ❯ " + label));
            } else {
                sb.append(Styles.normalItem.render("    " + label));
            }
            sb.append("\n");
        }

        sb.append("\n");
        sb.append(Styles.statusBar.render("  j/k to move · enter to select · ctrl+c to quit"));
        sb.append("\n");
        return sb.toString();
    }


    // Chat


    private UpdateResult<DevMeshModel> handleChatKey(KeyPressMessage kpm) {
        String key = kpm.key();

        if (todoPopupOpen) return handleTodoPopupKey(kpm);
        if (runtimePopupOpen) return handleRuntimePopupKey(kpm);

        if (inputMode == TuiMode.NORMAL) {
            if (key.equals("i")) {
                inputMode = TuiMode.INSERT;
                return UpdateResult.from(this);
            }
            if (key.equals("a")) {
                inputCursor = Math.min(inputBuffer.length(), inputCursor + 1);
                inputMode = TuiMode.INSERT;
                return UpdateResult.from(this);
            }
            if (key.equals("h") || key.equals("left")) {
                inputCursor = Math.max(0, inputCursor - 1);
                return UpdateResult.from(this);
            }
            if (key.equals("l") || key.equals("right")) {
                inputCursor = Math.min(inputBuffer.length(), inputCursor + 1);
                return UpdateResult.from(this);
            }
            if (key.equals("escape")) return UpdateResult.from(this);
        }

        // shift+tab: cycle permission mode
        if (key.equals("shift+tab") && !streaming && permChecker != null) {
            var current = permChecker.getMode();
            var next = switch (current) {
                case DEFAULT -> PermissionMode.ACCEPT_EDITS;
                case ACCEPT_EDITS -> PermissionMode.PLAN;
                case PLAN -> PermissionMode.BYPASS;
                case BYPASS -> PermissionMode.DEFAULT;
            };
            permChecker.setMode(next);

            return UpdateResult.from(this);
        }

        // ctrl+o: toggle tool block fold/unfold
        if (key.equals("ctrl+o")) {
            for (var msg : chatMessages) {
                if ("tool_group".equals(msg.role) || "tool_collapsed".equals(msg.role)
                        || "sub_agent".equals(msg.role)) {
                    msg.expanded = !msg.expanded;
                }
            }
            return UpdateResult.from(this);
        }

        // ctrl+j: insert newline without sending
        if (key.equals("ctrl+j")) {
            inputBuffer.insert(inputCursor, '\n'); inputCursor++;
            return UpdateResult.from(this);
        }

        if (key.equals("ctrl+d")) {
            scrollOffset = Math.max(scrollOffset - Math.max(height - 6, 1), 0);
            userScrolled = scrollOffset > 0;
            return UpdateResult.from(this);
        }
        if (key.equals("ctrl+u")) {
            scrollOffset = Math.min(scrollOffset + Math.max(height - 6, 1), Math.max(totalContentLines - 3, 0));
            userScrolled = true;
            return UpdateResult.from(this);
        }

        // escape during streaming: move agent to background
        if (key.equals("escape") && streaming) {
            chatMessages.add(new ChatMessage("system",
                    "Agent moved to background. You will be notified when it completes."));
            streaming = false;
            agentQueue = null;
            String commitText = renderMessagesRange(committedUpTo, chatMessages.size());
            committedUpTo = chatMessages.size();
            return !commitText.isEmpty()
                    ? UpdateResult.from(this, Command.println(commitText))
                    : UpdateResult.from(this);
        }

        // pgup/pgdown/home/end: viewport scrolling
        if (key.equals("pgup")) {
            scrollOffset = Math.min(scrollOffset + Math.max(height - 6, 1), Math.max(totalContentLines - 3, 0));
            userScrolled = true;
            return UpdateResult.from(this);
        }
        if (key.equals("pgdown")) {
            scrollOffset = Math.max(scrollOffset - Math.max(height - 6, 1), 0);
            userScrolled = scrollOffset > 0;
            return UpdateResult.from(this);
        }
        if (key.equals("home")) {
            scrollOffset = Math.max(totalContentLines - 3, 0);
            userScrolled = true;
            return UpdateResult.from(this);
        }
        if (key.equals("end")) {
            scrollOffset = 0;
            userScrolled = false;
            return UpdateResult.from(this);
        }

        // Slash menu open: handle navigation
        if (slashMenuOpen) {
            return switch (key) {
                case "up" -> {
                    if (slashCursor > 0) slashCursor--;
                    yield UpdateResult.from(this);
                }
                case "down" -> {
                    if (slashCursor < slashMatches.size() - 1) slashCursor++;
                    yield UpdateResult.from(this);
                }
                case "enter" -> {
                    if (!slashMatches.isEmpty()) {
                        var cmd = slashMatches.get(slashCursor);
                        inputBuffer.setLength(0); inputCursor = 0;
                        slashMenuOpen = false;
                        inputMode = TuiMode.NORMAL;
                        yield executeSlashCommand(cmd, "");
                    }
                    yield UpdateResult.from(this);
                }
                case "tab" -> {
                    if (!slashMatches.isEmpty()) {
                        var cmd = slashMatches.get(slashCursor);
                        inputBuffer.setLength(0); inputCursor = 0;
                        inputBuffer.append("/" + cmd.name() + " ");
                        inputCursor = inputBuffer.length();
                        slashMenuOpen = false;
                        inputMode = TuiMode.INSERT;
                    }
                    yield UpdateResult.from(this);
                }
                case "escape" -> {
                    slashMenuOpen = false;
                    inputMode = TuiMode.NORMAL;
                    yield UpdateResult.from(this);
                }
                default -> {
                    if (key.equals("backspace") || key.equals("ctrl+h")) {
                        if (inputCursor > 0) { inputBuffer.deleteCharAt(inputCursor - 1); inputCursor--; }
                        updateSlashMenu();
                    } else {
                        char[] runes = kpm.runes();
                        if (runes != null && runes.length > 0) {
                            for (char ch : runes) if (ch >= 32) { inputBuffer.insert(inputCursor, ch); inputCursor++; }
                        } else if (key.length() == 1 && key.charAt(0) >= 32) {
                            inputBuffer.insert(inputCursor, key.charAt(0)); inputCursor++;
                        }
                        updateSlashMenu();
                    }
                    yield UpdateResult.from(this);
                }
            };
        }

        // @ file menu open: handle navigation
        if (atMenuOpen) {
            return switch (key) {
                case "up" -> { if (atCursor > 0) atCursor--; yield UpdateResult.from(this); }
                case "down" -> { if (atCursor < atMatches.size() - 1) atCursor++; yield UpdateResult.from(this); }
                case "enter", "tab" -> {
                    if (!atMatches.isEmpty()) {
                        String selected = atMatches.get(atCursor);
                        int atIdx = inputBuffer.lastIndexOf("@");
                        if (atIdx >= 0) {
                            inputBuffer.replace(atIdx, inputBuffer.length(), "@" + selected + " ");
                            inputCursor = inputBuffer.length();
                        }
                    }
                    atMenuOpen = false;
                    yield UpdateResult.from(this);
                }
                case "escape" -> { atMenuOpen = false; yield UpdateResult.from(this); }
                default -> {
                    if (key.equals("backspace") || key.equals("ctrl+h")) {
                        if (inputCursor > 0) { inputBuffer.deleteCharAt(inputCursor - 1); inputCursor--; }
                        updateAtMenu();
                    } else {
                        char[] runes = kpm.runes();
                        if (runes != null && runes.length > 0) {
                            for (char ch : runes) if (ch >= 32) { inputBuffer.insert(inputCursor, ch); inputCursor++; }
                        } else if (key.length() == 1 && key.charAt(0) >= 32) {
                            inputBuffer.insert(inputCursor, key.charAt(0)); inputCursor++;
                        }
                        updateAtMenu();
                    }
                    yield UpdateResult.from(this);
                }
            };
        }

        // Enter sends a message or slash command
        if (key.equals("enter")) {
            if (inputBuffer.isEmpty()) return UpdateResult.from(this);
            if (streaming) {
                savePartialResponse();
            }
            String text = inputBuffer.toString().trim();
            if (text.startsWith("/")) {
                String[] parts = text.substring(1).split("\\s+", 2);
                String cmdName = parts[0];
                String cmdArgs = parts.length > 1 ? parts[1] : "";
                var cmd = cmdRegistry.find(cmdName);
                if (cmd.isPresent()) {
                    inputBuffer.setLength(0); inputCursor = 0;
                    return executeSlashCommand(cmd.get(), cmdArgs);
                } else {
                    inputBuffer.setLength(0); inputCursor = 0;
                    chatMessages.add(new ChatMessage("error",
                            "Unknown command: /" + cmdName + " — type /help to see available commands"));
                    return UpdateResult.from(this);
                }
            }
            return sendUserMessage();
        }

        // History navigation (up/down when not streaming)
        if (!streaming && (key.equals("up") || key.equals("down"))) {
            var entries = historyStore.getEntries();
            if (!entries.isEmpty()) {
                if (key.equals("up")) {
                    if (historyIndex < 0) {
                        historyDraft = inputBuffer.toString();
                        historyIndex = entries.size() - 1;
                    } else if (historyIndex > 0) {
                        historyIndex--;
                    }
                    inputBuffer.setLength(0); inputCursor = 0;
                    inputBuffer.append(entries.get(historyIndex)); inputCursor = inputBuffer.length();
                } else {
                    if (historyIndex >= 0) {
                        historyIndex++;
                        if (historyIndex >= entries.size()) {
                            historyIndex = -1;
                            inputBuffer.setLength(0); inputCursor = 0;
                            inputBuffer.append(historyDraft); inputCursor = inputBuffer.length();
                        } else {
                            inputBuffer.setLength(0); inputCursor = 0;
                            inputBuffer.append(entries.get(historyIndex)); inputCursor = inputBuffer.length();
                        }
                    }
                }
            }
            return UpdateResult.from(this);
        }


        if (key.equals("left")) {
            if (inputCursor > 0) inputCursor--;
            return UpdateResult.from(this);
        }
        if (key.equals("right")) {
            if (inputCursor < inputBuffer.length()) inputCursor++;
            return UpdateResult.from(this);
        }
        if (key.equals("home")) {
            inputCursor = 0;
            return UpdateResult.from(this);
        }
        if (key.equals("end")) {
            inputCursor = inputBuffer.length();
            return UpdateResult.from(this);
        }

        // Backspace (DEL 0x7F on Linux/macOS, BS 0x08 aka ctrl+h on Windows)
        if (key.equals("backspace") || key.equals("ctrl+h")) {
            if (inputCursor > 0) {
                inputBuffer.deleteCharAt(inputCursor - 1);
                inputCursor--;
                updateSlashMenu();
            }
            return UpdateResult.from(this);
        }

        // Space
        if (key.equals("space") || key.equals(" ")) {
            inputBuffer.insert(inputCursor, ' ');
            inputCursor++;
            return UpdateResult.from(this);
        }

        // Tab
        if (key.equals("tab")) {
            inputBuffer.insert(inputCursor, "    ");
            inputCursor += 4;
            return UpdateResult.from(this);
        }


        char[] runes = kpm.runes();
        if (runes != null && runes.length > 0) {
            for (char ch : runes) {
                if (ch >= 32) {
                    inputBuffer.insert(inputCursor, ch);
                    inputCursor++;
                }
            }
            if (inputBuffer.length() == 1 && inputBuffer.charAt(0) == '/') {
                updateSlashMenu();
            } else if (slashMenuOpen) {
                updateSlashMenu();
            }
            String text = inputBuffer.toString();
            int atIdx = text.lastIndexOf('@');
            if (atIdx >= 0 && !text.substring(atIdx).contains(" ")) {
                updateAtMenu();
            }
            return UpdateResult.from(this);
        }

        // Fallback: single printable key string (ASCII)
        if (key.length() == 1 && key.charAt(0) >= 32) {
            inputBuffer.insert(inputCursor, key.charAt(0));
            inputCursor++;
            if (key.charAt(0) == '/' && inputBuffer.length() == 1) {
                updateSlashMenu();
            } else if (slashMenuOpen) {
                updateSlashMenu();
            }
            if (key.charAt(0) == '@') updateAtMenu();
            return UpdateResult.from(this);
        }

        return UpdateResult.from(this);
    }

    private void updateSlashMenu() {
        String text = inputBuffer.toString();
        if (text.startsWith("/") && !text.contains(" ") && historyIndex < 0) {
            String prefix = text.substring(1);
            slashPopup = new PopupController(cmdRegistry.listVisible().stream()
                .map(command -> new PopupItem(command.name(), command.description()))
                .toList());
            slashPopup.setQuery(prefix);
            slashMatches = slashPopup.filteredItems().stream()
                .map(item -> cmdRegistry.find(item.value()).orElseThrow())
                .toList();
            slashMenuOpen = !slashMatches.isEmpty();
            inputMode = slashMenuOpen ? TuiMode.SLASH : TuiMode.INSERT;
            slashCursor = 0;
        } else {
            slashMenuOpen = false;
            inputMode = TuiMode.INSERT;
        }
    }

    private void updateAtMenu() {
        String text = inputBuffer.toString();
        int atIdx = text.lastIndexOf('@');
        if (atIdx < 0) { atMenuOpen = false; return; }
        String prefix = text.substring(atIdx + 1);
        if (prefix.contains(" ")) { atMenuOpen = false; return; }

        var skipDirs = Set.of(".git", "node_modules", ".venv", "__pycache__", ".devmesh", "build", ".gradle");
        var matches = new ArrayList<String>();
        try {
            var dir = Path.of(System.getProperty("user.dir"));
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".") && prefix.isEmpty()) return false;
                    if (skipDirs.contains(name)) return false;
                    return prefix.isEmpty() || name.toLowerCase().startsWith(prefix.toLowerCase());
                }).sorted().limit(10).forEach(p -> matches.add(p.getFileName().toString()));
            }
        } catch (Exception ignored) {}

        atMatches = matches;
        atMenuOpen = !matches.isEmpty();
        atCursor = 0;
    }

    private static final Set<String> AT_SKIP_DIRS = Set.of(
            ".git", "node_modules", ".venv", "__pycache__", ".devmesh", "build", ".gradle");

    private void openRuntimePopup(String kind) {
        if ("thinking".equals(kind) && !modelCapabilities.supportsThinking()) {
            chatMessages.add(new ChatMessage("system", modelCapabilities.reasoning() == CapabilitySupport.UNKNOWN
                    ? "Thinking control is unknown for the current model/provider. Configure capability metadata to enable it."
                    : "Thinking control is not supported by the current model/provider."));
            return;
        }
        runtimePopupKind = kind;
        runtimePopupOptions = switch (kind) {
            case "mode" -> {
                var options = new ArrayList<String>();
                options.add("Default");
                if (modelCapabilities.reasoningEffort() == CapabilitySupport.SUPPORTED) {
                    options.addAll(modelCapabilities.supportedReasoningEfforts().stream()
                            .map(e -> e.wireValue().substring(0, 1).toUpperCase() + e.wireValue().substring(1))
                            .toList());
                }
                yield List.copyOf(options);
            }
            case "thinking" -> List.of("On", "Off");
            case "settings" -> {
                var options = new ArrayList<String>();
                if (modelCapabilities.reasoning() != CapabilitySupport.UNSUPPORTED) options.add("Mode");
                if (modelCapabilities.reasoning() != CapabilitySupport.UNSUPPORTED) options.add("Thinking");
                if (modelCapabilities.temperature() == CapabilitySupport.SUPPORTED) options.add("Temperature");
                if (modelCapabilities.topP() == CapabilitySupport.SUPPORTED) options.add("Top P");
                if (modelCapabilities.verbosity() == CapabilitySupport.SUPPORTED) options.add("Verbosity");
                yield List.copyOf(options);
            }
            case "temperature" -> List.of("0.0", "0.3", "0.7", "1.0");
            case "top p" -> List.of("0.5", "0.9", "1.0");
            case "verbosity" -> List.of("Default", "low", "medium", "high");
            default -> List.of();
        };
        if (runtimePopupOptions.isEmpty()) {
            chatMessages.add(new ChatMessage("system", "No model runtime settings are known for this provider/model."));
            return;
        }
        runtimePopupCursor = 0;
        inputMode = TuiMode.POPUP;
        runtimePopupOpen = true;
    }

    private UpdateResult<DevMeshModel> handleRuntimePopupKey(KeyPressMessage kpm) {
        String key = kpm.key();
        if (key.equals("escape")) {
            runtimePopupOpen = false;
            inputMode = TuiMode.NORMAL;
            return UpdateResult.from(this);
        }
        if (key.equals("up") || key.equals("k")) {
            runtimePopupCursor = Math.max(0, runtimePopupCursor - 1);
            return UpdateResult.from(this);
        }
        if (key.equals("down") || key.equals("j")) {
            runtimePopupCursor = Math.min(runtimePopupOptions.size() - 1, runtimePopupCursor + 1);
            return UpdateResult.from(this);
        }
        if (key.equals("enter")) {
            String selected = runtimePopupOptions.get(runtimePopupCursor);
            String kind = runtimePopupKind;
            runtimePopupOpen = false;
            inputMode = TuiMode.NORMAL;
            if ("settings".equals(kind)) {
                openRuntimePopup(selected.toLowerCase());
            } else if ("mode".equals(kind)) {
                applyMode(selected);
            } else if ("thinking".equals(kind)) {
                applyThinking(selected);
            } else if ("temperature".equals(kind)) {
                runtimeSettings.setTemperature(Double.valueOf(selected));
                if (client != null) client.setRuntimeSettings(runtimeSettings);
            } else if ("top p".equals(kind)) {
                runtimeSettings.setTopP(Double.valueOf(selected));
                if (client != null) client.setRuntimeSettings(runtimeSettings);
            } else if ("verbosity".equals(kind)) {
                runtimeSettings.setVerbosity("Default".equals(selected) ? null : selected);
                if (client != null) client.setRuntimeSettings(runtimeSettings);
            }
        }
        return UpdateResult.from(this);
    }

    private void applyMode(String value) {
        if ("default".equalsIgnoreCase(value)) {
            runtimeSettings.setReasoningEffort(null);
            runtimeSettings.setThinkingEnabled(false);
            if (client != null) client.setRuntimeSettings(runtimeSettings);
            return;
        }
        ReasoningEffort effort = ReasoningEffort.parse(value);
        if (effort == null || modelCapabilities.reasoningEffort() != CapabilitySupport.SUPPORTED
                || !modelCapabilities.supportedReasoningEfforts().contains(effort)) {
            String supported = "default";
            if (modelCapabilities.reasoningEffort() == CapabilitySupport.SUPPORTED) {
                supported += ", " + String.join(", ", modelCapabilities.supportedReasoningEfforts().stream()
                        .map(ReasoningEffort::wireValue).toList());
            }
            chatMessages.add(new ChatMessage("error", "Mode '" + value + "' is not supported by the current model.\nSupported modes: " + supported));
            return;
        }
        runtimeSettings.setReasoningEffort(effort);
        runtimeSettings.setThinkingEnabled(true);
        if (client != null) client.setRuntimeSettings(runtimeSettings);
    }

    private void applyThinking(String value) {
        if (!modelCapabilities.supportsThinking()) {
            chatMessages.add(new ChatMessage("error", "Thinking control is not supported by the current model/provider."));
            return;
        }
        if (!"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
            chatMessages.add(new ChatMessage("error", "Usage: /thinking [on|off]"));
            return;
        }
        runtimeSettings.setThinkingEnabled("on".equalsIgnoreCase(value));
        if (client != null) client.setRuntimeSettings(runtimeSettings);
    }

    private UpdateResult<DevMeshModel> handleTodoPopupKey(KeyPressMessage kpm) {
        String key = kpm.key();
        var tasks = taskList == null ? List.<TaskList.Task>of() : taskList.list();
        if (key.equals("escape")) {
            todoPopupOpen = false;
            inputMode = TuiMode.NORMAL;
        } else if ((key.equals("down") || key.equals("j")) && !tasks.isEmpty()) {
            todoPopupCursor = Math.min(tasks.size() - 1, todoPopupCursor + 1);
        } else if ((key.equals("up") || key.equals("k")) && !tasks.isEmpty()) {
            todoPopupCursor = Math.max(0, todoPopupCursor - 1);
        }
        return UpdateResult.from(this);
    }

    private void openTodoPopup() {
        todoPopupOpen = true;
        todoPopupCursor = 0;
        inputMode = TuiMode.POPUP;
    }

    private String renderTodoPopup() {
        if (taskList == null) return "";
        var tasks = taskList.list();
        var sb = new StringBuilder();
        sb.append(Styles.selectLabel.render("  ┌─ Todo ───────────────────────────────┐")).append("\n");
        if (tasks.isEmpty()) {
            sb.append(Styles.toolDetail.render("  │ No orchestrator tasks yet.")).append("\n");
        } else {
            for (int i = 0; i < tasks.size(); i++) {
                var task = tasks.get(i);
                String marker = switch (task.getStatus()) {
                    case "completed" -> "✓";
                    case "in_progress" -> "→";
                    case "failed" -> "✗";
                    case "blocked" -> "!";
                    case "skipped" -> "-";
                    default -> "○";
                };
                var style = i == todoPopupCursor ? Styles.selectedItem : Styles.normalItem;
                sb.append(style.render("  │ " + marker + " " + task.getSubject())).append("\n");
            }
        }
        long completed = tasks.stream().filter(t -> TaskList.Status.COMPLETED.value().equals(t.getStatus())).count();
        sb.append(Styles.toolDetail.render("  │ " + completed + "/" + tasks.size() + " completed")).append("\n");
        sb.append(Styles.toolDetail.render("  └──────────────────────────────────────┘")).append("\n");
        return sb.toString();
    }

    private UpdateResult<DevMeshModel> executeSlashCommand(devmesh.command.Command cmd, String args) {
        return switch (cmd.type()) {
            case LOCAL -> {
                var ctx = buildCommandContext(args);
                String output = cmdRegistry.execute(cmd.name(), ctx);
                if (output != null && !output.isEmpty()) {
                    chatMessages.add(new ChatMessage("system", output));
                }
                yield UpdateResult.from(this);
            }
            case LOCAL_UI -> {
                switch (cmd.name()) {
                    case "quit" -> { }
                    case "mode" -> {
                        if (args == null || args.isBlank()) openRuntimePopup("mode");
                        else applyMode(args.strip());
                    }
                    case "thinking" -> {
                        if (args == null || args.isBlank()) openRuntimePopup("thinking");
                        else applyThinking(args.strip());
                    }
                    case "model-settings" -> openRuntimePopup("settings");
                    case "todo" -> openTodoPopup();
                    case "model", "provider" -> {
                        state = AppState.PROVIDER_SELECT;
                        providerCursor = Math.max(0, providers.indexOf(selectedProvider));
                        inputMode = TuiMode.NORMAL;
                    }
                    case "clear" -> {
                        chatMessages.clear();
                        committedUpTo = 0;
                        conversation = new ConversationManager();

                        var wd = System.getProperty("user.dir");
                        sessionId = devmesh.session.SessionManager.newId();
                        fileHistory = new devmesh.filehistory.FileHistory(wd, sessionId);
                        var fileStateCache = new devmesh.tool.FileStateCache();
                        for (var t : registry.listTools()) {
                            if (t instanceof devmesh.tool.impl.EditFileTool ef) { ef.setFileHistory(fileHistory); ef.setFileStateCache(fileStateCache); }
                            if (t instanceof devmesh.tool.impl.WriteFileTool wf) { wf.setFileHistory(fileHistory); wf.setFileStateCache(fileStateCache); }
                            if (t instanceof devmesh.tool.impl.ReadFileTool rf) rf.setFileStateCache(fileStateCache);
                        }
                        if (agent != null) {
                            agent.setFileHistory(fileHistory);
                            agent.setSessionId(sessionId);
                            agent.setToolNameFilter(null);
                        }
                        taskList = new TaskList("default", wd);
                        smartOrchestrator = new SmartOrchestrator(taskList);
                        todoSynchronizer = new AgentTodoSynchronizer(taskList);
                        totalInput = 0;
                        totalOutput = 0;

                        yield UpdateResult.from(this,
                                Command.println("\033[2J\033[3J\033[H" + renderBanner() + "\n"));
                    }
                    case "compact" -> {
                        if (agent != null) {
                            try {
                                var schemas = agent.getRegistry() != null
                                        ? agent.getRegistry().getAllSchemas(agent.getProtocol())
                                        : java.util.List.<java.util.Map<String, Object>>of();
                                String msg = devmesh.compact.ContextCompactor.forceCompact(
                                        conversation, client, selectedProvider.resolvedContextWindow(),
                                        System.getProperty("user.dir"), sessionId,
                                        agent.getRecoveryState(), schemas);
                                chatMessages.add(new ChatMessage("system", "⟳ " + msg));
                            } catch (Exception e) {
                                chatMessages.add(new ChatMessage("error", "Compact failed: " + e.getMessage()));
                            }
                        }
                    }
                    case "plan" -> {
                        if (permChecker != null) {
                            prePlanMode = permChecker.getMode();
                            permChecker.setMode(PermissionMode.PLAN);
                            String planPath = PlanFile.getOrCreatePlanPath(
                                    System.getProperty("user.dir"));
                            chatMessages.add(new ChatMessage("system",
                                    "Entered Plan mode. Plan file: %s\nExplore the codebase and design your approach."
                                            .formatted(planPath)));

                            if (hasExitedPlanMode && PlanFile.planExists()) {
                                String reentryReminder = PlanModePrompt.buildReentryReminder(planPath);
                                conversation.addSystemReminder(reentryReminder);
                                hasExitedPlanMode = false;
                            }
                        }
                    }
                    case "resume" -> {
                        resumeSessions = SessionManager.listSessions(System.getProperty("user.dir"));
                        resumeFiltered = new ArrayList<>(resumeSessions);
                        resumeCursor = 0;
                        resumeSearch = "";
                        state = AppState.RESUME;
                    }
                    case "rewind" -> {
                        if (fileHistory == null || !fileHistory.hasSnapshots()) {
                            chatMessages.add(new ChatMessage("system", "No checkpoints to rewind to."));
                        } else {
                            rewindSnapshots = fileHistory.getSnapshots();
                            rewindCursor = rewindSnapshots.size() - 1;
                            rewindPhase = 0;
                            rewindOptionCursor = 0;
                            rewindDialog = true;
                        }
                    }
                }
                yield "quit".equals(cmd.name())
                    ? UpdateResult.from(this, Command.of(QuitMessage::new))
                    : UpdateResult.from(this);
            }
            case PROMPT -> {
                boolean isSkill = cmd.description() != null && cmd.description().endsWith("[skill]");
                String prompt = cmdRegistry.execute(cmd.name(), buildCommandContext(args));

                String displayText = "/" + cmd.name();
                if (args != null && !args.isEmpty()) displayText += " " + args;
                chatMessages.add(new ChatMessage("user", displayText));
                String userLine = Styles.prompt.render("❯ ") + Styles.userText.render(displayText) + "\n";
                committedUpTo = chatMessages.size();
                SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "user", displayText);
                userHasSentMessage = true;

                if (prompt != null && !prompt.isBlank()) {
                    conversation.addUserMessage(prompt);
                }
                if (args != null && !args.isEmpty()) {
                    conversation.addUserMessage(args);
                }

                streaming = true;
                streamBuf.setLength(0);
                thinkingVerb = SpinnerVerbs.random();
                thinkingStartMs = System.currentTimeMillis();

                fireHook(HookEngine.EventName.TURN_START, null, null);

                try {
                    agentQueue = agent.run(conversation);
                    if (askUserTool != null) askUserTool.setEventQueue(agentQueue);
                } catch (Exception e) {
                    streaming = false;
                    chatMessages.add(new ChatMessage("error", "Agent error: " + e.getMessage()));
                    yield UpdateResult.from(this);
                }

                var pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
                if (isSkill) {
                    String loadedLine = Styles.system.render(
                        "  skill(" + cmd.name() + ")\n  Successfully loaded skill");
                    yield UpdateResult.from(this, Command.batch(Command.println(userLine), Command.println(loadedLine), pollCmd));
                }
                yield UpdateResult.from(this, Command.batch(Command.println(userLine), pollCmd));
            }
        };
    }

    private devmesh.command.CommandContext buildCommandContext(String args) {
        String workDir = System.getProperty("user.dir");
        String model = selectedProvider != null ? selectedProvider.getModel() : "unknown";
        return new devmesh.command.CommandContext(
                args,
                workDir,
                model,
                () -> permChecker != null ? permChecker.getMode().name().toLowerCase() : "default",
                () -> registry != null ? registry.listTools().size() : 0,
                () -> new int[]{totalInput, totalOutput},
                () -> memoryManager != null ? memoryManager.getMemories() : java.util.List.of(),
                () -> { if (memoryManager != null) memoryManager.clear(); },
                () -> sessionId != null ? "Session: " + sessionId : "No active session",
                () -> skillCatalog != null
                        ? skillCatalog.list().stream().map(s -> s.name()).toList()
                        : java.util.List.of(),
                () -> {
                    if (skillCatalog == null) return 0;
                    String wd = System.getProperty("user.dir");
                    skillCatalog.reload(wd);
                    for (var meta : skillCatalog.list()) registerSkillCommand(meta.name());
                    if (client != null) client.setSystemPrompt(rebuildSystemPrompt(wd));
                    return skillCatalog.list().size();
                },
                () -> mcpServerInfo,
                this::getSandboxStatus,
                sandboxInstance != null && sandboxInstance.isAvailable() ? this::switchSandboxMode : null
        );
    }

    private String getSandboxStatus() {
        if (sandboxInstance == null || !sandboxInstance.isAvailable()) {
            return "Unavailable (the current platform is unsupported or the sandbox tool is not installed)";
        }
        if (permChecker != null && permChecker.isSandboxEnabled()) {
            return "Enabled (sandbox + auto-allow)";
        }

        boolean bashHasSandbox = false;
        for (var t : registry.listTools()) {
            if (t instanceof devmesh.tool.impl.BashTool bt) {
                bashHasSandbox = true;
                break;
            }
        }
        return bashHasSandbox ? "Enabled (sandbox + standard permissions)" : "Disabled";
    }

    /**
     */
    private void switchSandboxMode(int mode) {
        if (sandboxInstance == null || !sandboxInstance.isAvailable()) return;
        String workDir = System.getProperty("user.dir");

        switch (mode) {
            case 1 -> {

                sandboxConfig = new devmesh.sandbox.SandboxConfig(
                        java.util.List.of(workDir, "/tmp"),
                        permChecker != null ? permChecker.getDenyWrite() : java.util.List.of(),
                        true
                );
                for (var t : registry.listTools()) {
                    if (t instanceof devmesh.tool.impl.BashTool bt) {
                        bt.setSandbox(sandboxInstance);
                        bt.setSandboxConfig(sandboxConfig);
                    }
                }
                if (permChecker != null) permChecker.setSandboxEnabled(true);
            }
            case 2 -> {

                sandboxConfig = new devmesh.sandbox.SandboxConfig(
                        java.util.List.of(workDir, "/tmp"),
                        permChecker != null ? permChecker.getDenyWrite() : java.util.List.of(),
                        true
                );
                for (var t : registry.listTools()) {
                    if (t instanceof devmesh.tool.impl.BashTool bt) {
                        bt.setSandbox(sandboxInstance);
                        bt.setSandboxConfig(sandboxConfig);
                    }
                }
                if (permChecker != null) permChecker.setSandboxEnabled(false);
            }
            case 3 -> {

                for (var t : registry.listTools()) {
                    if (t instanceof devmesh.tool.impl.BashTool bt) {
                        bt.setSandbox(null);
                        bt.setSandboxConfig(null);
                    }
                }
                if (permChecker != null) permChecker.setSandboxEnabled(false);
            }
        }
    }

    private UpdateResult<DevMeshModel> sendUserMessage() {
        refreshSkillsIfNeeded();

        String userText = inputBuffer.toString().trim();
        inputBuffer.setLength(0); inputCursor = 0;
        userScrolled = false;
        scrollOffset = 0;

        if (userText.isEmpty()) {
            return UpdateResult.from(this);
        }

        if (smartOrchestrator != null && taskList.list().isEmpty()) {
            smartOrchestrator.plan(userText);
            smartOrchestrator.startNext();
        }

        if (conversation.getMessages().isEmpty() && memoryManager != null) {
            memoryManager.injectMemories(conversation);
        }

        chatMessages.add(new ChatMessage("user", userText));

        String userLine = Styles.prompt.render("❯ ") + Styles.userText.render(userText) + "\n";
        committedUpTo = chatMessages.size();

        userHasSentMessage = true;
        conversation.addUserMessage(userText);
        SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "user", userText);
        historyStore.append(userText);
        historyIndex = -1;

        if (!mcpInstructions.isEmpty() && !mcpInstructionsOk) {
            conversation.addSystemReminder(mcpInstructions);
            mcpInstructionsOk = true;
        }


        var prefetchFuture = prefetchRelevantMemories(userText);

        if (agent == null) {
            chatMessages.add(new ChatMessage("error", "No agent configured."));
            return UpdateResult.from(this);
        }

        streaming = true;
        streamBuf.setLength(0);
        thinkingVerb = SpinnerVerbs.random();
        thinkingStartMs = System.currentTimeMillis();

        fireHook(HookEngine.EventName.TURN_START, null, null);


        var queue = new java.util.concurrent.LinkedBlockingQueue<devmesh.agent.AgentEvent>(64);
        agentQueue = queue;
        if (todoSynchronizer != null) todoSynchronizer.setEventQueue(queue);
        if (askUserTool != null) askUserTool.setEventQueue(queue);



        agent.setMemoryRecallFuture(prefetchFuture);

        Thread.startVirtualThread(() -> {
            try {
                agent.run(conversation, queue);
            } catch (Exception e) {
                queue.offer(new devmesh.agent.AgentEvent.ErrorEvent(
                        "Agent error: " + e.getMessage()));
            }
        });

        Command pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
        return UpdateResult.from(this, Command.batch(Command.println(userLine), pollCmd));
    }


    // Streaming event processing


    private UpdateResult<DevMeshModel> handleAgentEvents() {
        drainSubAgentProgress();

        if (agentQueue == null) return UpdateResult.from(this);

        var events = new ArrayList<AgentEvent>();
        agentQueue.drainTo(events);

        boolean loopDone = false;

        boolean needsCommit = false;

        for (var event : events) {
            if (todoSynchronizer != null) todoSynchronizer.onEvent(event);
            switch (event) {
                case AgentEvent.StreamText e -> streamBuf.append(e.text());
                case AgentEvent.ThinkingText e -> {}
                case AgentEvent.ThinkingComplete e -> {}
                case AgentEvent.ToolUseEvent e -> {
                    boolean found = false;
                    for (int i = 0; i < toolBlocks.size(); i++) {
                        if (toolBlocks.get(i).toolName().equals(e.toolName())
                                && toolBlocks.get(i).args() == null) {
                            toolBlocks.set(i, new ChatMessage.ToolBlockInfo(
                                    e.toolName(), e.args(), null, false, 0, false, true));
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        toolBlocks.add(new ChatMessage.ToolBlockInfo(
                                e.toolName(), e.args(), null, false, 0, false, true));
                    }
                }
                case AgentEvent.ToolResultEvent e -> {
                    if (!streamBuf.isEmpty()) {
                        chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
                        streamBuf.setLength(0);
                    }
                    for (int i = 0; i < toolBlocks.size(); i++) {
                        var tb = toolBlocks.get(i);
                        if (tb.toolName().equals(e.toolName()) && tb.loading()) {
                            toolBlocks.set(i, new ChatMessage.ToolBlockInfo(
                                    e.toolName(), tb.args(), e.output(), e.isError(),
                                    e.elapsed(), true, false));
                            break;
                        }
                    }
                    commitCompletedToolBlocks();
                    needsCommit = true;
                }
                case AgentEvent.CommandStarted e -> {
                    chatMessages.add(new ChatMessage("system", "▶ " + e.command()));
                    needsCommit = true;
                }
                case AgentEvent.CommandOutput e -> {
                    chatMessages.add(new ChatMessage(e.stderr() ? "error" : "system", "  " + e.text()));
                    needsCommit = true;
                }
                case AgentEvent.CommandCompleted e -> {
                    chatMessages.add(new ChatMessage(e.exitCode() == 0 ? "system" : "error",
                            "■ command " + e.status() + " (exit " + e.exitCode() + ")"));
                    needsCommit = true;
                }
                case AgentEvent.CommandTimedOut e -> {
                    chatMessages.add(new ChatMessage("error", "■ command timed out"));
                    needsCommit = true;
                }
                case AgentEvent.CommandCancelled e -> {
                    chatMessages.add(new ChatMessage("system", "■ command cancelled"));
                    needsCommit = true;
                }
                case AgentEvent.RepairDiagnosing e -> {
                    chatMessages.add(new ChatMessage("system", "↻ Diagnosing " + e.intent() + " failure"));
                    needsCommit = true;
                }
                case AgentEvent.RepairDiagnosisCompleted e -> {
                    chatMessages.add(new ChatMessage("system", "↻ Diagnosis: " + e.failureType()));
                    needsCommit = true;
                }
                case AgentEvent.RepairPlanned e -> {
                    chatMessages.add(new ChatMessage("system", "↻ Repair planned: " + e.taskId()));
                    needsCommit = true;
                }
                case AgentEvent.RepairApplying e -> {
                    chatMessages.add(new ChatMessage("system", "↻ Applying repair: " + e.taskId()));
                    needsCommit = true;
                }
                case AgentEvent.RepairRetesting e -> {
                    chatMessages.add(new ChatMessage("system", "↻ Retesting repair: " + e.taskId()));
                    needsCommit = true;
                }
                case AgentEvent.RepairRetrying e -> {
                    chatMessages.add(new ChatMessage("system", "↻ Repair retry " + e.attempt()));
                    needsCommit = true;
                }
                case AgentEvent.RepairSucceeded e -> {
                    chatMessages.add(new ChatMessage("system", "✓ Repair verified: " + e.taskId()));
                    needsCommit = true;
                }
                case AgentEvent.RepairFailed e -> {
                    chatMessages.add(new ChatMessage("error", "✗ Repair failed: " + e.reason()));
                    needsCommit = true;
                }
                case AgentEvent.RepairCancelled e -> {
                    chatMessages.add(new ChatMessage("system", "■ Repair cancelled"));
                    needsCommit = true;
                }
                case AgentEvent.TurnComplete e -> {
                    if (!streamBuf.isEmpty()) {
                        chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
                        streamBuf.setLength(0);
                    }
                    commitToolBlocks();
                }
                case AgentEvent.LoopComplete e -> {
                    loopDone = true;
                    if (!streamBuf.isEmpty()) {
                        chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
                        SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "assistant", streamBuf.toString());
                        streamBuf.setLength(0);
                    }
                    commitToolBlocks();
                    double totalTime = (System.currentTimeMillis() - thinkingStartMs) / 1000.0;
                    String pastVerb = SpinnerVerbs.pastTense(thinkingVerb);
                    chatMessages.add(new ChatMessage("thinking",
                            "✻ %s for %.1fs".formatted(pastVerb, totalTime)));
                    fireHook(HookEngine.EventName.TURN_END, null, null);
                }
                case AgentEvent.UsageEvent e -> {
                    totalInput = e.inputTokens();
                    totalOutput = e.outputTokens();
                }
                case AgentEvent.ErrorEvent e -> {

                    if (!streamBuf.isEmpty()) {
                        chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
                        streamBuf.setLength(0);
                    }
                    chatMessages.add(new ChatMessage("error", e.message()));
                    needsCommit = true;
                }
                case AgentEvent.CompactEvent e ->
                        chatMessages.add(new ChatMessage("system", "⟳ " + e.message()));
                case AgentEvent.RetryEvent e -> {
                    String msg = "↻ Retrying: " + e.reason();
                    if (e.waitMs() > 0) msg += " (waiting %dms)".formatted(e.waitMs());
                    chatMessages.add(new ChatMessage("system", msg));
                }
                case AgentEvent.CheckpointCreated e ->
                    chatMessages.add(new ChatMessage("system", "Checkpoint saved: " + e.checkpointId()));
                case AgentEvent.RecoveryBlocked e ->
                    chatMessages.add(new ChatMessage("error", "Recovery blocked: " + e.reason()));
                case AgentEvent.PermissionRequestEvent e -> {
                    permDialog = true;
                    permToolName = e.toolName();
                    permDesc = e.description();
                    permCursor = 0;
                    pendingPermission = e.future();
                }
                case AgentEvent.AskUserRequestEvent e -> {
                    askUserDialogState.activate(e.questions());
                    askUserFuture = e.future();
                }
            }
        }

        if (!userScrolled) {
            scrollOffset = 0;
        }


        if (loopDone || needsCommit) {
            String commitText = renderMessagesRange(committedUpTo, chatMessages.size());
            committedUpTo = chatMessages.size();
            Command printCmd = !commitText.isEmpty() ? Command.println(commitText) : null;

            if (loopDone) {
                streaming = false;
                agentQueue = null;
                drainTaskNotifications();
                triggerMemoryExtraction();
                if (permChecker != null && permChecker.getMode() == PermissionMode.PLAN) {
                    planApprovalDialog.activate();
                }
                if (teamManager != null) {
                    var mailboxPoll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                    return UpdateResult.from(this, printCmd != null ? Command.batch(printCmd, mailboxPoll) : mailboxPoll);
                }
                return printCmd != null ? UpdateResult.from(this, printCmd) : UpdateResult.from(this);
            }

            Command pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
            return UpdateResult.from(this, printCmd != null ? Command.batch(printCmd, pollCmd) : pollCmd);
        }

        Command pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
        return UpdateResult.from(this, pollCmd);
    }

    private void fireHook(HookEngine.EventName event, String toolName, Map<String, Object> args) {
        if (hookEngine == null) return;
        var ctx = new HookEngine.HookContext(event, toolName, args, null, null, null);
        hookEngine.runHooks(ctx);
    }

    private String buildMemorySection() {
        if (memoryManager == null) return "";
        var memories = memoryManager.getMemories();
        if (memories.isEmpty()) return "";
        var sb = new StringBuilder("# Auto Memory\n\n");
        for (String mem : memories) {
            sb.append(mem).append("\n\n");
        }
        return sb.toString();
    }

    private void triggerMemoryExtraction() {
        if (memoryManager == null || client == null) return;
        if (!memoryManager.shouldExtract()) return;
        Thread.startVirtualThread(() -> memoryManager.extract(client, conversation));
    }


    // Memory recall prefetch


    /**
     * Runs the recall selector in a virtual thread and returns a future
     * that will complete with the rendered system-reminder string (or ""
     * if nothing was selected / selector timed out). Fires a fresh
     * side-query LlmClient per call so the selector's SYSTEM prompt is
     * independent of the main conversation's system prompt.
     */
    private CompletableFuture<String> prefetchRelevantMemories(String query) {
        if (memoryManager == null || selectedProvider == null) {
            return CompletableFuture.completedFuture("");
        }
        var provider = selectedProvider;
        var userDir = memoryManager.userMemDir();
        var projectDir = memoryManager.projectMemDir();

        return CompletableFuture.supplyAsync(() -> {
            MemoryRecall.SelectorFn selector = (systemPrompt, userMessage) -> {
                LlmClient sideClient = LlmClient.create(provider, systemPrompt);
                ConversationManager miniConv = new ConversationManager();
                miniConv.addUserMessage(userMessage);
                BlockingQueue<devmesh.llm.StreamEvent> events = sideClient.stream(miniConv, null);
                var sb = new StringBuilder();
                while (true) {
                    var event = events.take();
                    if (event instanceof devmesh.llm.StreamEvent.TextDelta td) {
                        sb.append(td.text());
                    } else if (event instanceof devmesh.llm.StreamEvent.StreamEnd
                            || event instanceof devmesh.llm.StreamEvent.Error) {
                        break;
                    }
                }
                return sb.toString();
            };
            var results = MemoryRecall.findRelevantMemories(
                    query, userDir, projectDir, null, null, selector);
            return MemoryRecall.renderReminder(results);
        }, runnable -> {
            // Run on a virtual thread with 8s timeout.
            Thread t = Thread.ofVirtual().name("memory-recall-prefetch").start(runnable);
            Thread.ofVirtual().start(() -> {
                try {
                    if (!t.join(java.time.Duration.ofSeconds(8))) {
                        t.interrupt();
                    }
                } catch (InterruptedException ignored) {}
            });
        });
    }

    /**
     * Waits up to 3 seconds for the prefetch future to produce a rendered
     * reminder, then injects it as a system-reminder on the given
     * conversation. If the timeout fires first, the prefetch keeps running
     * the user's main request.
     */
    private static void collectPrefetchedRecall(
            ConversationManager conv, CompletableFuture<String> prefetchFuture) {
        if (conv == null || prefetchFuture == null) return;
        try {
            String reminder = prefetchFuture.get(3, TimeUnit.SECONDS);
            if (reminder != null && !reminder.isEmpty()) {
                conv.addSystemReminder(reminder);
            }
        } catch (Exception ignored) {

        }
    }


    // Tool block management


    private static final Set<String> COLLAPSIBLE_TOOLS = Set.of(
            "ReadFile", "Glob", "Grep", "ToolSearch");

    private static boolean isCollapsibleTool(String name) {
        return COLLAPSIBLE_TOOLS.contains(name);
    }

    /**
     */
    private void commitCompletedToolBlocks() {
        var remaining = new ArrayList<ChatMessage.ToolBlockInfo>();
        var completed = new ArrayList<ChatMessage.ToolBlockInfo>();

        for (var tb : toolBlocks) {
            if (tb.loading()) {
                remaining.add(tb);
            } else {
                completed.add(tb);
            }
        }
        if (completed.isEmpty()) return;

        var visibleTools = new ArrayList<ChatMessage.ToolBlockInfo>();
        var collapsedTools = new ArrayList<ChatMessage.ToolBlockInfo>();

        for (var tb : completed) {
            if ("Agent".equals(tb.toolName()) && activeSubAgent != null) {
                if (!activeSubAgent.done) {
                    activeSubAgent.done = true;
                    activeSubAgent.toolCount = activeSubAgent.toolUses.size();
                    double total = 0;
                    for (var tu : activeSubAgent.toolUses) total += tu.elapsed();
                    activeSubAgent.totalTime = total;
                }
                var msg = new ChatMessage("sub_agent", null);
                msg.subAgentBlock = activeSubAgent;
                msg.expanded = false;
                chatMessages.add(msg);
                activeSubAgent = null;
            } else if (isCollapsibleTool(tb.toolName())) {
                collapsedTools.add(tb);
            } else {
                visibleTools.add(tb);
            }
        }

        for (var tb : visibleTools) {
            chatMessages.add(new ChatMessage("tool_visible",
                    renderToolBlockText(tb), List.of(tb)));
        }
        if (!collapsedTools.isEmpty()) {
            var msg = new ChatMessage("tool_collapsed", null, new ArrayList<>(collapsedTools));
            msg.expanded = false;
            chatMessages.add(msg);
        }

        toolBlocks.clear();
        toolBlocks.addAll(remaining);
    }

    private void commitToolBlocks() {
        if (toolBlocks.isEmpty()) return;

        var visibleTools = new ArrayList<ChatMessage.ToolBlockInfo>();
        var collapsedTools = new ArrayList<ChatMessage.ToolBlockInfo>();

        for (var tb : toolBlocks) {
            if ("Agent".equals(tb.toolName()) && activeSubAgent != null) {
                if (!activeSubAgent.done) {
                    activeSubAgent.done = true;
                    activeSubAgent.toolCount = activeSubAgent.toolUses.size();
                    double total = 0;
                    for (var tu : activeSubAgent.toolUses) total += tu.elapsed();
                    activeSubAgent.totalTime = total;
                }
                var msg = new ChatMessage("sub_agent", null);
                msg.subAgentBlock = activeSubAgent;
                msg.expanded = false;
                chatMessages.add(msg);
                activeSubAgent = null;
            } else if (isCollapsibleTool(tb.toolName())) {
                collapsedTools.add(tb);
            } else {
                visibleTools.add(tb);
            }
        }

        for (var tb : visibleTools) {
            chatMessages.add(new ChatMessage("tool_visible",
                    renderToolBlockText(tb), List.of(tb)));
        }

        if (!collapsedTools.isEmpty()) {
            var msg = new ChatMessage("tool_collapsed", null, new ArrayList<>(collapsedTools));
            msg.expanded = false;
            chatMessages.add(msg);
        }

        toolBlocks.clear();
    }

    private void savePartialResponse() {
        if (!streamBuf.isEmpty()) {
            chatMessages.add(new ChatMessage("assistant", streamBuf.toString()));
            conversation.addAssistantMessage(streamBuf.toString());
            SessionManager.saveMessage(System.getProperty("user.dir"), sessionId, "assistant", streamBuf.toString());
            streamBuf.setLength(0);
        }
        commitToolBlocks();
        chatMessages.add(new ChatMessage("system", "(response interrupted)"));
        streaming = false;
        agentQueue = null;
        userScrolled = false;
        scrollOffset = 0;
    }

    private static String renderToolBlockText(ChatMessage.ToolBlockInfo tb) {
        String title = formatToolTitle(tb.toolName(),
                tb.args() != null ? tb.args() : Map.of());
        if (tb.isError()) {
            return "✗ %s (%.1fs)".formatted(title, tb.elapsed());
        }
        return "✓ %s (%.1fs)".formatted(title, tb.elapsed());
    }

    private static String renderToolGroupSummary(List<ChatMessage.ToolBlockInfo> tools) {
        double totalElapsed = 0;
        int errors = 0;
        for (var tb : tools) {
            totalElapsed += tb.elapsed();
            if (tb.isError()) errors++;
        }
        if (errors > 0) {
            return "● Done (%d tool uses · %d errors · %.1fs)".formatted(
                    tools.size(), errors, totalElapsed);
        }
        return "● Done (%d tool uses · %.1fs)".formatted(tools.size(), totalElapsed);
    }

    private String renderToolBlock(ChatMessage.ToolBlockInfo tb) {
        String title = formatToolTitle(tb.toolName(),
                tb.args() != null ? tb.args() : Map.of());
        if (tb.loading()) {
            return Styles.toolRunning.render("  ● %s …".formatted(title));
        }
        if (tb.isError()) {
            return Styles.toolError.render("  ✗ %s — error (%.1fs)".formatted(title, tb.elapsed()));
        }
        return Styles.toolDone.render("  ✓ %s (%.1fs)".formatted(title, tb.elapsed()));
    }





    @Override
    public String dumpHistory() {
        if (chatMessages.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(renderMessagesRange(0, chatMessages.size()));
        return sb.toString();
    }

    private String renderMessagesRange(int from, int to) {
        var sb = new StringBuilder();
        for (int i = from; i < to && i < chatMessages.size(); i++) {
            var msg = chatMessages.get(i);
            switch (msg.role) {
                case "user" -> {
                    sb.append(Styles.prompt.render("❯ "));
                    sb.append(Styles.userText.render(msg.content));
                    sb.append("\n\n");
                }
                case "assistant" -> {
                    sb.append(Styles.aiMarker.render("● "));
                    sb.append(MarkdownRenderer.render(msg.content, width));
                    sb.append("\n");
                }
                case "thinking" -> {
                    if (msg.content != null) {
                        sb.append(Styles.toolDetail.render("  " + msg.content));
                        sb.append("\n");
                    }
                }
                case "tool_visible" -> {
                    sb.append("  ");
                    if (msg.content != null && msg.content.startsWith("✗")) {
                        sb.append(Styles.toolError.render(msg.content));
                    } else {
                        sb.append(Styles.toolDone.render(msg.content));
                    }
                    sb.append("\n");
                }
                case "tool_collapsed" -> {

                    if (msg.toolGroup != null) {
                        for (var tb : msg.toolGroup) {
                            sb.append("  ");
                            String text = renderToolBlockText(tb);
                            sb.append(tb.isError() ? Styles.toolError.render(text) : Styles.toolDone.render(text));
                            sb.append("\n");
                        }
                    }
                }
                case "tool_group" -> {
                    if (msg.toolGroup != null) {
                        for (var tb : msg.toolGroup) {
                            sb.append("  ");
                            String text = renderToolBlockText(tb);
                            sb.append(tb.isError() ? Styles.toolError.render(text) : Styles.toolDone.render(text));
                            sb.append("\n");
                        }
                    }
                }
                case "sub_agent" -> {
                    if (msg.subAgentBlock != null) {
                        sb.append(renderSubAgentBlock(msg.subAgentBlock, false));
                    }
                }
                case "system" -> {
                    sb.append(Styles.system.render("  " + msg.content));
                    sb.append("\n");
                }
                case "error" -> {
                    sb.append(Styles.error.render("  ✖ " + msg.content));
                    sb.append("\n");
                }
                default -> {
                    sb.append("  " + msg.content);
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }


    // Sub-agent display


    private void handleSubAgentProgress(SubAgentProgress progress) {
        subAgentProgressQueue.add(progress);
        if (program != null) {
            program.send(new AgentEventMessage());
        }
    }

    private void drainSubAgentProgress() {
        SubAgentProgress progress;
        while ((progress = subAgentProgressQueue.poll()) != null) {
            if (progress.done()) {
                if (activeSubAgent != null) {
                    activeSubAgent.done = true;
                    activeSubAgent.toolCount = progress.toolCount();
                    activeSubAgent.totalTime = progress.totalTime();
                }
            } else {
                if (activeSubAgent == null || activeSubAgent.done) {
                    activeSubAgent = new ChatMessage.SubAgentBlockState();
                    activeSubAgent.desc = progress.description();
                    activeSubAgent.agentType = progress.agentType();
                }
                if (progress.toolName() != null) {
                    activeSubAgent.toolUses.add(new ChatMessage.ToolBlockInfo(
                            progress.toolName(), Map.of(), progress.toolOutput(),
                            progress.toolError(), 0, false, false));
                }
            }
        }
    }

    private String renderSubAgentBlock(ChatMessage.SubAgentBlockState sab, boolean expanded) {
        var sb = new StringBuilder();
        String label = sab.agentType != null && !sab.agentType.isEmpty()
                ? sab.agentType.substring(0, 1).toUpperCase() + sab.agentType.substring(1)
                : "Agent";
        sb.append(Styles.aiMarker.render("● %s(%s)".formatted(label, sab.desc)));
        sb.append("\n");

        if (sab.done) {
            if (expanded) {
                for (var tu : sab.toolUses) {
                    String title = formatToolTitle(tu.toolName(),
                            tu.args() != null ? tu.args() : Map.of());
                    String line = "     %s (%.1fs)".formatted(title, tu.elapsed());
                    sb.append(tu.isError()
                            ? Styles.toolError.render(line)
                            : Styles.toolDone.render(line));
                    sb.append("\n");
                }
            } else {
                sb.append(Styles.toolDone.render("  ⎿  Done (%d tool uses · %.1fs)".formatted(
                        sab.toolCount, sab.totalTime)));
                sb.append(Styles.toolDetail.render("  (ctrl+o to expand)"));
                sb.append("\n");
            }
        } else {
            int n = sab.toolUses.size();
            if (n > 0) {
                var last = sab.toolUses.get(n - 1);
                sb.append(Styles.toolDone.render("  ⎿  %s".formatted(
                        formatToolTitle(last.toolName(),
                                last.args() != null ? last.args() : Map.of()))));
                sb.append("\n");
            }
            if (n > 1) {
                sb.append(Styles.toolDetail.render("     … +%d tool uses (ctrl+o to expand)".formatted(n - 1)));
                sb.append("\n");
            }
            sb.append(Styles.toolDetail.render("     Running…"));
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }


    // Teammate spinner tree


    private String renderTeammateTree() {
        var progressList = teamManager.getAllTeammateProgress();
        if (progressList.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("\n");
        // Leader line
        sb.append("  ┌─ ").append(Styles.cyan("team-lead"))
          .append(": ").append(Styles.dim(thinkingVerb + "…"));
        if (totalInput + totalOutput > 0) {
            sb.append(Styles.dim(" · " + TeammateProgress.formatTokens(totalInput + totalOutput) + " tokens"));
        }
        sb.append("\n");

        // Teammate lines
        for (int i = 0; i < progressList.size(); i++) {
            var p = progressList.get(i);
            boolean isLast = (i == progressList.size() - 1);
            String connector = isLast ? "  └─ " : "  ├─ ";

            sb.append(connector).append(Styles.cyan("@" + p.getName())).append(": ");

            switch (p.getStatus()) {
                case "completed" -> sb.append(Styles.green("completed"));
                case "failed" -> sb.append(Styles.red("failed"));
                case "stopped" -> sb.append(Styles.yellow("stopped"));
                case "idle" -> sb.append(Styles.dim("idle"));
                default -> sb.append(Styles.dim(p.getActivitySummary() + "..."));
            }

            sb.append(Styles.dim(" · " + p.getToolUseCount() + " tools · "
                    + TeammateProgress.formatTokens(p.getTokenCount()) + " tokens"));
            sb.append("\n");
        }

        return sb.toString();
    }


    // Plan approval


    private UpdateResult<DevMeshModel> handlePlanApprovalKey(KeyPressMessage kpm) {
        var result = planApprovalDialog.handleKey(kpm.key());
        if (result == null) return UpdateResult.from(this);

        switch (result.type()) {
            case YOLO -> {
                if (permChecker != null) permChecker.setMode(PermissionMode.BYPASS);
                chatMessages.add(new ChatMessage("system",
                        "Plan approved. Entered YOLO mode (all operations auto-approved)."));

                String exitPath = PlanFile.getOrCreatePlanPath(System.getProperty("user.dir"));
                String exitReminder = PlanModePrompt.buildExitReminder(exitPath, PlanFile.planExists());
                conversation.addSystemReminder(exitReminder);
                hasExitedPlanMode = true;
                PlanFile.resetPlanPath();
            }
            case MANUAL -> {
                if (permChecker != null) {
                    var restoreMode = prePlanMode != null ? prePlanMode : PermissionMode.DEFAULT;
                    permChecker.setMode(restoreMode);
                }
                chatMessages.add(new ChatMessage("system",
                        "Plan approved. Each edit will require your confirmation."));

                String exitPath = PlanFile.getOrCreatePlanPath(System.getProperty("user.dir"));
                String exitReminder = PlanModePrompt.buildExitReminder(exitPath, PlanFile.planExists());
                conversation.addSystemReminder(exitReminder);
                hasExitedPlanMode = true;
                PlanFile.resetPlanPath();
            }
            case FEEDBACK -> {
                String feedback = result.feedback();
                inputBuffer.setLength(0); inputCursor = 0;
                inputBuffer.append(feedback); inputCursor = inputBuffer.length();
                return sendUserMessage();
            }
            case CANCEL -> {}
        }
        return UpdateResult.from(this);
    }


    // Task notifications


    private void drainTaskNotifications() {
        if (subAgentTaskManager == null) return;
        var notifications = subAgentTaskManager.drainNotifications();
        for (var n : notifications) {
            chatMessages.add(new ChatMessage("system",
                    "Task %s: %s (%s)".formatted(n.taskId(), n.name(), n.status())));
        }
    }

    private static String formatToolTitle(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return toolName;
        return switch (toolName) {
            case "ReadFile" -> {
                var p = args.get("file_path");
                yield "Read " + (p != null ? Path.of(p.toString()).getFileName() : toolName);
            }
            case "WriteFile" -> {
                var p = args.get("file_path");
                var content = args.get("content");
                String name = p != null ? Path.of(p.toString()).getFileName().toString() : "file";
                int lines = content instanceof String s ? s.split("\n", -1).length : 0;
                yield lines > 0 ? "Write %s (%d lines)".formatted(name, lines) : "Write " + name;
            }
            case "EditFile" -> {
                var p = args.get("file_path");
                yield "Edit " + (p != null ? Path.of(p.toString()).getFileName() : toolName);
            }
            case "Bash" -> {
                var cmd = args.get("command");
                if (cmd == null) yield toolName;
                String s = cmd.toString();
                yield s.length() > 50 ? "Bash: " + s.substring(0, 47) + "…" : "Bash: " + s;
            }
            case "Glob" -> "Glob: " + args.getOrDefault("pattern", "");
            case "Grep" -> "Grep: " + args.getOrDefault("pattern", "");
            default -> toolName;
        };
    }


    // Resume screen


    private UpdateResult<DevMeshModel> handleResumeKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> { if (resumeCursor > 0) resumeCursor--; yield UpdateResult.from(this); }
            case "down", "j" -> { if (resumeCursor < resumeFiltered.size() - 1) resumeCursor++; yield UpdateResult.from(this); }
            case "escape" -> { state = AppState.CHAT; yield UpdateResult.from(this); }
            case "enter" -> {
                if (!resumeFiltered.isEmpty()) {
                    var session = resumeFiltered.get(resumeCursor);
                    var messages = SessionManager.loadSession(System.getProperty("user.dir"), session.id());
                    sessionId = session.id();
                    // Keep the Agent's session log pointer in sync so a later
                    // compaction writes its boundary into this same file (chained
                    // resume); the fileHistory pointer follows too.
                    if (agent != null) agent.setSessionId(sessionId);
                    fileHistory = new devmesh.filehistory.FileHistory(
                            System.getProperty("user.dir"), sessionId);
                    if (agent != null) agent.setFileHistory(fileHistory);

                    // Compaction-aware rebuild: when the session has a
                    // compact_boundary, the live conversation is the compacted
                    // state (summary + kept tail + messages appended after the
                    // boundary); the pre-compaction prefix stays in the file for
                    // audit but is not replayed. Without a boundary (old sessions)
                    // everything replays verbatim.
                    conversation = SessionManager.rebuildConversation(messages);
                    var scan = SessionManager.findLastCompactBoundary(messages);
                    chatMessages.clear();
                    if (scan.found()) {
                        chatMessages.add(new ChatMessage("user", scan.boundary().summary()));
                        for (var k : scan.boundary().keep()) {
                            chatMessages.add(new ChatMessage(k.role(), k.content()));
                        }
                        for (var msg : scan.after()) {
                            chatMessages.add(new ChatMessage(msg.role(), msg.content()));
                        }
                        chatMessages.add(new ChatMessage("system",
                                "Resumed session %s from compacted state (summary + %d kept + %d newer messages)"
                                        .formatted(session.id(), scan.boundary().keep().size(), scan.after().size())));
                    } else {
                        for (var msg : messages) {
                            chatMessages.add(new ChatMessage(msg.role(), msg.content()));
                        }
                        chatMessages.add(new ChatMessage("system",
                                "Resumed session " + session.id() + " (%d messages)".formatted(messages.size())));
                    }
                }
                state = AppState.CHAT;

                String commitText = renderMessagesRange(0, chatMessages.size());
                committedUpTo = chatMessages.size();
                yield !commitText.isEmpty()
                        ? UpdateResult.from(this, Command.println(commitText))
                        : UpdateResult.from(this);
            }
            case "backspace", "ctrl+h" -> {
                if (!resumeSearch.isEmpty()) {
                    resumeSearch = resumeSearch.substring(0, resumeSearch.length() - 1);
                    filterResumeSessions();
                }
                yield UpdateResult.from(this);
            }
            default -> {
                char[] runes = kpm.runes();
                if (runes != null && runes.length > 0) {
                    for (char ch : runes) if (ch >= 32) resumeSearch += ch;
                    filterResumeSessions();
                }
                yield UpdateResult.from(this);
            }
        };
    }

    private void filterResumeSessions() {
        if (resumeSearch.isEmpty()) {
            resumeFiltered = new ArrayList<>(resumeSessions);
        } else {
            String lower = resumeSearch.toLowerCase();
            resumeFiltered = resumeSessions.stream()
                    .filter(s -> s.firstMessage().toLowerCase().contains(lower)
                            || s.id().contains(lower))
                    .toList();
        }
        resumeCursor = 0;
    }

    private String viewResume() {
        var sb = new StringBuilder();
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(Styles.selectLabel.render("  Resume a Session"));
        sb.append("\n");
        if (!resumeSearch.isEmpty()) {
            sb.append(Styles.statusBar.render("  Search: " + resumeSearch + "█"));
            sb.append("\n");
        }
        sb.append("\n");

        if (resumeFiltered.isEmpty()) {
            sb.append(Styles.statusBar.render("  No sessions found."));
            sb.append("\n");
        } else {
            int start = Math.max(0, resumeCursor - 5);
            int end = Math.min(resumeFiltered.size(), start + 12);
            for (int i = start; i < end; i++) {
                var s = resumeFiltered.get(i);
                String marker = i == resumeCursor ? " ❯ " : "   ";
                var style = i == resumeCursor ? Styles.selectedItem : Styles.normalItem;
                String preview = s.firstMessage().length() > 50
                        ? s.firstMessage().substring(0, 47) + "..."
                        : s.firstMessage();
                String meta = "%s · %d msgs".formatted(s.id(), s.messageCount());
                sb.append(style.render(marker + preview));
                sb.append("\n");
                sb.append(Styles.statusBar.render("     " + meta));
                sb.append("\n");
            }
        }

        sb.append("\n");
        sb.append(Styles.statusBar.render("  j/k to move · enter to resume · escape to cancel · type to search"));
        sb.append("\n");
        return sb.toString();
    }


    // Permission dialog


    private UpdateResult<DevMeshModel> handlePermDialogKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> { if (permCursor > 0) permCursor--; yield UpdateResult.from(this); }
            case "down", "j" -> { if (permCursor < 2) permCursor++; yield UpdateResult.from(this); }
            case "enter" -> {
                var response = switch (permCursor) {
                    case 0 -> PermissionResponse.ALLOW;
                    case 1 -> PermissionResponse.ALLOW_ALWAYS;
                    default -> PermissionResponse.DENY;
                };
                if (pendingPermission != null) pendingPermission.complete(response);
                permDialog = false;
                pendingPermission = null;
                yield UpdateResult.from(this);
            }
            case "escape" -> {
                if (pendingPermission != null) pendingPermission.complete(PermissionResponse.DENY);
                permDialog = false;
                pendingPermission = null;
                yield UpdateResult.from(this);
            }
            default -> UpdateResult.from(this);
        };
    }


    // Rewind dialog


    private UpdateResult<DevMeshModel> handleRewindKey(KeyPressMessage kpm) {
        String key = kpm.key();
        if (rewindPhase == 0) {
            return switch (key) {
                case "escape" -> { rewindDialog = false; yield UpdateResult.from(this); }
                case "up", "k" -> { if (rewindCursor > 0) rewindCursor--; yield UpdateResult.from(this); }
                case "down", "j" -> { if (rewindCursor < rewindSnapshots.size() - 1) rewindCursor++; yield UpdateResult.from(this); }
                case "enter" -> { rewindPhase = 1; rewindOptionCursor = 0; yield UpdateResult.from(this); }
                default -> UpdateResult.from(this);
            };
        }
        // Phase 1: option selection
        return switch (key) {
            case "escape" -> { rewindPhase = 0; yield UpdateResult.from(this); }
            case "up", "k" -> { if (rewindOptionCursor > 0) rewindOptionCursor--; yield UpdateResult.from(this); }
            case "down", "j" -> { if (rewindOptionCursor < REWIND_OPTIONS.length - 1) rewindOptionCursor++; yield UpdateResult.from(this); }
            case "enter" -> executeRewindOption();
            default -> UpdateResult.from(this);
        };
    }

    private UpdateResult<DevMeshModel> executeRewindOption() {
        var snap = rewindSnapshots.get(rewindCursor);
        switch (rewindOptionCursor) {
            case 0 -> { // Restore code and conversation
                var changed = fileHistory.rewind(rewindCursor);
                conversation.truncateTo(snap.messageIndex());
                chatMessages.clear();
                chatMessages.add(new ChatMessage("system",
                        "⟲ Rewound to checkpoint %d. Restored %d file(s) and conversation.".formatted(
                                rewindCursor + 1, changed.size())));
            }
            case 1 -> { // Restore conversation only
                conversation.truncateTo(snap.messageIndex());
                chatMessages.clear();
                chatMessages.add(new ChatMessage("system",
                        "⟲ Rewound conversation to checkpoint %d. Files unchanged.".formatted(rewindCursor + 1)));
            }
            case 2 -> { // Restore code only
                var changed = fileHistory.rewind(rewindCursor);
                chatMessages.add(new ChatMessage("system",
                        "⟲ Restored %d file(s) to checkpoint %d. Conversation unchanged.".formatted(
                                changed.size(), rewindCursor + 1)));
            }
            case 3 -> { // Never mind
                rewindDialog = false;
                rewindPhase = 0;
                return UpdateResult.from(this);
            }
        }
        rewindDialog = false;
        rewindPhase = 0;
        return UpdateResult.from(this);
    }


    // AskUser dialog


    private UpdateResult<DevMeshModel> handleAskUserDialogKey(KeyPressMessage kpm) {
        var result = askUserDialogState.handleKey(kpm.key());
        if (result != null) {
            if (askUserFuture != null) {
                askUserFuture.complete(result);
                askUserFuture = null;
            }
        }
        return UpdateResult.from(this);
    }


    // Chat view rendering


    private String runtimeStatusLabel() {
        String mode = modelCapabilities.reasoningEffort() == CapabilitySupport.UNKNOWN
                ? "Unknown"
                : runtimeSettings.reasoningEffort() == null ? "Default" : runtimeSettings.reasoningEffort().wireValue();
        String thinking = switch (modelCapabilities.reasoning()) {
            case SUPPORTED -> Boolean.TRUE.equals(runtimeSettings.thinkingEnabled()) ? "Thinking ON" : "Thinking OFF";
            case UNSUPPORTED -> "Thinking N/A";
            case UNKNOWN -> "Thinking Unknown";
        };
        return mode + " · " + thinking;
    }

    private String renderRuntimePopup() {
        var sb = new StringBuilder();
        String title = switch (runtimePopupKind) {
            case "mode" -> "Model Mode";
            case "thinking" -> "Thinking";
            case "temperature" -> "Temperature";
            case "top p" -> "Top P";
            case "verbosity" -> "Verbosity";
            default -> "Model Settings";
        };
        sb.append(Styles.selectLabel.render("  ┌─ " + title + " ─────────────────────┐")).append("\n");
        if ("mode".equals(runtimePopupKind) || "thinking".equals(runtimePopupKind)) {
            sb.append(Styles.toolDetail.render("  │ Model: " + (selectedProvider == null ? "unknown" : selectedProvider.getModel()))).append("\n");
        }
        for (int i = 0; i < runtimePopupOptions.size(); i++) {
            String marker = i == runtimePopupCursor ? "❯ " : "  ";
            var style = i == runtimePopupCursor ? Styles.selectedItem : Styles.normalItem;
            sb.append(style.render("  │ " + marker + runtimePopupOptions.get(i))).append("\n");
        }
        sb.append(Styles.toolDetail.render("  └────────────────────────────────────┘")).append("\n");
        return sb.toString();
    }

    private String viewChat() {
        var sb = new StringBuilder();
        if (!bannerPrinted) {
            sb.append(renderBanner());
            sb.append("\n\n");
        }


        for (int idx = committedUpTo; idx < chatMessages.size(); idx++) {
            var msg = chatMessages.get(idx);
            switch (msg.role) {
                case "user" -> {
                    sb.append(Styles.prompt.render("❯ "));
                    sb.append(Styles.userText.render(msg.content));
                    sb.append("\n\n");
                }
                case "assistant" -> {
                    sb.append(Styles.aiMarker.render("● "));
                    sb.append(MarkdownRenderer.render(msg.content, width));
                    sb.append("\n");
                }
                case "thinking" -> {
                    if (msg.content != null) {
                        sb.append(Styles.toolDetail.render("  "));
                        String preview = msg.content.length() > 80
                                ? msg.content.substring(0, 77) + "..."
                                : msg.content;
                        sb.append(Styles.toolDetail.render(preview));
                        sb.append("\n");
                    }
                }
                case "tool" -> {
                    sb.append(Styles.toolRunning.render("  " + msg.content));
                    sb.append("\n");
                }
                case "tool_visible" -> {
                    sb.append("  ");
                    if (msg.content != null && msg.content.startsWith("✗")) {
                        sb.append(Styles.toolError.render(msg.content));
                    } else {
                        sb.append(Styles.toolDone.render(msg.content));
                    }
                    sb.append("\n");
                }
                case "tool_collapsed" -> {
                    if (msg.toolGroup != null) {
                        if (msg.expanded) {
                            for (var tb : msg.toolGroup) {
                                sb.append("  ");
                                String text = renderToolBlockText(tb);
                                sb.append(tb.isError()
                                        ? Styles.toolError.render(text)
                                        : Styles.toolDone.render(text));
                                sb.append("\n");
                            }
                        } else {
                            sb.append(Styles.toolDone.render("  " + renderToolGroupSummary(msg.toolGroup)));
                            sb.append(Styles.toolDetail.render("  (ctrl+o to expand)"));
                            sb.append("\n");
                        }
                    }
                }
                case "tool_group" -> {
                    if (msg.expanded && msg.toolGroup != null) {
                        for (var tb : msg.toolGroup) {
                            sb.append("  ");
                            String text = renderToolBlockText(tb);
                            sb.append(tb.isError()
                                    ? Styles.toolError.render(text)
                                    : Styles.toolDone.render(text));
                            sb.append("\n");
                        }
                    } else if (msg.toolGroup != null) {
                        sb.append(Styles.toolDone.render("  " + renderToolGroupSummary(msg.toolGroup)));
                        sb.append(Styles.toolDetail.render("  (ctrl+o to expand)"));
                        sb.append("\n");
                    }
                }
                case "sub_agent" -> {
                    if (msg.subAgentBlock != null) {
                        sb.append(renderSubAgentBlock(msg.subAgentBlock, msg.expanded));
                    }
                }
                case "system" -> {
                    sb.append(Styles.system.render("  " + msg.content));
                    sb.append("\n");
                }
                case "error" -> {
                    sb.append(Styles.error.render("  ✖ " + msg.content));
                    sb.append("\n");
                }
                default -> {
                    sb.append("  " + msg.content);
                    sb.append("\n");
                }
            }
        }


        for (var tb : toolBlocks) {
            if ("Agent".equals(tb.toolName()) && activeSubAgent != null) {
                continue;
            }
            sb.append(renderToolBlock(tb));
            sb.append("\n");
        }


        if (activeSubAgent != null && !activeSubAgent.done) {
            sb.append(renderSubAgentBlock(activeSubAgent, false));
        }


        if (streaming && !streamBuf.isEmpty()) {
            sb.append(Styles.aiMarker.render("● "));

            String clipped = clipToPhysicalLines(streamBuf.toString(), Math.max(5, height - 12), width);
            sb.append(Styles.streamingText.render(clipped));
            sb.append("\n");
        }


        if (streaming) {
            double elapsed = (System.currentTimeMillis() - thinkingStartMs) / 1000.0;
            String frame = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length];
            sb.append(Styles.thinking.render("  %s %s…  (%.0fs)".formatted(frame, thinkingVerb, elapsed)));
            sb.append("\n");

            sb.append(renderTeammateTree());
        }


        if (permDialog) {
            sb.append(Styles.permBorder.render("  %s command".formatted(permToolName)));
            sb.append("\n\n");
            if (permDesc != null && !permDesc.isEmpty() && !permDesc.equals(permToolName)) {
                sb.append(Styles.aiText.render("    " + permDesc));
                sb.append("\n\n");
            }
            sb.append(Styles.permDim.render("  This command requires approval"));
            sb.append("\n\n");
            sb.append(Styles.aiText.render("  Do you want to proceed?"));
            sb.append("\n");
            String[] options = {"Yes", "Yes, and don't ask again for this pattern", "No"};
            for (int i = 0; i < options.length; i++) {
                String marker = i == permCursor ? " ❯ " : "   ";
                var style = i == permCursor ? Styles.selectedItem : Styles.normalItem;
                sb.append(style.render(marker + (i + 1) + ". " + options[i]));
                sb.append("\n");
            }
            sb.append("\n");
        }


        if (rewindDialog && rewindSnapshots != null) {
            sb.append("\n");
            sb.append(Styles.selectedItem.render("  ⟲ Rewind to checkpoint"));
            sb.append("\n\n");

            if (rewindPhase == 0) {
                int maxVisible = 8;
                int start = Math.max(0, rewindCursor - maxVisible + 1);
                int end = Math.min(start + maxVisible, rewindSnapshots.size());
                for (int i = start; i < end; i++) {
                    var snap = rewindSnapshots.get(i);
                    String marker = i == rewindCursor ? " ❯ " : "   ";
                    var style = i == rewindCursor ? Styles.selectedItem : Styles.normalItem;
                    String label = snap.userText();
                    if (label.length() > 50) label = label.substring(0, 50) + "…";
                    long secs = java.time.Duration.between(snap.timestamp(), java.time.Instant.now()).getSeconds();
                    sb.append(style.render("%s[%d] %s (%ds ago, %d file(s))".formatted(
                            marker, i + 1, label, secs, snap.backups().size())));
                    sb.append("\n");
                }
                sb.append("\n");
                sb.append(Styles.normalItem.render("  ↑/↓ navigate · enter select · esc cancel"));
            } else {
                var snap = rewindSnapshots.get(rewindCursor);
                String label = snap.userText();
                if (label.length() > 50) label = label.substring(0, 50) + "…";
                long secs = java.time.Duration.between(snap.timestamp(), java.time.Instant.now()).getSeconds();
                sb.append(Styles.normalItem.render("  Selected: [%d] %s (%ds ago, %d file(s))".formatted(
                        rewindCursor + 1, label, secs, snap.backups().size())));
                sb.append("\n\n");
                for (int i = 0; i < REWIND_OPTIONS.length; i++) {
                    String marker = i == rewindOptionCursor ? " ❯ " : "   ";
                    var style = i == rewindOptionCursor ? Styles.selectedItem : Styles.normalItem;
                    sb.append(style.render(marker + REWIND_OPTIONS[i]));
                    sb.append("\n");
                }
                sb.append("\n");
                sb.append(Styles.normalItem.render("  ↑/↓ navigate · enter select · esc back"));
            }
            sb.append("\n");
        }


        if (askUserDialogState.isActive()) {
            sb.append("\n");
            sb.append(askUserDialogState.render(width));
        }


        if (planApprovalDialog.isActive()) {
            sb.append("\n");
            sb.append(planApprovalDialog.render());
        }


        {
            String contentStr = sb.toString();
            sb.setLength(0);
            String[] contentLines = contentStr.split("\n", -1);
            int lineCount = contentLines.length;
            if (lineCount > 0 && contentLines[lineCount - 1].isEmpty()) lineCount--;
            totalContentLines = lineCount;

            int bottomHeight = 4;
            if (slashMenuOpen && !slashMatches.isEmpty()) bottomHeight += Math.min(slashMatches.size(), 8);
            if (atMenuOpen && !atMatches.isEmpty()) bottomHeight += Math.min(atMatches.size(), 8);
            if (runtimePopupOpen) bottomHeight += runtimePopupOptions.size() + 3;
            if (todoPopupOpen && taskList != null) bottomHeight += taskList.list().size() + 3;
            int viewportHeight = Math.max(height - bottomHeight, 3);

            if (lineCount > viewportHeight && scrollOffset > lineCount - viewportHeight) {
                scrollOffset = lineCount - viewportHeight;
            }

            int endLine = Math.min(lineCount, lineCount - scrollOffset);
            int startLine = Math.max(0, endLine - viewportHeight);

            for (int i = startLine; i < endLine; i++) {
                sb.append(contentLines[i]).append("\n");
            }

            if (scrollOffset > 0) {
                sb.append(Styles.toolDetail.render("  ↓ %d more lines below (pgdn/end)".formatted(scrollOffset)));
                sb.append("\n");
            }
        }


        String sep = "─".repeat(Math.max(width - 2, 20));
        sb.append(Styles.separator.render(sep));
        sb.append("\n");


        if (streaming) {
            // Input disabled during streaming; spinner is in chat content above
        } else {
            if (inputBuffer.isEmpty()) {
                sb.append(Styles.prompt.render("❯ "));
                sb.append(Styles.placeholder.render("Send a message..."));
                sb.append("\n");
            } else {

                String before = inputBuffer.substring(0, inputCursor);
                String after = inputBuffer.substring(inputCursor);
                String withCursor = before + "█" + after;
                String[] inputLines = withCursor.split("\n", -1);
                for (int i = 0; i < inputLines.length; i++) {
                    if (i == 0) {
                        sb.append(Styles.prompt.render("❯ "));
                    } else {
                        sb.append("  ");
                    }
                    sb.append(inputLines[i]);
                    sb.append("\n");
                }
            }
        }
        if (streaming) sb.append("\n");


        sb.append(Styles.separator.render("─".repeat(Math.max(width, 20))));
        sb.append("\n");


        if (slashMenuOpen && !slashMatches.isEmpty()) {
            for (int i = 0; i < slashMatches.size() && i < 8; i++) {
                var cmdItem = slashMatches.get(i);
                String desc = cmdItem.description() != null ? cmdItem.description() : "";
                if (desc.codePointCount(0, desc.length()) > 30) {
                    desc = desc.substring(0, desc.offsetByCodePoints(0, 28)) + "…";
                }
                String label = String.format("  /%-16s — %s", cmdItem.name(), desc);
                var style = i == slashCursor ? Styles.selectedItem : Styles.normalItem;
                sb.append(style.render(label));
                sb.append("\n");
            }
        }


        if (atMenuOpen && !atMatches.isEmpty()) {
            for (int i = 0; i < atMatches.size() && i < 8; i++) {
                String marker = i == atCursor ? " ❯ " : "   ";
                var style = i == atCursor ? Styles.selectedItem : Styles.normalItem;
                sb.append(style.render(marker + "@" + atMatches.get(i)));
                sb.append("\n");
            }
        }

        if (todoPopupOpen) {
            sb.append(renderTodoPopup());
        }

        if (runtimePopupOpen) {
            sb.append(renderRuntimePopup());
        }

        String modeStr;
        var modeStyle = Styles.modeDefault;
        if (permChecker != null) {
            var mode = permChecker.getMode();
            modeStr = switch (mode) {
                case DEFAULT -> "default";
                case ACCEPT_EDITS -> "accept-edits";
                case PLAN -> "plan";
                case BYPASS -> "YOLO";
            };
            modeStyle = switch (mode) {
                case DEFAULT -> Styles.modeDefault;
                case ACCEPT_EDITS -> Styles.modeAcceptEdits;
                case PLAN -> Styles.modePlan;
                case BYPASS -> Styles.modeBypass;
            };
        } else {
            modeStr = "default";
        }

        String left = modeStyle.render("  " + modeStr);
        String inputModeLabel = switch (inputMode) {
            case NORMAL -> "NORMAL";
            case INSERT -> "INSERT";
            case SLASH -> "SLASH";
            case POPUP -> "POPUP";
            case SEARCH -> "SEARCH";
            case CONFIRM -> "CONFIRM";
        };
        left += Styles.statusItem.render("  [" + inputModeLabel + "]");
        if (permChecker != null && permChecker.getMode() != PermissionMode.DEFAULT) {
            left += Styles.toolDetail.render(" (shift+tab)");
        }

        // Active teammates indicator
        long activeTeammates = teamManager.getAllTeammateProgress().stream()
                .filter(p -> "running".equals(p.getStatus()))
                .count();
        String teammateStr = "";
        if (activeTeammates > 0) {
            teammateStr = " · " + activeTeammates + " teammate" + (activeTeammates > 1 ? "s" : "");
            left += Styles.cyan(teammateStr);
        }

        var rightParts = new StringBuilder();
        if (mcpConnecting) {
            rightParts.append(Styles.modePlan.render("MCP connecting… "));
        }
        String modelStr = selectedProvider != null ? selectedProvider.getModel() : "";
        if (!modelStr.isEmpty()) {
            rightParts.append(Styles.statusItem.render(modelStr));
        }
        String runtimeLabel = runtimeStatusLabel();
        if (!runtimeLabel.isEmpty()) {
            rightParts.append(Styles.statusItem.render(" · " + runtimeLabel));
        }

        int leftLen = modeStr.length() + 2 + (permChecker != null
                && permChecker.getMode() != PermissionMode.DEFAULT ? 12 : 0)
                + teammateStr.length();
        int rightLen = (mcpConnecting ? 17 : 0) + modelStr.length() + runtimeLabel.length() + (runtimeLabel.isEmpty() ? 0 : 3);
        int gap = Math.max(width - leftLen - rightLen - 2, 2);
        sb.append(left);
        sb.append(" ".repeat(gap));
        sb.append(rightParts);
        sb.append("\n");


        String result = sb.toString();
        String[] allLines = result.split("\n", -1);
        int total = allLines.length;
        if (total > 0 && allLines[total - 1].isEmpty()) total--;
        int maxLines = program != null ? program.getAvailableHeight() : Math.max(height - 1, 3);
        if (total > maxLines) {
            var capped = new StringBuilder();
            int start = total - maxLines;
            for (int i = start; i < total; i++) {
                capped.append(allLines[i]);
                if (i < total - 1) capped.append("\n");
            }
            capped.append("\n");
            return capped.toString();
        }
        return result;
    }
}
