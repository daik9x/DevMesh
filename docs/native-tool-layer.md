# Native Tool Layer 2.0

DevMesh prefers structured Java operations when they are sufficient and keeps
shell execution as a controlled fallback for operations that require an
external process.

| Operation | Native | Shell fallback |
|---|---:|---:|
| Read file | YES | YES |
| Write/edit file | YES | YES |
| Create directory | YES | YES |
| Copy/move/delete file | YES | YES |
| Directory/file metadata | YES | YES |
| File hash | YES | YES |
| Search files | YES | YES |
| Search text/regex | YES | YES |
| JSON/YAML operations | PARTIAL | YES |
| Git status/diff/history | NOT IMPLEMENTED | YES |
| Build/test | PARTIAL | YES |
| Run application | NO | YES |
| External compiler/package manager | NO | YES |

## Current native tools

`ReadFile`, `WriteFile`, `EditFile`, `Glob`, and `Grep` use Java filesystem
APIs. The following deferred tools are also available through progressive
discovery:

- `CreateDirectory`
- `CopyFile`
- `MoveFile`
- `DeleteFile`
- `HashFile`
- `FileInfo`

Native capabilities declare implementation type, structured-output support,
cross-platform support, shell dependency, and external-process dependency.
`ToolSelector` gives native and structured tools a deterministic preference over
shell tools when their intent matches. Existing `Bash` support remains
available for commands such as builds, tests, Git operations, and application
execution.

All new filesystem tools use Java NIO and pass through the existing permission
checker path classification. They do not invoke `cmd`, PowerShell, Bash, `cp`,
`mv`, `rm`, `mkdir`, `ls`, `find`, `grep`, or `rg`.

## Verification

The focused native tool tests cover create/copy/move/delete, metadata, hashes,
missing paths, selector ordering, deferred discovery, and permission checks.
The existing engineering validation benchmark also tracks the expanded native
tool inventory and progressive schema counts.

## Limitations

Git read operations, build/test execution, JSON/YAML editing, structured patch
application, process inspection, and native archive operations still use the
existing implementations or shell/process fallback paths. They are not marked
as native until an implementation and test exist.
