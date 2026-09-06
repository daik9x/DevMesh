package devmesh.evaluation;

import java.io.IOException;
import java.nio.file.*;

public final class FixtureManager {
    public Path prepare(BenchmarkScenario scenario, Path fixtureRoot) throws IOException {
        Path source = fixtureRoot.resolve(scenario.repositoryFixture()).normalize();
        if (!source.startsWith(fixtureRoot.normalize()) || !Files.exists(source)) throw new IOException("Fixture is missing or outside fixture root: " + source);
        Path destination = Files.createTempDirectory("devmesh-benchmark-" + scenario.id() + "-");
        copy(source, destination);
        return destination;
    }
    private static void copy(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) { for (Path path : paths.toList()) { Path target = destination.resolve(source.relativize(path).toString()); if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES); } }
    }
}