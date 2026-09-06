package devmesh.tool.impl;

import java.nio.file.Path;
import java.util.Map;

final class NativePathSupport {
    private NativePathSupport() {}
    static String path(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String s ? s : "";
    }
    static Path requiredPath(Map<String, Object> args, String key) {
        String value = path(args, key);
        return value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }
}