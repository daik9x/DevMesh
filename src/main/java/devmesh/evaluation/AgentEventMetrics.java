package devmesh.evaluation;

import devmesh.agent.AgentEvent;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight observer for an existing Agent event queue; it does not execute the Agent. */
public final class AgentEventMetrics {
    private final AtomicInteger toolCalls = new AtomicInteger(); private final AtomicInteger toolFailures = new AtomicInteger(); private final AtomicInteger repairs = new AtomicInteger(); private final AtomicInteger checkpoints = new AtomicInteger(); private final AtomicInteger recoveryBlocks = new AtomicInteger(); private final AtomicInteger compactions = new AtomicInteger();
    public void observe(AgentEvent event) { switch (event) { case AgentEvent.ToolUseEvent ignored -> toolCalls.incrementAndGet(); case AgentEvent.ToolResultEvent result -> { if (result.isError()) toolFailures.incrementAndGet(); } case AgentEvent.RepairDiagnosing ignored -> repairs.incrementAndGet(); case AgentEvent.CheckpointCreated ignored -> checkpoints.incrementAndGet(); case AgentEvent.RecoveryBlocked ignored -> recoveryBlocks.incrementAndGet(); case AgentEvent.CompactEvent ignored -> compactions.incrementAndGet(); default -> {} } }
    public int toolCalls() { return toolCalls.get(); } public int toolFailures() { return toolFailures.get(); } public int repairAttempts() { return repairs.get(); } public int checkpoints() { return checkpoints.get(); } public int recoveryBlocks() { return recoveryBlocks.get(); } public int compactions() { return compactions.get(); }
}