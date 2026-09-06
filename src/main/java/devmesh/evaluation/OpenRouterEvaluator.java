package devmesh.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import devmesh.config.ProviderConfig;
import devmesh.conversation.ConversationManager;
import devmesh.llm.LlmClient;
import devmesh.llm.StreamEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Optional LLM judge. It is unavailable without OPENROUTER_API_KEY and never falls back to a fake score. */
public final class OpenRouterEvaluator implements Evaluator {
    public static final String DEFAULT_MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmClient client;
    public OpenRouterEvaluator(String model) {
        var config = new ProviderConfig(); config.setName("OpenRouter evaluator"); config.setProtocol("openrouter");
        config.setModel(model == null || model.isBlank() ? DEFAULT_MODEL : model);
        if (config.resolvedApiKey().isBlank()) throw new EvaluationUnavailableException("OpenRouter evaluator unavailable: OPENROUTER_API_KEY is not configured.");
        client = LlmClient.create(config, "Return only valid JSON matching the requested evaluation schema.");
    }
    @Override public JudgeResult evaluate(EvaluationInput input) throws Exception {
        var conversation = new ConversationManager();
        conversation.addUserMessage("Evaluate this coding task objectively. Return JSON with numeric fields correctness, requirementCoverage, codeQuality, safety, explanationQuality from 0 to 1, plus reasoning, criticalIssues, recommendations.\n" + JSON.writeValueAsString(input));
        var events = client.stream(conversation, List.of()); var text = new StringBuilder();
        while (true) { StreamEvent event = events.poll(5, TimeUnit.MINUTES); if (event == null) throw new EvaluationException("evaluator timeout"); if (event instanceof StreamEvent.TextDelta delta) text.append(delta.text()); if (event instanceof StreamEvent.Error error) throw new EvaluationException(error.message()); if (event instanceof StreamEvent.StreamEnd) break; }
        ObjectNode node = (ObjectNode) JSON.readTree(text.toString());
        return new JudgeResult(requiredScore(node, "correctness"), requiredScore(node, "requirementCoverage"), requiredScore(node, "codeQuality"), requiredScore(node, "safety"), requiredScore(node, "explanationQuality"), node.path("reasoning").asText(""), JSON.convertValue(node.path("criticalIssues"), List.class), JSON.convertValue(node.path("recommendations"), List.class));
    }
    private static double requiredScore(ObjectNode node, String name) { double value = node.path(name).asDouble(Double.NaN); if (!Double.isFinite(value) || value < 0 || value > 1) throw new EvaluationException("Invalid evaluator score: " + name); return value; }
    public static class EvaluationUnavailableException extends RuntimeException { public EvaluationUnavailableException(String message) { super(message); } }
    public static class EvaluationException extends RuntimeException { public EvaluationException(String message) { super(message); } }
}