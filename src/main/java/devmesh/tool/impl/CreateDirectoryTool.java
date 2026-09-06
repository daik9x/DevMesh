package devmesh.tool.impl;

import devmesh.tool.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class CreateDirectoryTool implements Tool {
    @Override public String name() { return "CreateDirectory"; }
    @Override public String description() { return "Create a directory and any missing parent directories using Java NIO."; }
    @Override public ToolCategory category() { return ToolCategory.WRITE; }
    @Override public boolean shouldDefer() { return true; }
    @Override public Map<String, Object> schema() { return schema(name(), description(), Map.of("path", Map.of("type", "string")), List.of("path")); }
    @Override public ToolResult execute(Map<String, Object> args) { try { Path path = NativePathSupport.requiredPath(args, "path"); if (path == null) return ToolResult.error("Error: path is required"); Files.createDirectories(path); return ToolResult.success("Created directory: " + path); } catch (Exception e) { return ToolResult.error("Error creating directory: " + e.getMessage()); } }
    static Map<String, Object> schema(String name, String description, Map<String, Object> properties, List<String> required) { return Map.of("name", name, "description", description, "input_schema", Map.of("type", "object", "properties", properties, "required", required)); }
}