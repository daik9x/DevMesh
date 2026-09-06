package devmesh.evaluation;

import java.util.List;
import java.util.Map;

public record BenchmarkScenario(String id, String name, String description, BenchmarkCategory category,
                                BenchmarkDifficulty difficulty, String repositoryFixture, String task,
                                String expectedBehavior, Validation validation, long timeoutSeconds,
                                List<String> allowedTools, List<String> forbiddenActions, Map<String, Object> metadata) {
    public record Validation(String command, List<String> expectedOutput, List<String> requiredFiles,
                             List<String> forbiddenFiles, boolean requireZeroExit) {}

    public BenchmarkScenario {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("scenario id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("scenario name is required");
        category = category == null ? BenchmarkCategory.MULTI_STEP_TASK : category;
        difficulty = difficulty == null ? BenchmarkDifficulty.MEDIUM : difficulty;
        repositoryFixture = repositoryFixture == null ? "." : repositoryFixture;
        task = task == null ? "" : task;
        expectedBehavior = expectedBehavior == null ? "" : expectedBehavior;
        validation = validation == null ? new Validation(null, List.of(), List.of(), List.of(), true) : validation;
        timeoutSeconds = timeoutSeconds <= 0 ? 300 : timeoutSeconds;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        forbiddenActions = forbiddenActions == null ? List.of() : List.copyOf(forbiddenActions);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}