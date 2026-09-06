# Cross-Platform Release Packaging

DevMesh release packages are built on native runners with Gradle. Each package
contains the application fat JAR, a Java 21 runtime created by `jlink`, public
launchers, empty config/data directories, a package README, and a checksum.

## Package Tasks

Run the task that matches the host operating system and architecture:

```text
./gradlew packageLinuxX64
./gradlew packageMacosX64
.\gradlew.bat packageWindowsX64
```

`packageAll` is an alias for the current native platform. It does not
cross-compile Java runtimes. The project version is the single source for
artifact names:

```text
DevMesh-<version>-linux-x64.tar.gz
DevMesh-<version>-macos-x64.tar.gz
DevMesh-<version>-windows-x64.zip
```

Artifacts and checksums are written to `build/release/`.

## Package Layout

```text
DevMesh-<version>-<platform>/
  bin/                  # platform launcher
  runtime/jdk-21/       # bundled Java runtime
  app/devmesh.jar       # application
  app/lib/              # reserved for non-fat-JAR libraries
  config/
  data/
  README.md|README.txt
  LICENSE
```

The launchers resolve their installation directory and execute
`runtime/jdk-21/bin/java` directly. They do not require `JAVA_HOME`, a system
Java installation, or a global `PATH` change. User data remains in the
workspace `.devmesh` directory, so replacing a package does not delete chats,
checkpoints, configuration, or the SQLite database.

## CI and Releases

`.github/workflows/release.yml` builds Linux x64, Windows x64, and macOS x64
on native GitHub-hosted runners when a `v*` tag is pushed. Each runner runs its
matching package task, tests the bundled launcher with `--help`, and uploads
the archive and SHA-256 checksum. The release job publishes those artifacts.

Only Linux x64 package execution has been verified in the current development
environment. Windows and macOS packaging are configured for CI and must be
reported as CI-verified only after the corresponding workflow succeeds.
