package devmesh.tool;

import devmesh.tool.impl.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeFileToolTest {
    @Test
    void performsFilesystemOperationsWithoutShell() throws Exception {
        var root = Files.createTempDirectory("native-tools");
        var source = root.resolve("source.txt");
        var copy = root.resolve("nested/copy.txt");
        Files.writeString(source, "hello");

        assertFalse(new CreateDirectoryTool().execute(Map.of("path", copy.getParent().toString())).isError());
        assertFalse(new CopyFileTool().execute(Map.of("source", source.toString(), "destination", copy.toString())).isError());
        assertEquals("hello", Files.readString(copy));
        assertFalse(new MoveFileTool().execute(Map.of("source", copy.toString(), "destination", root.resolve("moved.txt").toString())).isError());
        assertFalse(new DeleteFileTool().execute(Map.of("path", root.resolve("moved.txt").toString())).isError());
        assertFalse(Files.exists(root.resolve("moved.txt")));
    }

    @Test
    void returnsStructuredMetadataAndStableHash() throws Exception {
        var root = Files.createTempDirectory("native-tools");
        var file = root.resolve("value.txt");
        Files.writeString(file, "hello");
        var info = new FileInfoTool().execute(Map.of("path", file.toString()));
        var hash = new HashFileTool().execute(Map.of("path", file.toString(), "algorithm", "SHA-256"));
        assertFalse(info.isError());
        assertTrue(info.output().contains("type=file"));
        assertTrue(hash.output().contains("algorithm=SHA-256"));
        assertTrue(hash.output().contains("2cf24dba5fb0a30e"));
    }

    @Test
    void rejectsMissingRequiredPaths() {
        assertTrue(new DeleteFileTool().execute(Map.of()).isError());
        assertTrue(new HashFileTool().execute(Map.of("path", "missing.txt")).isError());
    }
}
