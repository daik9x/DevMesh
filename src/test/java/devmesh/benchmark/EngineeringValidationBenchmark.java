package devmesh.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import devmesh.compact.ContextCompactor;
import devmesh.compact.RecoveryState;
import devmesh.config.ProviderConfig;
import devmesh.conversation.ConversationManager;
import devmesh.conversation.Message;
import devmesh.conversation.ToolResultBlock;
import devmesh.conversation.ToolUseBlock;
import devmesh.llm.LlmClient;
import devmesh.llm.StreamEvent;
import devmesh.observability.ToolSchemaMetrics;
import devmesh.subagent.AgentTool;
import devmesh.subagent.SubAgentTaskManager;
import devmesh.task.TaskList;
import devmesh.task.TaskTools;
import devmesh.teams.TeamManager;
import devmesh.teams.TeamTools;
import devmesh.tool.ToolRegistry;
import devmesh.tool.impl.AskUserTool;
import devmesh.tool.impl.ExitPlanModeTool;
import devmesh.tool.impl.InstallSkillTool;
import devmesh.tool.impl.ToolSearchTool;
import devmesh.worktree.WorktreeManager;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Reproducible, offline engineering-validation benchmark used by README and resume claims. */
public final class EngineeringValidationBenchmark {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final List<String> DISCOVERY_SCENARIO = List.of(
            "AskUserQuestion", "EnterWorktree", "ExitWorktree", "ProposeSkillCandidate", "TaskCreate", "TaskUpdate");

    private static final String USER_SEED =
            "Inspect the current Java module, identify the relevant classes, preserve public behavior, and list a verifiable next step. ";
    private static final String ASSISTANT_SEED =
            "I traced the call path, checked the safety boundary, compared the implementation with its tests, and recorded concrete evidence. ";
    private static final String TOOL_RESULT_SEED =
            "public final class Sample { static String verify(String input) { return input == null ? \"\" : input.strip(); } }\n";

