import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat
import java.io.File

plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "devmesh"
version = "2.0.0"
description = "DevMesh intelligent coding platform"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass = "devmesh.DevMesh"
}

repositories {
    mavenCentral()
}

dependencies {
    // Terminal I/O (the TUI framework's low-level driver).
    implementation("org.jline:jline:3.28.0")

    // MCP SDK
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.3")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // LLM SDKs
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.openai:openai-java:4.37.0")

    // Web server (Remote mode)
    implementation("io.javalin:javalin:6.6.0")

    // Config & JSON
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.withType<Jar> {
    manifest {
        attributes(
            "Implementation-Title" to "DevMesh",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "td"
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "devmesh"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

tasks.register<JavaExec>("engineeringValidation") {
    group = "verification"
    description = "Run the deterministic Tool Schema and 50-turn context benchmark"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("devmesh.benchmark.EngineeringValidationBenchmark")
    args(
        layout.projectDirectory.file("benchmarks/fixtures/context-50-turns.yaml").asFile.absolutePath,
        layout.projectDirectory.file("benchmarks/results/engineering-validation.json").asFile.absolutePath,
        layout.projectDirectory.file("benchmarks/engineering-validation.md").asFile.absolutePath
    )
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Run isolated deterministic Agent Evaluation benchmarks"
    dependsOn(tasks.classes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("devmesh.evaluation.BenchmarkCli")
    args("benchmark")
}

tasks.distZip { dependsOn(tasks.shadowJar) }
tasks.distTar { dependsOn(tasks.shadowJar) }
tasks.startScripts { dependsOn(tasks.shadowJar) }
tasks.named("startShadowScripts") { dependsOn(tasks.jar) }

// Native release packaging. Run each platform task on its matching runner:
// jlink creates a runtime for the host OS and architecture.
val releaseVersion = project.version.toString()
val releaseDir = layout.buildDirectory.dir("release")
val releaseModules = listOf("java.base", "java.compiler", "java.desktop", "java.instrument", "java.management", "java.naming", "java.net.http", "java.security.jgss", "java.sql", "jdk.unsupported").joinToString(",")
fun releaseName(platform: String) = "DevMesh-$releaseVersion-$platform"
fun releaseArchive(platform: String) = if (platform == "windows-x64") "${releaseName(platform)}.zip" else "${releaseName(platform)}.tar.gz"
fun currentPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    return when { os.contains("win") -> "windows-x64"; os.contains("mac") -> "macos-x64"; os.contains("linux") -> "linux-x64"; else -> "unsupported" }
}
fun requireNativePlatform(platform: String) { check(currentPlatform() == platform) { "$platform packaging must run on its native runner; current platform is ${currentPlatform()}" } }
fun releaseSha256(file: java.io.File): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

fun assembleRelease(platform: String) {
    requireNativePlatform(platform)
    val root = releaseDir.get().asFile.resolve(releaseName(platform))
    root.deleteRecursively()
    val app = root.resolve("app"); val runtime = root.resolve("runtime/jdk-21"); val bin = root.resolve("bin")
    val config = root.resolve("config"); val data = root.resolve("data")
    listOf(app, app.resolve("lib"), bin, config, data).forEach { it.mkdirs() }
    config.resolve(".keep").writeText(""); data.resolve(".keep").writeText(""); app.resolve("lib/.keep").writeText("")
    runtime.parentFile.mkdirs()
    Files.copy(tasks.shadowJar.get().archiveFile.get().asFile.toPath(), app.resolve("devmesh.jar").toPath(), StandardCopyOption.REPLACE_EXISTING)
    val jlink = File(System.getProperty("java.home"), "bin/jlink" + if (platform == "windows-x64") ".exe" else "")
    val process = ProcessBuilder(jlink.absolutePath, "--add-modules", releaseModules, "--strip-debug", "--no-man-pages", "--no-header-files", "--compress=2", "--output", runtime.absolutePath).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { "jlink failed: $output" }
    if (platform == "windows-x64") {
        bin.resolve("devmesh.bat").writeText("@echo off\r\nsetlocal\r\nset \"DEV_MESH_HOME=%~dp0..\"\r\nset \"DEV_MESH_RUNTIME=%DEV_MESH_HOME%\\runtime\\jdk-21\"\r\nset \"DEV_MESH_APP=%DEV_MESH_HOME%\\app\"\r\n\"%DEV_MESH_RUNTIME%\\bin\\java.exe\" -jar \"%DEV_MESH_APP%\\devmesh.jar\" %*\r\nexit /b %ERRORLEVEL%\r\n")
        bin.resolve("devmesh.ps1").writeText("\$ErrorActionPreference = \"Stop\"\n\$home = (Resolve-Path (Join-Path \$PSScriptRoot \"..\")).Path\n\$java = Join-Path \$home \"runtime/jdk-21/bin/java.exe\"\n\$jar = Join-Path \$home \"app/devmesh.jar\"\n& \$java -jar \$jar @args\nexit \$LASTEXITCODE\n")
        root.resolve("README.txt").writeText("DevMesh $releaseVersion - Windows x64\n\nJava 21 is bundled. No separate Java installation is required.\nRun bin\\devmesh.bat --help. User data remains in the workspace .devmesh directory.\n")
    } else {
        val launcher = bin.resolve("devmesh")
        launcher.writeText("#!/usr/bin/env sh\nset -eu\nSCRIPT_DIR=\$(CDPATH= cd -- \"\$(dirname -- \"\$0\")\" && pwd)\nDEV_MESH_HOME=\$(CDPATH= cd -- \"\$SCRIPT_DIR/..\" && pwd)\nexec \"\$DEV_MESH_HOME/runtime/jdk-21/bin/java\" -jar \"\$DEV_MESH_HOME/app/devmesh.jar\" \"\$@\"\n")
        try { Files.setPosixFilePermissions(launcher.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)) } catch (_: UnsupportedOperationException) {}
        root.resolve("README.md").writeText("# DevMesh $releaseVersion\n\nJava 21 is bundled. No separate Java installation is required.\nRun `./bin/devmesh --help`. User data remains in the workspace `.devmesh` directory.\n")
    }
    root.resolve("LICENSE").writeText("DevMesh release package. See the source repository for licensing information.\n")
    root.resolve(".release-version").writeText("$releaseVersion\n")
}

fun registerReleaseTask(platform: String) {
    val taskName = "package" + platform.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    tasks.register(taskName) {
        group = "distribution"
        description = "Build the self-contained $platform package"
        dependsOn(tasks.test, tasks.shadowJar)
        doLast {
            assembleRelease(platform)
            val root = releaseDir.get().asFile
            val archive = root.resolve(releaseArchive(platform))
            if (platform == "windows-x64") {
                ant.invokeMethod("zip", mapOf("destfile" to archive.absolutePath, "basedir" to root.absolutePath, "includes" to "${releaseName(platform)}/**"))
            } else {
                val result = ProcessBuilder("tar", "-czf", archive.absolutePath, "-C", root.absolutePath, releaseName(platform)).inheritIO().start().waitFor()
                check(result == 0) { "tar failed" }
            }
            val java = root.resolve("${releaseName(platform)}/runtime/jdk-21/bin/java" + if (platform == "windows-x64") ".exe" else "")
            check(java.isFile && root.resolve("${releaseName(platform)}/app/devmesh.jar").isFile) { "Release layout is incomplete" }
            val runtime = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true).start().let { p -> val text = p.inputStream.bufferedReader().readText(); p.waitFor(); text }
            check(runtime.contains("21")) { "Bundled runtime is not Java 21: $runtime" }
            val launcher = root.resolve("${releaseName(platform)}/bin/devmesh" + if (platform == "windows-x64") ".bat" else "")
            val helpCommand = if (platform == "windows-x64") listOf("cmd", "/c", launcher.absolutePath, "--help") else listOf(launcher.absolutePath, "--help")
            val helpProcess = ProcessBuilder(helpCommand).directory(root.resolve(releaseName(platform))).redirectErrorStream(true).start()
            val helpOutput = helpProcess.inputStream.bufferedReader().readText()
            check(helpProcess.waitFor() == 0 && helpOutput.contains("DevMesh")) { "Packaged launcher smoke test failed: $helpOutput" }
            root.resolve("SHA256SUMS-$platform.txt").writeText("${releaseSha256(archive)}  ${archive.name}\n")
        }
    }
}
registerReleaseTask("windows-x64")
registerReleaseTask("linux-x64")
registerReleaseTask("macos-x64")
tasks.register("packageAll") { group = "distribution"; description = "Build the package for the current native platform"; dependsOn("package${currentPlatform().split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }}") }
