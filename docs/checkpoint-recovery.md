# Checkpoint and Recovery

DevMesh checkpoints are immutable logical execution snapshots written below
`.devmesh/checkpoints/<sessionId>/`. A checkpoint contains task, Todo, agent,
context, repository, tool, repair, verification, execution, and metadata maps;
it does not serialize live Java objects or the full conversation.

## Storage and safety

`LocalCheckpointStore` writes a redacted snapshot to a temporary file, flushes
it, and atomically replaces the final JSON file. A SHA-256 integrity value and
schema version are checked before a checkpoint is returned by `load` or
`loadLatest`. Corrupt, incompatible, or non-`VALID` files are not resumable.
Checkpoint history is append-only and linked through `parentCheckpointId`.
Retention is bounded by `CheckpointManager`.

Common credential-shaped keys such as API keys, tokens, passwords,
authorization values, secrets, and private keys are stored as `[REDACTED]`.
Do not put credentials in task metadata or tool arguments.

## Recovery decisions

`RecoveryManager` is fail-closed:

- `RESUME` is used for a valid checkpoint with an unchanged repository.
- `VERIFY_THEN_RESUME` is used when an action has `STARTED_UNKNOWN_RESULT`.
- `ASK_USER` is used when tracked repository files changed externally.
- `ABORT` is used when no valid checkpoint exists or the repository is missing.

Repository changes are never discarded automatically. Recovery does not run
Git reset/clean operations and does not repeat an action whose side effects are
unknown.

## Agent integration

The existing Agent loop creates checkpoints at task start, after a tool batch,
after a completed turn, and at completion or failure. Checkpoints are surfaced
through the existing `AgentEvent` queue and consumed by the print, remote, TUI,
and Todo integrations. Context state references the existing
`ContextControlPlane` snapshot; Todo state references the existing `TaskList`.

Configuration is available under `checkpoint` in the existing YAML config:

```yaml
checkpoint:
  enabled: true
  maxCheckpoints: 50
  retentionDays: 7
  autoResume: ask
  redactSecrets: true
```

The current implementation provides persistence, integrity validation, bounded
retention, repository drift classification, and safe recovery decisions. Full
interactive resume commands, in-flight process inspection, and automatic
reconstruction of a running Agent from a checkpoint remain follow-up work.
