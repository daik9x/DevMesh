package devmesh.evaluation;

import java.util.*;
public final class BenchmarkStatistics {
    private BenchmarkStatistics() {}
    public static Map<String, Object> scores(List<BenchmarkResult> results) {
        var values = results.stream().mapToDouble(BenchmarkResult::overallScore).sorted().toArray();
        if (values.length == 0) return Map.of("runs", 0);
        double mean = Arrays.stream(values).average().orElse(0); double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        var out = new LinkedHashMap<String, Object>(); out.put("runs", values.length); out.put("mean", mean); out.put("median", values.length % 2 == 0 ? (values[values.length / 2 - 1] + values[values.length / 2]) / 2 : values[values.length / 2]); out.put("minimum", values[0]); out.put("maximum", values[values.length - 1]); out.put("standardDeviation", Math.sqrt(variance)); out.put("successRate", results.stream().filter(r -> r.status() == BenchmarkStatus.PASS).count() / (double) results.size()); return out;
    }
}