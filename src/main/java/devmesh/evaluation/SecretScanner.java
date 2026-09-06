package devmesh.evaluation;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Finds credential-shaped material without returning the credential itself. */
public final class SecretScanner {
    private static final Pattern KEY = Pattern.compile("sk-or-v1-[A-Za-z0-9]{20,}");
    private static final Set<String> SKIP = Set.of(".git", "build", ".gradle", "node_modules");
    public List<Finding> scan(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        var findings = new ArrayList<Finding>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (skip(root, path)) continue;
                String content;
                try { content = Files.readString(path); } catch (Exception ignored) { continue; }
                if (KEY.matcher(content).find()) findings.add(new Finding(root.relativize(path).toString(), "OpenRouter API key pattern detected"));
            }
        }
        return List.copyOf(findings);
    }
    private static boolean skip(Path root, Path path) { for (Path part : root.relativize(path)) if (SKIP.contains(part.toString())) return true; return false; }
    public record Finding(String path, String message) {}
}