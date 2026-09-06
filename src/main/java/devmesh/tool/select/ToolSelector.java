package devmesh.tool.select;

import devmesh.platform.OperatingSystem;
import devmesh.repository.AnalysisDepth;
import devmesh.repository.RepositoryIntelligence;
import devmesh.tool.Tool;
import devmesh.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic, explainable candidate selection over the existing ToolRegistry. */
public final class ToolSelector {
    private final ToolRegistry registry;
    private final Map<String, Integer> failures = new HashMap<>();
    private final RepositoryIntelligence repository;

    public ToolSelector(ToolRegistry registry, Path repositoryRoot) {
        this.registry = registry;
        this.repository = repositoryRoot == null ? null : new RepositoryIntelligence(repositoryRoot);
    }

    public void recordFailure(String toolName) {
        if (toolName != null) failures.merge(toolName, 1, Integer::sum);
    }

    public List<ToolSelection> candidates(ToolSelectionRequest request) {
        var selections = new ArrayList<ToolSelection>();
        for (Tool tool : registry.listTools()) {
            ToolCapability capability = ToolCapability.from(tool);
            if (!capability.intents().contains(request.intent())) continue;
            if (!capability.supportedPlatforms().contains(request.platform())) continue;
            if (request.alreadyKnownKeys().contains(tool.name())) continue;
            if (request.permissions() != null
                    && request.permissions().check(tool, request.arguments()).decision()
                    == devmesh.permission.PermissionMode.Decision.DENY) continue;
            int score = score(capability, request);
            String reason = explain(capability, request, score);
            selections.add(new ToolSelection(tool, capability, score, reason));
        }
        selections.sort(java.util.Comparator.comparingInt(ToolSelection::score).reversed()
                .thenComparing(selection -> selection.tool().name()));
        return List.copyOf(selections);
    }

    public ToolSelection select(ToolSelectionRequest request) {
        return candidates(request).stream().findFirst().orElse(null);
    }

    private int score(ToolCapability capability, ToolSelectionRequest request) {
        int score = 100;
        if (capability.readOnly()) score += 15;
        if (capability.destructive()) score -= 35;
        score += capability.reliability() / 5;
        score -= capability.estimatedCost() * 2;
        score -= failures.getOrDefault(capability.name(), 0) * 12;
        score += switch (capability.implementationType()) {
            case NATIVE -> 18;
            case LIBRARY -> 12;
            case EXECUTABLE -> 4;
            case MCP -> 0;
            case SHELL -> -12;
        };
        if (capability.structuredOutput()) score += 6;
        if (capability.requiresShell()) score -= 8;
        if (request.intent() == ToolIntent.SEARCH_SYMBOL && repository != null
                && !repository.search(String.valueOf(request.arguments().getOrDefault("query", "")), AnalysisDepth.STANDARD).isEmpty()
                && "Grep".equals(capability.name())) score -= 20;
        return score;
    }

    private static String explain(ToolCapability capability, ToolSelectionRequest request, int score) {
        return "intent=" + request.intent() + ", capability=" + capability.name()
                + ", risk=" + capability.risk() + ", score=" + score;
    }
}