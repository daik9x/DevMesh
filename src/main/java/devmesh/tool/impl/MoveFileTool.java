package devmesh.tool.impl;

import devmesh.tool.*;
import java.nio.file.*;
import java.util.*;

public final class MoveFileTool implements Tool {
    @Override public String name() { return "MoveFile"; }
    @Override public String description() { return "Move a file using Java NIO without spawning a shell."; }
    @Override public ToolCategory category() { return ToolCategory.WRITE; }
    @Override public boolean shouldDefer() { return true; }
    @Override public Map<String, Object> schema() { return CreateDirectoryTool.schema(name(), description(), Map.of("source", Map.of("type", "string"), "destination", Map.of("type", "string")), List.of("source", "destination")); }
    @Override public ToolResult execute(Map<String, Object> args) { try { Path source = NativePathSupport.requiredPath(args, "source"), destination = NativePathSupport.requiredPath(args, "destination"); if (source == null || destination == null) return ToolResult.error("Error: source and destination are required"); Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); return ToolResult.success("Moved file to: " + destination); } catch (Exception e) { return ToolResult.error("Error moving file: " + e.getMessage()); } }
}