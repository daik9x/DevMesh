package devmesh.tool.impl;

import devmesh.tool.*;
import java.nio.file.*;
import java.util.*;

public final class DeleteFileTool implements Tool {
    @Override public String name() { return "DeleteFile"; }
    @Override public String description() { return "Delete one file or an empty directory using Java NIO."; }
    @Override public ToolCategory category() { return ToolCategory.WRITE; }
    @Override public boolean shouldDefer() { return true; }
    @Override public Map<String, Object> schema() { return CreateDirectoryTool.schema(name(), description(), Map.of("path", Map.of("type", "string")), List.of("path")); }
    @Override public ToolResult execute(Map<String, Object> args) { try { Path path = NativePathSupport.requiredPath(args, "path"); if (path == null) return ToolResult.error("Error: path is required"); boolean deleted = Files.deleteIfExists(path); return ToolResult.success(deleted ? "Deleted: " + path : "Path did not exist: " + path); } catch (Exception e) { return ToolResult.error("Error deleting path: " + e.getMessage()); } }
}