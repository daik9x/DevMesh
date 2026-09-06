package devmesh.permission;

import devmesh.tool.Tool;
import devmesh.tool.ToolCategory;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PermissionChecker {

    private PermissionMode mode;
    private final Path projectRoot;

    private final Set<String> allowAlwaysRules = new java.util.HashSet<>();

    /** Parsed permission rules loaded from YAML files (mutable for dynamic append). */
    private final ArrayList<PermissionRule> fileRules;
    private String planFilePath;

    private final List<String> denyWrite;

    private boolean sandboxEnabled;

    /** A single parsed rule from a permissions.yaml file. */
    private record PermissionRule(String toolName, String pattern, RuleEffect effect) {
        boolean matches(String toolName, String content) {
            if (!this.toolName.equals(toolName)) {
                return false;
            }

            return globMatch(pattern, content);
        }

        private static boolean globMatch(String pattern, String content) {
            String re = "^" + pattern
                    .replace("\\", "\\\\")
                    .replace(".", "\\.")
                    .replace("+", "\\+")
                    .replace("^", "\\^")
                    .replace("$", "\\$")
                    .replace("{", "\\{")
                    .replace("}", "\\}")
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                    .replace("|", "\\|")
                    .replace("[", "\\[")
                    .replace("]", "\\]")
                    .replace("*", ".*")
                    .replace("?", ".") + "$";
            try {
                return content.matches(re);
            } catch (Exception e) {
                return content.equals(pattern);
            }
        }
    }

    private enum RuleEffect {
        ALLOW, DENY, ASK
    }

    private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
            "Agent", "ToolSearch", "AskUserQuestion", "ExitPlanMode"
    );

    private static final Set<String> SAFE_COMMANDS = Set.of(
            "ls", "dir", "pwd", "echo", "cat", "head", "tail", "wc",
            "find", "which", "whereis", "whoami", "hostname", "uname",
            "date", "cal", "uptime", "df", "du", "free", "env", "printenv",
            "file", "stat", "readlink", "realpath", "basename", "dirname",
            "sort", "uniq", "tr", "cut", "awk", "sed", "grep", "egrep", "fgrep",
            "diff", "comm", "tee", "xargs", "true", "false", "test",
            "git status", "git log", "git diff", "git show", "git branch",
            "git tag", "git remote", "git rev-parse", "git ls-files",
            "git blame", "git stash list", "go version", "go env",
            "node -v", "npm -v", "npx", "python --version", "pip list",
            "cargo --version", "rustc --version", "java -version", "java --version"
    );

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+if=.*of=/dev/"),
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile(">\\s*/dev/sd")
    );

        private static final Map<String, String> CONTENT_FIELDS = Map.ofEntries(
            Map.entry("Bash", "command"), Map.entry("ReadFile", "file_path"),
            Map.entry("WriteFile", "file_path"), Map.entry("EditFile", "file_path"),
            Map.entry("CreateDirectory", "path"), Map.entry("CopyFile", "source"),
            Map.entry("MoveFile", "source"), Map.entry("DeleteFile", "path"),
            Map.entry("HashFile", "path"), Map.entry("FileInfo", "path"),
            Map.entry("Glob", "pattern"), Map.entry("Grep", "pattern"));

    private static final List<String> DEFAULT_DENY_WRITE = List.of(
            ".devmesh/config.yaml",
            ".devmesh/permissions.local.yaml",
            ".devmesh/skills/"
    );

    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode;
        this.projectRoot = projectRoot;
        this.fileRules = new ArrayList<>(loadRules());


        var resolvedDeny = new ArrayList<String>();
        if (projectRoot != null) {
            for (String rel : DEFAULT_DENY_WRITE) {
                resolvedDeny.add(projectRoot.resolve(rel).toAbsolutePath().normalize().toString());
            }
        }
        this.denyWrite = resolvedDeny;
        this.sandboxEnabled = false;
    }

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }
    public void setPlanFilePath(String path) { this.planFilePath = path; }

    public boolean isSandboxEnabled() { return sandboxEnabled; }
    public void setSandboxEnabled(boolean enabled) { this.sandboxEnabled = enabled; }
    public List<String> getDenyWrite() { return Collections.unmodifiableList(denyWrite); }

    public record CheckResult(PermissionMode.Decision decision, String reason) {
        public static CheckResult allow() { return new CheckResult(PermissionMode.Decision.ALLOW, ""); }

        public static CheckResult deny(String reason) { return new CheckResult(PermissionMode.Decision.DENY, reason); }
        public static CheckResult ask() { return new CheckResult(PermissionMode.Decision.ASK, ""); }
        public static CheckResult ask(String reason) { return new CheckResult(PermissionMode.Decision.ASK, reason); }
    }

    public CheckResult check(Tool tool, Map<String, Object> args) {
        String toolName = tool.name();
        String content = extractContent(toolName, args);

        // Layer 0: Plan mode exceptions
        if (mode == PermissionMode.PLAN) {
            if (PLAN_MODE_ALLOWED_TOOLS.contains(toolName)) {
                return CheckResult.allow();
            }
            if ("WriteFile".equals(toolName) || "EditFile".equals(toolName)) {
                String path = stringArg(args, "file_path", "");
                if (path.contains(".devmesh/plans/")) {
                    return CheckResult.allow();
                }
            }
        }

        // Layer 1: Safe commands (auto-allow)
        if ("Bash".equals(toolName) && content != null && isSafeCommand(content)) {
            return CheckResult.allow();
        }

        // Layer 2: Dangerous command detection
        if ("Bash".equals(toolName) && content != null) {
            for (var pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    return CheckResult.deny("Dangerous command detected: " + pattern.pattern());
                }
            }
        }


        if (content != null && isWritePathTool(toolName) && isDeniedPath(content)) {
            return CheckResult.deny("Path is protected by sandbox: " + content);
        }

        // Layer 3: Path sandbox
        if (content != null && isPathTool(toolName)) {
            if (!isPathAllowed(content) && mode != PermissionMode.BYPASS) {
                return CheckResult.ask("Path outside allowed sandbox: " + content);
            }
        }

        // Layer 4: File-based permission rules (last matching rule wins)
        if (content != null) {
            for (int i = fileRules.size() - 1; i >= 0; i--) {
                PermissionRule rule = fileRules.get(i);
                if (rule.matches(toolName, content)) {
                    return switch (rule.effect) {
                        case ALLOW -> CheckResult.allow();
                        case DENY -> CheckResult.deny("Denied by rule: " + rule.toolName + "(" + rule.pattern + ")");
                        case ASK -> CheckResult.ask();
                    };
                }
            }
        }

        // Layer 4b: Allow-always rules (session-level)
        if (allowAlwaysRules.contains(toolName + ":" + content)) {
            return CheckResult.allow();
        }



        if (sandboxEnabled && tool.category() == ToolCategory.COMMAND) {
            String[] subcommands = content.split("\\s*(?:&&|\\|\\||[;|])\\s*");
            boolean hasAsk = false;
            for (String sub : subcommands) {
                sub = sub.trim();
                if (sub.isEmpty()) continue;
                for (int i = fileRules.size() - 1; i >= 0; i--) {
                    PermissionRule rule = fileRules.get(i);
                    if (rule.matches(toolName, sub)) {
                        if (rule.effect == RuleEffect.DENY) {
                            return CheckResult.deny("Permission rule: deny");
                        }
                        if (rule.effect == RuleEffect.ASK) {
                            hasAsk = true;
                        }
                        break;
                    }
                }
            }
            if (hasAsk) {
                return CheckResult.ask();
            }
            return CheckResult.allow();
        }

        // Layer 5: Permission mode matrix
        var decision = mode.decide(tool.category());
        return switch (decision) {
            case ALLOW -> CheckResult.allow();
            case DENY -> CheckResult.deny("Denied by permission mode: " + mode);
            case ASK -> CheckResult.ask();
        };
    }

    public void addAllowAlwaysRule(String toolName, String content) {
        allowAlwaysRules.add(toolName + ":" + content);
    }

    // --- Rule loading from YAML files ---

    private static final Pattern RULE_PATTERN = Pattern.compile("^(\\w+)\\((.+)\\)$");

    /**
     * Load permission rules from user-level, project-level, and local YAML files.
     * User-level: ~/.devmesh/permissions.yaml
     * Project-level: {projectRoot}/.devmesh/permissions.yaml
     * Local-level: {projectRoot}/.devmesh/permissions.local.yaml
     *
     * Rules are loaded in order; the last matching rule wins when evaluated.
     */
    private List<PermissionRule> loadRules() {
        var rules = new ArrayList<PermissionRule>();

        // User-level rules
        Path userHome = Path.of(System.getProperty("user.home"));
        Path userFile = userHome.resolve(".devmesh").resolve("permissions.yaml");
        rules.addAll(loadRulesFile(userFile));

        // Project-level rules
        if (projectRoot != null) {
            Path projectFile = projectRoot.resolve(".devmesh").resolve("permissions.yaml");
            rules.addAll(loadRulesFile(projectFile));

            // Local-level rules (gitignored, session-persistent)
            Path localFile = projectRoot.resolve(".devmesh").resolve("permissions.local.yaml");
            rules.addAll(loadRulesFile(localFile));
        }

        return new ArrayList<>(rules);
    }

    public void appendLocalRule(String toolName, String pattern) {
        if (projectRoot == null) return;
        Path localFile = projectRoot.resolve(".devmesh").resolve("permissions.local.yaml");
        try {
            Files.createDirectories(localFile.getParent());
            var rules = new ArrayList<>(loadRulesFile(localFile));
            rules.add(new PermissionRule(toolName, pattern, RuleEffect.ALLOW));

            var entries = new ArrayList<Map<String, String>>();
            for (var r : rules) {
                entries.add(Map.of("rule", r.toolName + "(" + r.pattern + ")", "effect",
                        r.effect == RuleEffect.ALLOW ? "allow" : "deny"));
            }
            var yaml = new Yaml();
            Files.writeString(localFile, yaml.dump(entries));
            // Reload
            fileRules.clear();
            fileRules.addAll(loadRules());
        } catch (IOException ignored) {}
    }

    /**
     * Parse a single YAML permissions file into a list of rules.
     * Expected format: a YAML list of maps with "rule" and "effect" keys.
     * Example:
     *   - rule: "Bash(git *)"
     *     effect: allow
     *   - rule: "WriteFile(/etc/*)"
     *     effect: deny
     */
    @SuppressWarnings("unchecked")
    private List<PermissionRule> loadRulesFile(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return List.of();
        }

        Yaml yaml = new Yaml();
        Object parsed;
        try {
            parsed = yaml.load(content);
        } catch (Exception e) {
            return List.of();
        }

        if (!(parsed instanceof List<?> entries)) {
            return List.of();
        }

        var rules = new ArrayList<PermissionRule>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object ruleObj = map.get("rule");
            Object effectObj = map.get("effect");
            if (!(ruleObj instanceof String ruleStr) || !(effectObj instanceof String effectStr)) {
                continue;
            }

            RuleEffect effect;
            if ("allow".equals(effectStr)) {
                effect = RuleEffect.ALLOW;
            } else if ("deny".equals(effectStr)) {
                effect = RuleEffect.DENY;
            } else if ("ask".equals(effectStr)) {
                effect = RuleEffect.ASK;
            } else {
                continue;
            }

            Matcher m = RULE_PATTERN.matcher(ruleStr.trim());
            if (!m.matches()) {
                continue;
            }
            rules.add(new PermissionRule(m.group(1), m.group(2), effect));
        }
        return rules;
    }

    private boolean isSafeCommand(String command) {
        String trimmed = command.trim();
        if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
                || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
            return false;
        }
        for (var safe : SAFE_COMMANDS) {
            if (trimmed.equals(safe) || trimmed.startsWith(safe + " ")) {
                return true;
            }
        }
        return false;
    }

    private boolean isPathTool(String toolName) {
        return Set.of("ReadFile", "WriteFile", "EditFile", "CreateDirectory", "CopyFile", "MoveFile", "DeleteFile", "HashFile", "FileInfo").contains(toolName);
    }

    private boolean isWritePathTool(String toolName) {
        return Set.of("WriteFile", "EditFile", "CreateDirectory", "CopyFile", "MoveFile", "DeleteFile").contains(toolName);
    }

    /**
     */
    private boolean isDeniedPath(String pathStr) {
        try {
            String normalized = Path.of(pathStr).toAbsolutePath().normalize().toString();
            for (String deny : denyWrite) {
                if (normalized.startsWith(deny)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean isPathAllowed(String pathStr) {
        try {
            if (projectRoot == null) return false;
            Path requested = Path.of(pathStr).toAbsolutePath().normalize();
            Path p = Files.exists(requested)
                    ? requested.toRealPath()
                    : realParent(requested);
            Path root = projectRoot.toAbsolutePath().normalize().toRealPath();
            Path tmp = Path.of("/tmp").toAbsolutePath().normalize();
            Path realTmp = Files.exists(tmp) ? tmp.toRealPath() : tmp;
            return p.startsWith(root) || p.startsWith(realTmp);
        } catch (Exception e) {
            return false;
        }
    }

    private static Path realParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) return path;
        return parent.toRealPath().resolve(path.getFileName()).normalize();
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = CONTENT_FIELDS.get(toolName);
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    public String describeToolAction(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "Bash" -> "Execute: " + stringArg(args, "command", "");
            case "ReadFile" -> "Read: " + stringArg(args, "file_path", "");
            case "WriteFile" -> "Write: " + stringArg(args, "file_path", "");
            case "EditFile" -> "Edit: " + stringArg(args, "file_path", "");
            case "Glob" -> "Glob: " + stringArg(args, "pattern", "");
            case "Grep" -> "Grep: " + stringArg(args, "pattern", "");
            case "Agent" -> {
                String desc = stringArg(args, "description", "");
                String prompt = stringArg(args, "prompt", "");
                if (!desc.isEmpty()) {
                    yield "Agent: " + desc;
                } else if (!prompt.isEmpty()) {
                    yield "Agent: " + (prompt.length() > 80 ? prompt.substring(0, 77) + "..." : prompt);
                } else {
                    yield "Agent";
                }
            }
            default -> {
                var parts = new java.util.ArrayList<String>();
                for (var entry : args.entrySet()) {
                    String s = String.valueOf(entry.getValue());
                    if (s.length() > 80) s = s.substring(0, 77) + "...";
                    parts.add(entry.getKey() + "=" + s);
                }
                yield parts.isEmpty() ? toolName : String.join(", ", parts);
            }
        };
    }
}
