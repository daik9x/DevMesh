package devmesh.evaluation;

import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ScenarioLoader {
    private ScenarioLoader() {}
    public static List<BenchmarkScenario> loadDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Scenario directory not found: " + directory);
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted().flatMap(path -> { try { return load(path).stream(); } catch (IOException e) { throw new ScenarioLoadException(path, e); } }).toList();
        }
    }
    public static List<BenchmarkScenario> load(Path file) throws IOException {
        Object parsed = new Yaml().load(Files.readString(file));
        if (parsed instanceof Map<?, ?> map && map.get("scenarios") instanceof List<?> list) return list.stream().map(ScenarioLoader::convert).toList();
        if (parsed instanceof Map<?, ?> map) return List.of(convert(map));
        throw new IOException("Scenario YAML must contain a mapping or scenarios list: " + file);
    }
    private static BenchmarkScenario convert(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("scenario must be a mapping");
        String id = text(map, "id");
        var validationMap = map.get("validation") instanceof Map<?, ?> m ? m : Map.of();
        var validation = new BenchmarkScenario.Validation(text(validationMap, "command"), strings(validationMap, "expectedOutput"), strings(validationMap, "requiredFiles"), strings(validationMap, "forbiddenFiles"), bool(validationMap, "requireZeroExit", true));
        return new BenchmarkScenario(id, text(map, "name"), text(map, "description"), enumValue(BenchmarkCategory.class, text(map, "category"), BenchmarkCategory.MULTI_STEP_TASK), enumValue(BenchmarkDifficulty.class, text(map, "difficulty"), BenchmarkDifficulty.MEDIUM), text(map, "repositoryFixture"), text(map, "task"), text(map, "expectedBehavior"), validation, number(map, "timeoutSeconds", 300), strings(map, "allowedTools"), strings(map, "forbiddenActions"), objectMap(map.get("metadata")));
    }
    private static String text(Map<?, ?> map, String key) { Object value = map.get(key); return value == null ? "" : value.toString(); }
    private static boolean bool(Map<?, ?> map, String key, boolean fallback) { Object value = map.get(key); return value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private static long number(Map<?, ?> map, String key, long fallback) { try { return Long.parseLong(text(map, key)); } catch (NumberFormatException e) { return fallback; } }
    private static List<String> strings(Map<?, ?> map, String key) { Object value = map.get(key); if (!(value instanceof List<?> list)) return List.of(); return list.stream().map(String::valueOf).toList(); }
    private static Map<String, Object> objectMap(Object value) { if (!(value instanceof Map<?, ?> map)) return Map.of(); var out = new LinkedHashMap<String, Object>(); map.forEach((k, v) -> out.put(String.valueOf(k), v)); return out; }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) { try { return value.isBlank() ? fallback : Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return fallback; } }
    public static final class ScenarioLoadException extends RuntimeException { public ScenarioLoadException(Path path, Throwable cause) { super("Failed to load scenario " + path, cause); } }
}