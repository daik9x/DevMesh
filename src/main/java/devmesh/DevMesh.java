
package devmesh;

import devmesh.config.AppConfig;
import devmesh.config.ConfigLoader;
import devmesh.print.PrintMode;
import devmesh.remote.RemoteServer;
import devmesh.tui.DevMeshModel;
import devmesh.observability.TraceAnalyzer;
import devmesh.observability.TraceEvaluator;
import devmesh.evolution.SkillEvolutionCli;
import devmesh.evaluation.BenchmarkCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import devmesh.tui.tea.Program;

import java.util.List;
import java.nio.file.Path;
import sun.misc.Signal;

public class DevMesh {

    public static final String VERSION = "2.0.0";

    // Default listening address for Remote mode.
    private static final String DEFAULT_REMOTE_ADDR = ":18888";

    public static void main(String[] args) {
        if (hasVersionFlag(args)) {
            System.out.println("DevMesh " + VERSION);
            return;
        }
        if (hasHelpFlag(args)) {
            printUsage();
            return;
        }

        Integer evolutionExit = SkillEvolutionCli.tryRun(args);
        if (evolutionExit != null) {
            if (evolutionExit != 0) System.exit(evolutionExit);
            return;
        }

        Integer benchmarkExit = BenchmarkCli.tryRun(args);
        if (benchmarkExit != null) {
            if (benchmarkExit != 0) System.exit(benchmarkExit);
            return;
        }

        // Parse CLI arguments: -p "prompt", --output-format, --remote[=addr], and the config path.
        String configPath = null;
        boolean remoteMode = false;
        String remoteAddr = DEFAULT_REMOTE_ADDR;
        String printPrompt = null;
        String outputFormat = "text";
        String traceReportPath = null;
        String traceEvalPath = null;
        String tracePolicyPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-p") && i + 1 < args.length) {
                printPrompt = args[++i];
            } else if (arg.startsWith("-p=")) {
                printPrompt = arg.substring("-p=".length());
            } else if (arg.equals("--output-format") && i + 1 < args.length) {
                outputFormat = args[++i];
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring("--output-format=".length());
            } else if (arg.equals("--trace-report") && i + 1 < args.length) {
                traceReportPath = args[++i];
            } else if (arg.startsWith("--trace-report=")) {
                traceReportPath = arg.substring("--trace-report=".length());
            } else if (arg.equals("--trace-eval") && i + 1 < args.length) {
                traceEvalPath = args[++i];
            } else if (arg.startsWith("--trace-eval=")) {
                traceEvalPath = arg.substring("--trace-eval=".length());
            } else if (arg.equals("--trace-policy") && i + 1 < args.length) {
                tracePolicyPath = args[++i];
            } else if (arg.startsWith("--trace-policy=")) {
                tracePolicyPath = arg.substring("--trace-policy=".length());
            } else if (arg.equals("--remote")) {
                remoteMode = true;
            } else if (arg.startsWith("--remote=")) {
                remoteMode = true;
                remoteAddr = arg.substring("--remote=".length());
            } else if (configPath == null) {
                configPath = arg;
            }
        }

        // Offline observability commands do not require an LLM/API config.
        if (traceReportPath != null || traceEvalPath != null) {
            try {
                String target = traceEvalPath != null ? traceEvalPath : traceReportPath;
                var summary = TraceAnalyzer.analyze(Path.of(target));
                if (traceEvalPath != null) {
                    if (tracePolicyPath == null || tracePolicyPath.isBlank()) {
                        throw new IllegalArgumentException("--trace-eval requires --trace-policy <yaml>");
                    }
                    var policy = TraceEvaluator.loadPolicy(Path.of(tracePolicyPath));
                    var result = TraceEvaluator.evaluate(summary, policy);
                    if ("json".equals(outputFormat)) {
                        var payload = new java.util.LinkedHashMap<String, Object>();
                        payload.put("summary", summary.toMap());
                        payload.put("evaluation", result.toMap());
                        System.out.println(prettyJson(payload));
                    } else {
                        System.out.print(summary.renderText());
                        System.out.print(result.renderText());
                    }
                    if (!result.passed()) System.exit(2);
                } else if ("json".equals(outputFormat)) {
                    System.out.println(prettyJson(summary.toMap()));
                } else {
                    System.out.print(summary.renderText());
                }
            } catch (Exception e) {
                System.err.println("Trace command failed: " + e.getMessage());
                System.exit(2);
            }
            return;
        }

        // Fall back to the environment variable.
        if (configPath == null) {
            String envPath = System.getenv("DEVMESH_CONFIG");
            if (envPath != null && !envPath.isBlank()) {
                configPath = envPath;
            }
        }

        AppConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // -p mode: run non-interactively and write the result to stdout.
        if (printPrompt != null) {
            PrintMode.OutputFormat fmt = "stream-json".equals(outputFormat)
                    ? PrintMode.OutputFormat.STREAM_JSON
                    : PrintMode.OutputFormat.TEXT;
            PrintMode.run(config, printPrompt, fmt);
            return;
        }

        // --remote mode: start the HTTP + WebSocket server without entering the TUI.
        if (remoteMode) {
            var server = new RemoteServer(
                    config.getProviders(),
                    config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                    config.getHooks() != null ? config.getHooks() : List.of(),
                    remoteAddr,
                    config.isEnableCoordinatorMode()
            );
            try {
                server.run();
            } catch (Exception e) {
                System.err.println("Remote server error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        // TUI mode (default).
        var model = new DevMeshModel(
                config.getProviders(),
                config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                config.getHooks() != null ? config.getHooks() : List.of(),
                config.isEnableCoordinatorMode()
        );

        var program = new Program(model);

        model.setProgram(program);

        System.out.print("\033[?25l");

        // Re-register SIGINT handler after TUI4J's program.run() starts,
        // overriding the framework's default quit-on-SIGINT behavior.
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            try {
                Signal.handle(new Signal("INT"), sig -> model.handleSigint());
            } catch (IllegalArgumentException ignored) {}
        });

        try {
            program.run();
        } finally {
            System.out.print("\033[?25h");
            System.out.flush();
        }
    }

    private static String prettyJson(Object value) throws Exception {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(value);
    }

    private static boolean hasHelpFlag(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) return true;
        }
        return false;
    }

    private static boolean hasVersionFlag(String[] args) {
        for (String arg : args) {
            if ("--version".equals(arg) || "-V".equals(arg)) return true;
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("DevMesh - local AI coding agent");
        System.out.println();
        System.out.println("Usage: java -jar devmesh.jar [config.yaml] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help                         Show this help message");
        System.out.println("  -V, --version                      Show the DevMesh version");
        System.out.println("  -p, -p=<prompt>                    Run a one-shot prompt");
        System.out.println("      --output-format <text|stream-json>");
        System.out.println("                                      Select one-shot output format");
        System.out.println("      --remote[=<address>]            Start Remote Web mode (default :18888)");
        System.out.println("      --trace-report <path>           Render an offline trace report");
        System.out.println("      --trace-eval <path>             Evaluate traces with a policy");
        System.out.println("      --trace-policy <path>           YAML policy for --trace-eval");
        System.out.println();
        System.out.println("Environment:");
        System.out.println("  DEVMESH_CONFIG                      Default configuration file path");
        System.out.println("  DEVMESH_TRACE=false                 Disable trace recording");
        System.out.println("  DEVMESH_TRACE_DIR=<path>            Trace output directory");
    }
}
