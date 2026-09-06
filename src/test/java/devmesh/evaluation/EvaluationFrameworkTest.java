package devmesh.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationFrameworkTest {
    @Test
    void loadsScenarioAndRunsValidationInAnIsolatedCopy() throws Exception {
        var root = Files.createTempDirectory("evaluation-fixture");
        var fixture = root.resolve("fixture"); Files.createDirectories(fixture); Files.writeString(fixture.resolve("README.md"), "fixture");
        var scenario = new BenchmarkScenario("isolated", "isolated", "", BenchmarkCategory.SECURITY, BenchmarkDifficulty.EASY,
                "fixture", "inspect", "README exists", new BenchmarkScenario.Validation(null, List.of(), List.of("README.md"), List.of("created.txt"), true), 10, List.of(), List.of(), Map.of());
        var result = new BenchmarkRunner().run(scenario, root);
        assertEquals(BenchmarkStatus.PASS, result.status());
        assertEquals(1.0, result.overallScore(), 0.0001);
        assertFalse(Files.exists(fixture.resolve("created.txt")));
    }

    @Test
    void scoringWeightsAreTransparentAndBounded() {
        var metrics = BenchmarkMetrics.empty(true, 1);
        var dimensions = ScoreCalculator.dimensions(metrics);
        assertEquals(1.0, dimensions.values().stream().mapToDouble(ScoreDimension::weight).sum(), 0.0001);
        assertEquals(1.0, ScoreCalculator.overall(metrics), 0.0001);
    }

    @Test
    void mockEvaluatorNeedsNoNetworkOrApiKey() throws Exception {
        var result = new MockEvaluator().evaluate(new EvaluationInput("task", "expected", "", "", "pass", "pass", "", "", "", "done", Map.of()));
        assertEquals(1.0, result.correctness());
        assertTrue(result.criticalIssues().isEmpty());
    }

    @Test
    void statisticsAndSecretScannerAreDeterministicAndMasked() throws Exception {
        var root = Files.createTempDirectory("evaluation-secrets");
        Files.writeString(root.resolve("config.txt"), "OPENROUTER_API_KEY=sk-or-v1-thisisatestsecretvalue");
        assertEquals(1, new SecretScanner().scan(root).size());
        var metrics = BenchmarkMetrics.empty(true, 1);
        var result = new BenchmarkResult("one", BenchmarkStatus.PASS, "", metrics, 1, ScoreCalculator.dimensions(metrics));
        assertEquals(1.0, ((Number) BenchmarkStatistics.scores(List.of(result)).get("mean")).doubleValue());
    }
}