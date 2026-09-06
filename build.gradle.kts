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
