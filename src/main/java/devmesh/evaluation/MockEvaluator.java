package devmesh.evaluation;
import java.util.List;
public final class MockEvaluator implements Evaluator {
    private final JudgeResult result;
    public MockEvaluator() { this(new JudgeResult(1, 1, 1, 1, 1, "deterministic mock", List.of(), List.of())); }
    public MockEvaluator(JudgeResult result) { this.result = result; }
    @Override public JudgeResult evaluate(EvaluationInput input) { return result; }
}