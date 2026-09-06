package devmesh.evaluation;
import java.util.Map;
public record EvaluationInput(String task, String expectedBehavior, String changedFiles, String diff,
                              String buildResult, String testResult, String toolTrace, String todoTrace,
                              String repairTrace, String finalResponse, Map<String, Object> metadata) {}