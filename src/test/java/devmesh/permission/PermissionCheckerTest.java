package devmesh.permission;

import devmesh.tool.Tool;
import devmesh.tool.ToolCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCheckerTest {

    private static final Tool BASH_TOOL = new Tool() {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return ""; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public Map<String, Object> schema() { return Map.of(); }
        @Override public devmesh.tool.ToolResult execute(Map<String, Object> args) { return new devmesh.tool.ToolResult("", false); }
    };

    @Test
    void sandboxAutoAllowRespectsCompoundDeny(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".devmesh");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"Bash(rm -rf /)\"\n  effect: deny\n");

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        checker.setSandboxEnabled(true);

        var result = checker.check(BASH_TOOL, Map.of("command", "echo ok && rm -rf /"));
        assertEquals(PermissionMode.Decision.DENY, result.decision(),
                "compound command with denied subcommand should be deny");
    }

    @Test
    void sandboxAutoAllowAllowsSafeCommand(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".devmesh");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"Bash(rm -rf /)\"\n  effect: deny\n");

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        checker.setSandboxEnabled(true);

        var result = checker.check(BASH_TOOL, Map.of("command", "go test ./..."));
        assertEquals(PermissionMode.Decision.ALLOW, result.decision(),
                "safe command with sandbox should be allow");
    }

    @Test
    void sandboxAutoAllowRespectsAskRule(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".devmesh");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"Bash(git push origin main)\"\n  effect: ask\n");

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        checker.setSandboxEnabled(true);

        var result = checker.check(BASH_TOOL, Map.of("command", "git push origin main"));
        assertEquals(PermissionMode.Decision.ASK, result.decision(),
                "ask rule should not be overridden by sandbox");
    }

        @Test
        void rejectsWorkspaceSymlinkEscapes(@TempDir Path tmpDir) throws IOException {
                Path outside = Files.createTempDirectory("outside");
                Path link = tmpDir.resolve("linked");
                try {
                        Files.createSymbolicLink(link, Path.of("/etc"));
                } catch (UnsupportedOperationException | IOException e) {
                        return;
                }
                Tool read = new Tool() {
                        @Override public String name() { return "ReadFile"; }
                        @Override public String description() { return ""; }
                        @Override public ToolCategory category() { return ToolCategory.READ; }
                        @Override public Map<String, Object> schema() { return Map.of(); }
                        @Override public devmesh.tool.ToolResult execute(Map<String, Object> args) { return devmesh.tool.ToolResult.success(""); }
                };
                var result = new PermissionChecker(PermissionMode.DEFAULT, tmpDir)
                                .check(read, Map.of("file_path", link.resolve("secret.txt").toString()));
                assertNotEquals(PermissionMode.Decision.ALLOW, result.decision());
        }
}
