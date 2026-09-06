package devmesh.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Offline benchmark command. It never loads normal DevMesh configuration or starts an Agent. */
public final class BenchmarkCli {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private BenchmarkCli() {}
    public static void main(String[] args) {
        Integer exit = tryRun(args == null || args.length == 0 ? new String[]{"benchmark"} : args);
        if (exit != null && exit != 0) System.exit(exit);
    }
    public static Integer tryRun(String[] args) {
        if (args.length == 0 || !("benchmark".equals(args[0]) || "--benchmark".equals(args[0]))) return null;
        try {
            var options = options(args); Path workspace = Path.of(options.getOrDefault("workspace", System.getProperty("user.dir"))).toAbsolutePath().normalize();
            Path scenarioDir = workspace.resolve(options.getOrDefault("scenarios", "benchmarks/scenarios")); Path fixtureRoot = workspace.resolve(options.getOrDefault("fixtures", "benchmarks/fixtures"));
            List<BenchmarkScenario> scenarios = ScenarioLoader.loadDirectory(scenarioDir); String selected = options.get("scenario"); if (selected != null) scenarios = scenarios.stream().filter(s -> selected.equals(s.id())).toList();
            int runs = Integer.parseInt(options.getOrDefault("runs", "1")); if (runs < 1) throw new IllegalArgumentException("--runs must be positive");
            var runner = new BenchmarkRunner(); var results = new ArrayList<BenchmarkResult>();
            for (int run = 0; run < runs; run++) for (var scenario : scenarios) results.add(runner.run(scenario, fixtureRoot));
            var report = new LinkedHashMap<String, Object>(); report.put("benchmark", "devmesh-agent-evaluation"); report.put("benchmarkVersion", 1); report.put("timestamp", Instant.now()); report.put("javaVersion", System.getProperty("java.version")); report.put("os", System.getProperty("os.name")); report.put("model", OpenRouterEvaluator.DEFAULT_MODEL); report.put("openrouterEvaluator", System.getenv("OPENROUTER_API_KEY") == null || System.getenv("OPENROUTER_API_KEY").isBlank() ? "EVALUATION_UNAVAILABLE" : "CONFIGURED"); report.put("runs", runs); report.put("statistics", BenchmarkStatistics.scores(results)); report.put("scenarios", results.stream().map(BenchmarkResult::toMap).toList()); report.put("overallScore", results.stream().mapToDouble(BenchmarkResult::overallScore).average().orElse(0)); report.put("securityWarnings", new SecretScanner().scan(workspace).stream().map(f -> Map.of("path", f.path(), "message", f.message())).toList());
            Path reportPath = workspace.resolve(options.getOrDefault("report", "build/benchmark/benchmark.json")); Files.createDirectories(reportPath.getParent()); JSON.writeValue(reportPath.toFile(), report); Path markdown = replaceExtension(reportPath, ".md"); Files.writeString(markdown, markdown(report));
            if ("json".equals(options.get("output-format"))) System.out.println(JSON.writeValueAsString(report)); else System.out.printf("Benchmark: %d scenarios, overall score %.2f%nReport: %s%n", results.size(), ((Number) report.get("overallScore")).doubleValue(), reportPath);
            return results.stream().anyMatch(r -> r.status() == BenchmarkStatus.ERROR || r.status() == BenchmarkStatus.TIMEOUT) ? 2 : 0;
        } catch (Exception e) { System.err.println("Benchmark failed: " + e.getMessage()); return 2; }
    }
    private static Map<String, String> options(String[] args) { var out = new LinkedHashMap<String, String>(); for (int i = 1; i < args.length; i++) { String arg = args[i]; if (!arg.startsWith("--")) continue; int eq = arg.indexOf('='); if (eq > 0) out.put(arg.substring(2, eq), arg.substring(eq + 1)); else if (i + 1 < args.length && !args[i + 1].startsWith("--")) out.put(arg.substring(2), args[++i]); else out.put(arg.substring(2), "true"); } return out; }
    private static Path replaceExtension(Path path, String extension) { String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); return path.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + extension); }
    private static String markdown(Map<String, Object> report) { var sb = new StringBuilder("# Agent Evaluation Benchmark\n\n"); sb.append("Overall score: ").append(report.get("overallScore")).append("\n\nEvaluator: ").append(report.get("openrouterEvaluator")).append("\n\n| Scenario | Status | Score |\n|---|---|---:|\n"); for (Object raw : (List<?>) report.get("scenarios")) { var row = (Map<?, ?>) raw; sb.append('|').append(row.get("scenarioId")).append('|').append(row.get("status")).append('|').append(row.get("overallScore")).append('|').append('\n'); } return sb.toString(); }
}