package devmesh.evaluation;
import java.util.List;
public record JudgeResult(double correctness, double requirementCoverage, double codeQuality, double safety,
                          double explanationQuality, String reasoning, List<String> criticalIssues,
                          List<String> recommendations) {}