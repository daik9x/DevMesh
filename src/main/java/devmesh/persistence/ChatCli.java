package devmesh.persistence;

import java.nio.file.Path;

/** Offline chat management commands that do not require provider configuration. */
public final class ChatCli {
    private ChatCli() {}
    public static Integer tryRun(String[] args) {
        if (args.length < 2 || !"chat".equals(args[0])) return null;
        try {
            Path workspace = Path.of(option(args, "--workspace", System.getProperty("user.dir"))).toAbsolutePath().normalize();
            Path database = workspace.resolve(option(args, "--database", ".devmesh/devmesh.db"));
            try (var persistence = new SQLitePersistence(database)) {
                var chats = new ChatManager(persistence, workspace); String command = args[1];
                switch (command) {
                    case "new" -> { String title = args.length > 2 && !args[2].startsWith("--") ? args[2] : "New chat"; System.out.println(chats.create(title, null, null, "default").id()); }
                    case "list" -> chats.list(100, 0).forEach(s -> System.out.printf("%s\t%s\t%s%n", s.id(), s.title(), s.status()));
                    case "search" -> { String query = args.length > 2 ? args[2] : ""; chats.search(query, 100).forEach(s -> System.out.printf("%s\t%s\t%s%n", s.id(), s.title(), s.status())); }
                    case "rename" -> { require(args, 3); System.out.println(chats.rename(args[2], args[3]).title()); }
                    case "archive" -> { require(args, 3); chats.archive(args[2]); }
                    case "delete" -> { require(args, 3); chats.delete(args[2]); }
                    default -> throw new IllegalArgumentException("Unknown chat command: " + command);
                }
                return 0;
            }
        } catch (Exception e) { System.err.println("Chat command failed: " + e.getMessage()); return 2; }
    }
    private static String option(String[] args, String name, String fallback) { for (int i = 0; i < args.length; i++) { if (name.equals(args[i]) && i + 1 < args.length) return args[i + 1]; if (args[i].startsWith(name + "=")) return args[i].substring(name.length() + 1); } return fallback; }
    private static void require(String[] args, int count) { if (args.length <= count) throw new IllegalArgumentException("Missing required chat argument"); }
}