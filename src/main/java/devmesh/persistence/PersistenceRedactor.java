package devmesh.persistence;

import java.util.regex.Pattern;

final class PersistenceRedactor {
    private static final Pattern SECRET = Pattern.compile("(?i)(api[_-]?key|access[_-]?token|authorization|password|secret|private[_-]?key)(\\s*[=:]\\s*)[^,\\s}]+") ;
    private PersistenceRedactor() {}
    static String redact(String value) { return value == null ? null : SECRET.matcher(value).replaceAll("$1$2[REDACTED]"); }
}