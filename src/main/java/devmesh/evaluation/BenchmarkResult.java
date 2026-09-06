package devmesh.evaluation;
import java.util.Map;
public record BenchmarkResult(String scenarioId, BenchmarkStatus status, String reason, BenchmarkMetrics metrics, double overallScore, Map<String, ScoreDimension> dimensions) {
    public Map<String, Object> toMap() { var out = new java.util.LinkedHashMap<String, Object>(); out.put("scenarioId", scenarioId); out.put("status", status.name()); out.put("reason", reason); out.put("metrics", metrics.toMap()); out.put("overallScore", overallScore); var scores = new java.util.LinkedHashMap<String, Object>(); dimensions.forEach((k, v) -> scores.put(k, Map.of("rawScore", v.rawScore(), "weight", v.weight(), "weightedScore", v.weightedScore()))); out.put("dimensions", scores); return out; }
}