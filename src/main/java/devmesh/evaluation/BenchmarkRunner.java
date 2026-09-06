package devmesh.evaluation;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BenchmarkRunner {
    private final FixtureManager fixtures;
    public BenchmarkRunner() { this(new FixtureManager()); }
    public BenchmarkRunner(FixtureManager fixtures) { this.fixtures = fixtures; }
    public BenchmarkResult run(BenchmarkScenario scenario, Path fixtureRoot) {
        long started = System.nanoTime(); Path work = null;
        try {
            work = fixtures.prepare(scenario, fixtureRoot);
            var validation = scenario.validation(); boolean success = true; String output = "";
            if (validation.command() != null && !validation.command().isBlank()) {
                Process process = new ProcessBuilder(shell(validation.command())).directory(work.toFile()).redirectErrorStream(true).start();
                boolean finished = process.waitFor(scenario.timeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) { process.destroyForcibly(); return result(scenario, BenchmarkStatus.TIMEOUT, "validation timeout", false, started); }
                output = new String(process.getInputStream().readAllBytes()); success = !validation.requireZeroExit() || process.exitValue() == 0;
                success &= validation.expectedOutput().stream().allMatch(output::contains);
            }
            for (String file : validation.requiredFiles()) success &= Files.exists(work.resolve(file));
            for (String file : validation.forbiddenFiles()) success &= !Files.exists(work.resolve(file));
            return result(scenario, success ? BenchmarkStatus.PASS : BenchmarkStatus.FAIL, success ? "validation passed" : "validation failed", success, started);
        } catch (Exception e) { return result(scenario, BenchmarkStatus.ERROR, e.getMessage(), false, started); }
        finally { if (work != null) deleteQuietly(work); }
    }
    private static BenchmarkResult result(BenchmarkScenario scenario, BenchmarkStatus status, String reason, boolean success, long started) { var metrics = BenchmarkMetrics.empty(success, Duration.ofNanos(System.nanoTime() - started).toMillis()); return new BenchmarkResult(scenario.id(), status, reason == null ? "" : reason, metrics, ScoreCalculator.overall(metrics), ScoreCalculator.dimensions(metrics)); }
    private static String[] shell(String command) { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? new String[]{"cmd", "/c", command} : new String[]{"sh", "-c", command}; }
    private static void deleteQuietly(Path path) { try (var paths = Files.walk(path)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }); } catch (IOException ignored) {} }
}