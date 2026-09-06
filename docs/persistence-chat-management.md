# Persistence Layer 2.0 and Chat Management

DevMesh now has an explicit SQLite persistence boundary in
`devmesh.persistence`. `SQLitePersistence` owns JDBC access, schema migration,
transaction boundaries, WAL configuration, foreign keys, indexed session/message
queries, pagination, and secret redaction. Business code uses
`PersistenceService` and `ChatManager` rather than raw SQL.

## Database

The default chat CLI location is:

```text
.devmesh/devmesh.db
```

The path can be overridden with `--database` for the offline chat commands or
with the existing YAML configuration:

```yaml
persistence:
  databasePath: .devmesh/devmesh.db
  wal: true
  busyTimeoutMs: 5000
  ftsEnabled: true
```

Schema version 1 creates sessions, messages, tasks, todos, checkpoints,
tool executions, repair attempts, repositories, evaluations, and
`schema_migrations`. Migrations run transactionally and do not recreate an
existing database.

Initialize the workspace before using the normal TUI or chat commands:

```text
devmesh init
devmesh init --workspace /path/to/repo
devmesh init --workspace /path/to/repo --force
```

`init` is offline, creates the required `.devmesh` directories, and never
writes an API key.

## Chat commands

These commands run without provider configuration:

```text
devmesh chat new "Fix authentication" --workspace /path/to/repo
devmesh chat list --workspace /path/to/repo
devmesh chat search authentication --workspace /path/to/repo
devmesh chat rename <id> "New title" --workspace /path/to/repo
devmesh chat archive <id> --workspace /path/to/repo
devmesh chat delete <id> --workspace /path/to/repo
```

Messages use explicit sequence numbers, and session search covers title,
workspace path, and message content. Archived sessions are hidden from normal
listing but remain in the database until explicitly deleted.

## Safety and compatibility

Message content and metadata are redacted for common credential-shaped keys
before insertion. SQLite enables foreign keys, WAL mode, full synchronous
writes, and a busy timeout. Failed transactions roll back.

The existing JSONL `SessionManager` and local JSON checkpoint store remain
available for backward compatibility. Automatic migration of those legacy
stores into SQLite, full Agent-loop state restoration, FTS5 search, TUI chat
browser commands, export/import, and SQLite-backed Todo/tool/repair adapters
are follow-up integration work rather than silently claimed features.
