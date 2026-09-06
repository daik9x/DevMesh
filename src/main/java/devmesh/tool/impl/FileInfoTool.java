package devmesh.tool.impl;

import devmesh.tool.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class FileInfoTool implements Tool {
    @Override public String name() { return "FileInfo"; }
    @Override public String description() { return "Return structured metadata for a file or directory using Java NIO."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public boolean shouldDefer() { return true; }
    @Override public Map<String, Object> schema() { return CreateDirectoryTool.schema(name(), description(), Map.of("path", Map.of("type", "string")), List.of("path")); }
    @Override public ToolResult execute(Map<String, Object> args) { try { Path path = NativePathSupport.requiredPath(args, "path"); if (path == null) return ToolResult.error("Error: path is required"); BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class); return ToolResult.success(Map.of("path", path.toString(), "type", attrs.isDirectory() ? "directory" : attrs.isRegularFile() ? "file" : "other", "size", attrs.size(), "lastModified", attrs.lastModifiedTime().toString(), "readable", Files.isReadable(path), "writable", Files.isWritable(path)).toString()); } catch (Exception e) { return ToolResult.error("Error reading metadata: " + e.getMessage()); } }
}