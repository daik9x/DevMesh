package devmesh.tool.impl;

import devmesh.tool.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class HashFileTool implements Tool {
    @Override public String name() { return "HashFile"; }
    @Override public String description() { return "Calculate a file checksum using Java MessageDigest."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public boolean shouldDefer() { return true; }
    @Override public Map<String, Object> schema() { return CreateDirectoryTool.schema(name(), description(), Map.of("path", Map.of("type", "string"), "algorithm", Map.of("type", "string", "default", "SHA-256")), List.of("path")); }
    @Override public ToolResult execute(Map<String, Object> args) { try { Path path = NativePathSupport.requiredPath(args, "path"); if (path == null) return ToolResult.error("Error: path is required"); String algorithm = NativePathSupport.path(args, "algorithm"); if (algorithm.isBlank()) algorithm = "SHA-256"; byte[] digest = MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(path)); return ToolResult.success(Map.of("path", path.toString(), "algorithm", algorithm, "hash", HexFormat.of().formatHex(digest)).toString()); } catch (Exception e) { return ToolResult.error("Error hashing file: " + e.getMessage()); } }
}