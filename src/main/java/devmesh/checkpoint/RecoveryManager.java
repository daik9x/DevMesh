package devmesh.checkpoint;

import java.util.Optional;

/** Fail-closed recovery decision layer; it never executes or rolls back actions. */
public final class RecoveryManager {
    private final CheckpointManager checkpoints;
    private final RepositoryRecoveryValidator repositoryValidator = new RepositoryRecoveryValidator();
    public RecoveryManager(CheckpointManager checkpoints) { this.checkpoints = checkpoints; }

    public RecoveryResult decide(String sessionId) {
        Optional<Checkpoint> latest = checkpoints.latestValid(sessionId);
        if (latest.isEmpty()) return new RecoveryResult(RecoveryDecision.ABORT, null, "no valid checkpoint");
        Checkpoint checkpoint = latest.get();
        RepositoryRecoveryState repo = repositoryValidator.validate(checkpoint);
        if (repo == RepositoryRecoveryState.REPOSITORY_MISSING) return new RecoveryResult(RecoveryDecision.ABORT, checkpoint, "repository missing");
        Object action = checkpoint.executionState().get("actionStatus");
        if (ActionStatus.STARTED_UNKNOWN_RESULT.name().equals(action)) {
            return new RecoveryResult(RecoveryDecision.VERIFY_THEN_RESUME, checkpoint, "action has unknown side effects");
        }
        if (repo == RepositoryRecoveryState.EXTERNAL_CHANGES || repo == RepositoryRecoveryState.CONFLICTING_CHANGES) {
            return new RecoveryResult(RecoveryDecision.ASK_USER, checkpoint, repo.name().toLowerCase());
        }
        return new RecoveryResult(RecoveryDecision.RESUME, checkpoint, "latest checkpoint is valid");
    }

    public record RecoveryResult(RecoveryDecision decision, Checkpoint checkpoint, String reason) {}
}