    private EngineeringValidationBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected: <fixture.yaml> <result.json> <report.md>");
        }
        Path fixturePath = Path.of(args[0]).toAbsolutePath().normalize();
        Path resultPath = Path.of(args[1]).toAbsolutePath().normalize();
        Path reportPath = Path.of(args[2]).toAbsolutePath().normalize();
        BenchmarkConfig config = loadConfig(fixturePath);

        Path workDir = Path.of(System.getProperty("user.dir"), "build", "engineering-validation", "workdir")
                .toAbsolutePath().normalize();
        Files.createDirectories(workDir);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("benchmark_version", 1);
        root.put("fixture", fixturePath.getFileName().toString());
        root.put("fixture_sha256", sha256(Files.readAllBytes(fixturePath)));

        List<Map<String, Object>> schemaResults = new ArrayList<>();
        for (String protocol : List.of("anthropic", "openai-compat")) {
            schemaResults.add(runSchemaBenchmark(protocol, workDir.resolve(protocol)));
        }
        root.put("tool_schema_benchmark", schemaResults);

        @SuppressWarnings("unchecked")
        Map<String, Object> openAiCompat = schemaResults.stream()
                .filter(row -> "openai-compat".equals(row.get("protocol")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> resident = (Map<String, Object>) openAiCompat.get("resident");
        int residentSchemaTokens = ((Number) resident.get("estimated_tokens")).intValue();

        Map<String, Object> contextResult = runContextBenchmark(config, residentSchemaTokens);
        root.put("context_compaction_benchmark", contextResult);

        Files.createDirectories(resultPath.getParent());
        Files.createDirectories(reportPath.getParent());
        JSON.writeValue(resultPath.toFile(), root);
        Files.writeString(resultPath, Files.readString(resultPath) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(reportPath, renderMarkdown(schemaResults, contextResult, config), StandardCharsets.UTF_8);

        System.out.println(JSON.writeValueAsString(root));
        System.out.println("Wrote " + resultPath);
        System.out.println("Wrote " + reportPath);
    }

    private static Map<String, Object> runSchemaBenchmark(String protocol, Path workDir) throws Exception {
        ToolRegistry residentRegistry = buildInteractiveRegistry(protocol, workDir.resolve("resident"));
        ToolRegistry discoveredRegistry = buildInteractiveRegistry(protocol, workDir.resolve("discovered"));
        ToolRegistry eagerRegistry = buildInteractiveRegistry(protocol, workDir.resolve("eager"));

        List<String> deferredNames = eagerRegistry.getDeferredToolNames().stream().sorted().toList();
        System.out.println(protocol + " registered tools: "
                + eagerRegistry.listTools().stream().map(tool -> tool.name()).sorted().toList());
        System.out.println(protocol + " deferred tools: " + deferredNames);
        ensure(eagerRegistry.listTools().size() == 27, "Expected 27 interactive built-in tools");
        ensure(deferredNames.size() == 14, "Expected 14 deferred built-in tools");
        ensure(deferredNames.containsAll(DISCOVERY_SCENARIO), "Discovery scenario must contain deferred tools only");

        discover(discoveredRegistry, DISCOVERY_SCENARIO);
        discover(eagerRegistry, deferredNames);

        var resident = ToolSchemaMetrics.measure(residentRegistry.getAllSchemas(protocol));
        var discovered = ToolSchemaMetrics.measure(discoveredRegistry.getAllSchemas(protocol));
        var eager = ToolSchemaMetrics.measure(eagerRegistry.getAllSchemas(protocol));

        ensure(resident.schemaCount() == 13, "Expected 13 resident schemas");
        ensure(discovered.schemaCount() == 19, "Expected 19 schemas after discovering six deferred tools");
        ensure(eager.schemaCount() == 27, "Expected all 27 schemas in eager baseline");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("protocol", protocol);
        row.put("registered_tools", eagerRegistry.listTools().size());
        row.put("resident_tools", resident.schemaCount());
        row.put("deferred_tools", deferredNames.size());
        row.put("deferred_tool_names", deferredNames);
        row.put("discovery_query", "select:" + String.join(",", DISCOVERY_SCENARIO));
        row.put("discovered_tools", DISCOVERY_SCENARIO);
        row.put("token_estimator", "ceil(canonical_compact_json_characters / 4)");
        row.put("eager_baseline", measurementMap(eager));
        row.put("resident", measurementWithReduction(resident, eager));
        row.put("after_discovering_six", measurementWithReduction(discovered, eager));
        return row;
    }

    private static ToolRegistry buildInteractiveRegistry(String protocol, Path workDir) throws Exception {
        Files.createDirectories(workDir);
        var registry = ToolRegistry.createDefault();
        registry.register(new ToolSearchTool(registry, protocol));
        registry.register(new ExitPlanModeTool());
        registry.register(new AskUserTool());

        var cfg = new ProviderConfig();
        cfg.setProtocol(protocol);
        cfg.setBaseUrl("http://127.0.0.1:1");
        cfg.setApiKey("test-api-key");
        cfg.setModel("benchmark-model");
        cfg.setContextWindow(32_768);
        cfg.setMaxOutputTokens(4_096);

        var agentTool = new AgentTool(new NoopClient(), registry, protocol, cfg);
        agentTool.setTaskManager(new SubAgentTaskManager());
        var worktreeManager = new WorktreeManager(workDir.toString(), List.of(), 720);
        agentTool.setWorktreeManager(worktreeManager);
        var teamManager = new TeamManager();
        agentTool.setTeamManager(teamManager);
        registry.register(agentTool);

        registry.register(new devmesh.tool.impl.EnterWorktreeTool(worktreeManager, "benchmark-session"));
        registry.register(new devmesh.tool.impl.ExitWorktreeTool(worktreeManager));

        var taskList = new TaskList("benchmark", workDir.toString());
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));

        registry.register(new TeamTools.TeamCreateTool(teamManager));
        registry.register(new TeamTools.TeamDeleteTool(teamManager));
        registry.register(new TeamTools.SendMessageTool(teamManager, "lead"));
        registry.register(new InstallSkillTool());
        return registry;
    }

    private static void discover(ToolRegistry registry, List<String> names) {
        var search = registry.get("ToolSearch");
        var result = search.execute(Map.of("query", "select:" + String.join(",", names)));
        ensure(!result.isError(), "ToolSearch discovery failed: " + result.output());
    }

    private static Map<String, Object> runContextBenchmark(BenchmarkConfig cfg,
                                                            int residentSchemaTokens) throws Exception {
        List<TurnInput> turns = generateTurns(cfg);
        String inputJson = JSON.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(turns);

        var baseline = new ConversationManager();
        var compacted = new ConversationManager();
        var summaryClient = new DeterministicSummaryClient(cfg.summaryText());
        var tracking = new ContextCompactor.AutoCompactTrackingState();
        var recovery = new RecoveryState();

        List<Integer> baselineSeries = new ArrayList<>();
        List<Integer> compactedSeries = new ArrayList<>();
        int compactions = 0;
        int integrityFailures = 0;

        for (TurnInput turn : turns) {
            appendTurn(baseline, turn);
            appendTurn(compacted, turn);

            String compactResult = ContextCompactor.manage(
                    compacted, summaryClient, cfg.contextWindowTokens(), cfg.maxOutputTokens(),
                    null, tracking, recovery, List.of());
            if (compactResult != null && !compactResult.isBlank()) compactions++;

            if (!toolPairsBalanced(compacted.getMessages())) integrityFailures++;
            baselineSeries.add(ContextCompactor.estimateTokens(baseline.getMessages()) + residentSchemaTokens);
            compactedSeries.add(ContextCompactor.estimateTokens(compacted.getMessages()) + residentSchemaTokens);
        }

        ensure(turns.size() == 50, "Expected exactly 50 generated turns");
        ensure(compactions > 0, "Fixture did not trigger auto compaction");
        ensure(integrityFailures == 0, "Compaction orphaned a tool_result");

        double baselineAverage = average(baselineSeries);
        double compactedAverage = average(compactedSeries);
        int baselinePeak = baselineSeries.stream().mapToInt(Integer::intValue).max().orElseThrow();
        int compactedPeak = compactedSeries.stream().mapToInt(Integer::intValue).max().orElseThrow();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rounds", cfg.rounds());
        result.put("generated_messages", baseline.size());
        result.put("generated_tool_pairs", turns.stream().filter(TurnInput::hasTool).count());
        result.put("generated_input_sha256", sha256(inputJson.getBytes(StandardCharsets.UTF_8)));
        result.put("context_window_tokens", cfg.contextWindowTokens());
        result.put("max_output_tokens", cfg.maxOutputTokens());
        result.put("resident_schema_estimated_tokens", residentSchemaTokens);
        result.put("metric", "post-management estimated request tokens = message estimate + fixed resident schema estimate");
        result.put("message_token_estimator", "ContextCompactor.estimateTokens (characters/3.5 plus message and tool overhead)");
        result.put("baseline", "same 50 generated turns with ContextCompactor.manage disabled");
        result.put("treatment", "same 50 generated turns with ContextCompactor.manage enabled and deterministic summary client");
        result.put("baseline_average_estimated_tokens", round2(baselineAverage));
        result.put("compacted_average_estimated_tokens", round2(compactedAverage));
        result.put("average_reduction_percent", round2(reduction(baselineAverage, compactedAverage)));
        result.put("baseline_peak_estimated_tokens", baselinePeak);
        result.put("compacted_peak_estimated_tokens", compactedPeak);
        result.put("peak_reduction_percent", round2(reduction(baselinePeak, compactedPeak)));
        result.put("baseline_final_estimated_tokens", baselineSeries.getLast());
        result.put("compacted_final_estimated_tokens", compactedSeries.getLast());
        result.put("compaction_count", compactions);
        result.put("tool_pair_integrity_checks", cfg.rounds());
        result.put("tool_pair_integrity_failures", integrityFailures);
        result.put("baseline_series", baselineSeries);
        result.put("compacted_series", compactedSeries);
        return result;
    }

    private static List<TurnInput> generateTurns(BenchmarkConfig cfg) {
        List<TurnInput> turns = new ArrayList<>();
        for (int round = 1; round <= cfg.rounds(); round++) {
            String user = sized("Round %02d user request. ".formatted(round), USER_SEED,
                    cfg.userMessageCharacters());
            String assistant = sized("Round %02d analysis. ".formatted(round), ASSISTANT_SEED,
                    cfg.assistantMessageCharacters());
            if (round % cfg.toolEveryNRounds() == 0) {
                String toolId = "read-round-" + round;
                String result = sized("Round %02d ReadFile result.\n".formatted(round), TOOL_RESULT_SEED,
                        cfg.toolResultCharacters());
                String followup = sized("Round %02d verified follow-up. ".formatted(round), ASSISTANT_SEED,
                        cfg.toolFollowupCharacters());
                turns.add(new TurnInput(round, user, assistant, toolId, result, followup));
            } else {
                turns.add(new TurnInput(round, user, assistant, null, null, null));
            }
        }
        return turns;
    }

    private static void appendTurn(ConversationManager conv, TurnInput turn) {
        conv.addUserMessage(turn.user());
        if (!turn.hasTool()) {
            conv.addAssistantMessage(turn.assistant());
            return;
        }
        conv.addAssistantFull(turn.assistant(), null, List.of(new ToolUseBlock(
                turn.toolId(), "ReadFile", Map.of(
                        "path", "src/main/java/devmesh/agent/Agent.java",
                        "round", turn.round()))));
        conv.addToolResultsMessage(List.of(new ToolResultBlock(turn.toolId(), turn.toolResult(), false)));
        conv.addAssistantMessage(turn.followup());
    }

    private static boolean toolPairsBalanced(List<Message> messages) {
        Map<String, Integer> uses = new LinkedHashMap<>();
        Map<String, Integer> results = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message.getToolUses() != null) {
                for (ToolUseBlock use : message.getToolUses()) {
                    uses.merge(use.toolUseId(), 1, Integer::sum);
                }
            }
            if (message.getToolResults() != null) {
                for (ToolResultBlock result : message.getToolResults()) {
                    results.merge(result.toolUseId(), 1, Integer::sum);
                }
            }
        }
        return uses.equals(results) && uses.values().stream().allMatch(count -> count == 1);
    }

    private static Map<String, Object> measurementMap(ToolSchemaMetrics.Measurement measurement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_count", measurement.schemaCount());
        result.put("canonical_json_characters", measurement.jsonCharacters());
        result.put("estimated_tokens", measurement.estimatedTokens());
        return result;
    }

    private static Map<String, Object> measurementWithReduction(ToolSchemaMetrics.Measurement value,
                                                                 ToolSchemaMetrics.Measurement baseline) {
        Map<String, Object> result = measurementMap(value);
        result.put("estimated_token_reduction_vs_eager_percent",
                round2(reduction(baseline.estimatedTokens(), value.estimatedTokens())));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static BenchmarkConfig loadConfig(Path path) throws Exception {
        Map<String, Object> map = new Yaml().load(Files.readString(path));
        return new BenchmarkConfig(
                intValue(map, "rounds"),
                intValue(map, "context_window_tokens"),
                intValue(map, "max_output_tokens"),
                intValue(map, "user_message_characters"),
                intValue(map, "assistant_message_characters"),
                intValue(map, "tool_every_n_rounds"),
                intValue(map, "tool_result_characters"),
                intValue(map, "tool_followup_characters"),
                String.valueOf(map.get("summary_text")));
    }

    private static int intValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Missing number: " + key);
        return number.intValue();
    }

    private static String renderMarkdown(List<Map<String, Object>> schemaResults,
                                         Map<String, Object> context,
                                         BenchmarkConfig cfg) {
        StringBuilder md = new StringBuilder();
        md.append("# DevMesh Engineering Validation Benchmark\n\n");
        md.append("This report is generated offline by `./gradlew engineeringValidation` using real Tool Schemas, real `ToolSearch` registration state, and the real `ContextCompactor.manage`; it does not call an external model.\n\n");
        md.append("## 1. On-demand Tool Schema discovery\n\n");
        md.append("Schema tokens are an **estimate**: tools are sorted by name and serialized as canonical compact JSON, then `ceil(JSON character count / 4)` is calculated. This is the same implementation used by the Agent trace and is not claimed to match any model's private tokenizer.\n\n");
        md.append("The baseline discovers all 21 built-in tools in interactive mode and injects all schemas every turn. The fixed discovery input is `ToolSearch(select:AskUserQuestion,EnterWorktree,ExitWorktree,ProposeSkillCandidate,TaskCreate,TaskUpdate)`.\n\n");
        md.append("| Protocol | Scenario | Schema count | JSON characters | Estimated tokens | Reduction vs. full |\n");
        md.append("| --- | --- | ---: | ---: | ---: | ---: |\n");
        for (Map<String, Object> row : schemaResults) {
            String protocol = String.valueOf(row.get("protocol"));
            appendSchemaRow(md, protocol, "Full baseline", castMap(row.get("eager_baseline")), null);
            appendSchemaRow(md, protocol, "Cold-start resident", castMap(row.get("resident")), "estimated_token_reduction_vs_eager_percent");
            appendSchemaRow(md, protocol, "After discovering six deferred tools", castMap(row.get("after_discovering_six")), "estimated_token_reduction_vs_eager_percent");
        }

        md.append("\n## 2. 50-turn context compaction\n\n");
        md.append("The input is fixed by `benchmarks/fixtures/context-50-turns.yaml`: 50 turns, each with a 700-character user message and a 1,200-character assistant message; every five turns add one `ReadFile` tool_use, a 5,000-character tool_result, and a 600-character follow-up, for 120 messages and 10 tool-call groups. The window is ")
            .append(cfg.contextWindowTokens()).append(" tokens with ")
                .append(cfg.maxOutputTokens()).append(" tokens reserved for output.\n\n");
        md.append("The baseline disables `ContextCompactor.manage` for the same input; the treatment calls the real `manage` before each model request. To remove external API variability, the summary client returns fixed text. This benchmark validates triggering, reconstruction, and tool-pair integrity, not summary quality. The metric is `estimated message tokens + estimated fixed resident Schema tokens` after compaction management on each turn.\n\n");
        md.append("| Metric | No-compaction baseline | Automatic compaction | Reduction |\n");
        md.append("| --- | ---: | ---: | ---: |\n");
        md.append("| 50-turn average estimated context | ").append(context.get("baseline_average_estimated_tokens"))
                .append(" | ").append(context.get("compacted_average_estimated_tokens"))
                .append(" | ").append(context.get("average_reduction_percent")).append("% |\n");
        md.append("| 50-turn peak estimated context | ").append(context.get("baseline_peak_estimated_tokens"))
                .append(" | ").append(context.get("compacted_peak_estimated_tokens"))
                .append(" | ").append(context.get("peak_reduction_percent")).append("% |\n");
        md.append("| Final value at turn 50 | ").append(context.get("baseline_final_estimated_tokens"))
                .append(" | ").append(context.get("compacted_final_estimated_tokens")).append(" | — |\n\n");
        md.append("Automatic compaction triggered ").append(context.get("compaction_count"))
            .append(" times; tool_use/tool_result pairing was checked on all ")
            .append(context.get("tool_pair_integrity_checks")).append(" turns with ")
            .append(context.get("tool_pair_integrity_failures")).append(" failures.\n\n");
        md.append("See `benchmarks/results/engineering-validation.json` for the raw per-turn sequence and input SHA-256. These figures apply only to the fixed input and estimator above and should not be generalized to all models, tasks, or context windows.\n");
        return md.toString();
    }

    private static void appendSchemaRow(StringBuilder md, String protocol, String scenario,
                                        Map<String, Object> values, String reductionKey) {
        md.append("| ").append(protocol).append(" | ").append(scenario).append(" | ")
                .append(values.get("schema_count")).append(" | ")
                .append(values.get("canonical_json_characters")).append(" | ")
                .append(values.get("estimated_tokens")).append(" | ");
        if (reductionKey == null) md.append("—");
        else md.append(values.get(reductionKey)).append("%");
        md.append(" |\n");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String sized(String prefix, String seed, int targetCharacters) {
        if (prefix.length() >= targetCharacters) return prefix.substring(0, targetCharacters);
        StringBuilder value = new StringBuilder(prefix);
        while (value.length() < targetCharacters) value.append(seed);
        return value.substring(0, targetCharacters);
    }

    private static double average(List<Integer> values) {
        return values.stream().mapToLong(Integer::longValue).average().orElse(0.0);
    }

    private static double reduction(double baseline, double treatment) {
        return baseline == 0 ? 0.0 : (1.0 - treatment / baseline) * 100.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void ensure(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record BenchmarkConfig(int rounds, int contextWindowTokens, int maxOutputTokens,
                                   int userMessageCharacters, int assistantMessageCharacters,
                                   int toolEveryNRounds, int toolResultCharacters,
                                   int toolFollowupCharacters, String summaryText) {}

    private record TurnInput(int round, String user, String assistant, String toolId,
                             String toolResult, String followup) {
        boolean hasTool() { return toolId != null; }
    }

    private static class NoopClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conversation,
                                                 List<Map<String, Object>> tools) {
            BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            queue.add(new StreamEvent.StreamEnd("end_turn", 0, 0));
            return queue;
        }

        @Override public void setSystemPrompt(String prompt) {}
    }

    private static final class DeterministicSummaryClient extends NoopClient {
        private final String summary;

        private DeterministicSummaryClient(String summary) {
            this.summary = summary;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conversation,
                                                 List<Map<String, Object>> tools) {
            BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            queue.add(new StreamEvent.TextDelta("<summary>" + summary + "</summary>"));
            queue.add(new StreamEvent.StreamEnd("end_turn", 0, 0));
            return queue;
        }
    }
}
