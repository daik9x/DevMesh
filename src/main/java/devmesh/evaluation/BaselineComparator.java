package devmesh.evaluation;
import java.util.*;
public final class BaselineComparator {
    public record Change(String scenarioId, double baseline, double current, double delta, String classification) {}
    public static List<Change> compare(List<BenchmarkResult> baseline, List<BenchmarkResult> current) {
        var old = new HashMap<String, Double>(); baseline.forEach(r -> old.put(r.scenarioId(), r.overallScore())); var changes = new ArrayList<Change>();
        for (var result : current) { double before = old.getOrDefault(result.scenarioId(), 0d); double delta = result.overallScore() - before; changes.add(new Change(result.scenarioId(), before, result.overallScore(), delta, delta > .0001 ? "improved" : delta < -.0001 ? "regressed" : "unchanged")); }
        return List.copyOf(changes);
    }
}