package devmesh.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

public record BenchmarkMetrics(boolean taskSuccess, boolean buildSuccess, boolean testsSuccess,
                               int buildAttempts, int buildFailures, int testsPassed, int testsFailed,
                               int repairAttempts, int repairFailures, int toolCalls, int unnecessaryToolCalls,
                               int toolFailures, int contextTokens, int contextCompactions, int checkpointCreated,
                               int checkpointRestored, int duplicateActionsAvoided, int forbiddenActionAttempts,
                               int permissionViolations, int unsafeRecoveryAttempts, long executionTimeMillis) {
    public Map<String, Object> toMap() { var out = new LinkedHashMap<String, Object>(); out.put("taskSuccess", taskSuccess); out.put("buildSuccess", buildSuccess); out.put("testsSuccess", testsSuccess); out.put("buildAttempts", buildAttempts); out.put("buildFailures", buildFailures); out.put("testsPassed", testsPassed); out.put("testsFailed", testsFailed); out.put("repairAttempts", repairAttempts); out.put("repairFailures", repairFailures); out.put("toolCalls", toolCalls); out.put("unnecessaryToolCalls", unnecessaryToolCalls); out.put("toolFailures", toolFailures); out.put("contextTokens", contextTokens); out.put("contextCompactions", contextCompactions); out.put("checkpointCreated", checkpointCreated); out.put("checkpointRestored", checkpointRestored); out.put("duplicateActionsAvoided", duplicateActionsAvoided); out.put("forbiddenActionAttempts", forbiddenActionAttempts); out.put("permissionViolations", permissionViolations); out.put("unsafeRecoveryAttempts", unsafeRecoveryAttempts); out.put("executionTimeMillis", executionTimeMillis); return out; }
    public static BenchmarkMetrics empty(boolean success, long duration) { return new BenchmarkMetrics(success, success, success, 0, 0, success ? 1 : 0, success ? 0 : 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, duration); }
}