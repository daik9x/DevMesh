package devmesh.config;

import java.nio.file.Files;
import java.nio.file.Path;

/** Initializes a workspace without loading provider configuration or contacting a network. */
public final class InitCli {
    private static final String DEFAULT_CONFIG = """
            # DevMesh workspace configuration
            # Set OPENROUTER_API_KEY in the environment before using the provider.
            providers:
              - name: OpenRouter
                protocol: openrouter
                model: nvidia/nemotron-3-ultra-550b-a55b:free
                context_window: 128000
                max_output_tokens: 8192
            permission_mode: default
            checkpoint:
              enabled: true
              max_checkpoints: 50
              retention_days: 7
              auto_resume: ask
              redact_secrets: true
            persistence:
              database_path: .devmesh/devmesh.db
              wal: true
              busy_timeout_ms: 5000
              fts_enabled: true
            """;

    private InitCli() {}

    public static Integer tryRun(String[] args) {
        if (args.length == 0 || !"init".equals(args[0])) return null;
        try {
            Path workspace = Path.of(option(args, "--workspace", System.getProperty("user.dir")))
                    .toAbsolutePath().normalize();
            Path devmesh = workspace.resolve(".devmesh");
            Path config = devmesh.resolve("config.yaml");
            boolean force = hasFlag(args, "--force");
            if (Files.exists(config) && !force) {
                throw new IllegalStateException("Configuration already exists at " + config + "; use --force to replace it");
            }
            Files.createDirectories(devmesh.resolve("sessions"));
            Files.createDirectories(devmesh.resolve("tasks"));
            Files.createDirectories(devmesh.resolve("checkpoints"));
            Files.createDirectories(devmesh.resolve("context"));
            Files.writeString(config, DEFAULT_CONFIG);
            System.out.println("Initialized DevMesh workspace: " + workspace);
            System.out.println("Configuration: " + config);
            System.out.println("Set OPENROUTER_API_KEY before starting an agent task.");
            return 0;
        } catch (Exception e) {
            System.err.println("DevMesh init failed: " + e.getMessage());
            return 2;
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) if (flag.equals(arg)) return true;
        return false;
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length; i++) {
            if (name.equals(args[i]) && i + 1 < args.length) return args[i + 1];
            if (args[i].startsWith(name + "=")) return args[i].substring(name.length() + 1);
        }
        return fallback;
    }
}
